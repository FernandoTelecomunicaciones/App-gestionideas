package com.fernando.ahora.reminders

import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.testing.EventLog
import com.fernando.ahora.testing.FakeNotifier
import com.fernando.ahora.testing.FakeReminderStore
import com.fernando.ahora.testing.FakeScheduler
import com.fernando.ahora.testing.FakeTimeProvider
import com.fernando.ahora.testing.aTask
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Pure-JVM tests of the reminder engine's decisions. Room-backed interleavings live in ReminderIntegrationTest. */
class ReminderReconcilerTest {
    // 2026-09-20T10:00Z = 12:00 Madrid (CEST)
    private val time = FakeTimeProvider(Instant.parse("2026-09-20T10:00:00Z"))
    private val log = EventLog()
    private val store = FakeReminderStore(log)
    private val scheduler = FakeScheduler(log)
    private val notifier = FakeNotifier(log)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var reconciler: ReminderReconciler

    private val today = LocalDate.of(2026, 9, 20)
    private val now get() = time.instant

    @Before
    fun setUp() {
        reconciler = ReminderReconciler(store, scheduler, notifier, time, scope)
    }

    private fun reminder(id: Long, date: LocalDate, at: LocalTime, rev: Int = 0): Task =
        aTask(id, dueDate = date, dueTime = at, reminderEnabled = true).copy(reminderRevision = rev)

    private fun reconcile() = runBlocking { reconciler.reconcileNow("test") }

    // ---- single-alarm cursor --------------------------------------------------------------

    @Test
    fun arms_theEarliestFutureReminder_asTheSingleCursor() {
        store.put(reminder(1, today, LocalTime.of(18, 0)))                  // 16:00Z
        store.put(reminder(2, today.plusDays(1), LocalTime.of(9, 0)))       // next day 07:00Z
        reconcile()
        assertEquals(Instant.parse("2026-09-20T16:00:00Z"), scheduler.armed)
        assertEquals(1, scheduler.arms.size) // one alarm, not one per task
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun noReminders_cancelsTheCursor() {
        scheduler.armed = Instant.EPOCH
        reconcile()
        assertNull(scheduler.armed)
    }

    @Test
    fun undefinedReminders_areIgnored() {
        store.put(aTask(1, dueDate = today, dueTime = LocalTime.of(18, 0))) // toggle off
        store.put(aTask(2, dueDate = today, reminderEnabled = true))          // no time
        reconcile()
        assertNull(scheduler.armed)
    }

    // ---- delivery -------------------------------------------------------------------------

    @Test
    fun dueWithinGrace_isDelivered_marked_andTheCursorMovesToTheNextOne() {
        store.put(reminder(1, today, LocalTime.of(8, 0)))   // 06:00Z: 4 h late -> deliver
        store.put(reminder(2, today, LocalTime.of(18, 0)))  // 16:00Z
        reconcile()
        assertEquals(listOf(1L), notifier.posted)
        assertEquals(now, store.tasks[1]!!.reminderFiredAt)
        assertEquals(Instant.parse("2026-09-20T16:00:00Z"), scheduler.armed)
    }

    @Test
    fun dueBeyondGrace_isDroppedSilently_butStillMarkedConsumed() {
        store.put(reminder(1, today.minusDays(1), LocalTime.of(9, 0))) // >12 h late
        reconcile()
        assertTrue(notifier.posted.isEmpty())
        assertNotNull(store.tasks[1]!!.reminderFiredAt)
        assertNull(scheduler.armed)
    }

    @Test
    fun notificationsDisabled_consumesSilently_andNeverThrows() {
        notifier.notificationsEnabled = false
        store.put(reminder(1, today, LocalTime.of(8, 0)))
        reconcile()
        assertTrue(notifier.posted.isEmpty())
        assertNotNull(store.tasks[1]!!.reminderFiredAt)
    }

    @Test
    fun snooze_overridesTheDueTime_thenDeliversAtTheSnoozeInstant() {
        val snooze = Instant.parse("2026-09-20T10:10:00Z")
        store.put(reminder(1, today, LocalTime.of(8, 0)).copy(reminderFiredAt = Instant.EPOCH, reminderSnoozeUntil = snooze))
        reconcile()
        assertTrue(notifier.posted.isEmpty())
        assertEquals(snooze, scheduler.armed)

        time.instant = Instant.parse("2026-09-20T10:11:00Z")
        reconcile()
        assertEquals(listOf(1L), notifier.posted)
        assertNull(store.tasks[1]!!.reminderSnoozeUntil)
        assertNull(scheduler.armed)
    }

    // ---- ARM BEFORE DELIVER (review #1 CR-2, review #2 A2-02) ------------------------------

    @Test
    fun armsTheCursorBeforeAnyNotificationIsPosted() {
        store.put(reminder(1, today, LocalTime.of(8, 0)))
        store.put(reminder(2, today, LocalTime.of(18, 0)))
        reconcile()
        val firstArm = log.indexOfFirst("arm:")
        val firstPost = log.indexOfFirst("post:")
        assertTrue("cursor must be armed first: ${log.events}", firstArm in 0 until firstPost)
    }

    @Test
    fun aFailingDelivery_leavesAnArmedRetryCursor_andTheItemUnmarked() {
        store.put(reminder(1, today, LocalTime.of(8, 0)))
        notifier.failFor[1] = IllegalStateException("boom")
        reconcile()
        assertNull(store.tasks[1]!!.reminderFiredAt)
        assertEquals(now.plus(Duration.ofSeconds(60)), scheduler.armed) // retry cursor, never stranded
    }

    @Test
    fun aPoisonItem_doesNotBlockOthers_andIsEventuallyConsumed() {
        store.put(reminder(1, today, LocalTime.of(8, 0)))   // poison
        store.put(reminder(2, today, LocalTime.of(9, 0)))
        store.put(reminder(3, today, LocalTime.of(10, 0)))
        notifier.failFor[1] = IllegalStateException("always fails")

        reconcile() // failure #1
        assertEquals(listOf(2L, 3L), notifier.posted) // the others got through in the same pass
        assertNull(store.tasks[1]!!.reminderFiredAt)
        assertNotNull(store.tasks[2]!!.reminderFiredAt)
        assertNotNull(store.tasks[3]!!.reminderFiredAt)
        assertEquals(now.plus(Duration.ofSeconds(60)), scheduler.armed)

        reconcile() // failure #2
        assertNull(store.tasks[1]!!.reminderFiredAt)
        reconcile() // failure #3 -> bounded: consumed, no infinite retry loop
        assertNotNull(store.tasks[1]!!.reminderFiredAt)
        assertNull(scheduler.armed)
    }

    @Test
    fun aPermanentFailure_isConsumedImmediately() {
        store.put(reminder(1, today, LocalTime.of(8, 0)))
        notifier.failFor[1] = PermanentNotificationFailure("no permission")
        reconcile()
        assertNotNull(store.tasks[1]!!.reminderFiredAt)
        assertNull(scheduler.armed)
    }

    @Test
    fun aSecurityException_isPermanent() {
        store.put(reminder(1, today, LocalTime.of(8, 0)))
        notifier.failFor[1] = SecurityException("denied")
        reconcile()
        assertNotNull(store.tasks[1]!!.reminderFiredAt)
    }

    @Test
    fun theDeliveryBudget_stopsWork_andLeavesTheRetryCursorForTheRest() {
        store.put(reminder(1, today, LocalTime.of(8, 0)))
        store.put(reminder(2, today, LocalTime.of(9, 0)))
        store.put(reminder(3, today, LocalTime.of(10, 0)))
        var t = 0L
        reconciler.nanoClock = { t += 4_000_000_000L; t } // every clock read advances 4 s; budget is 6 s
        reconcile()
        assertEquals(listOf(1L), notifier.posted) // only the first fit in the budget
        assertNull(store.tasks[2]!!.reminderFiredAt)
        assertEquals(now.plus(Duration.ofSeconds(60)), scheduler.armed)
    }

    @Test
    fun cancelledDuringDelivery_theCursorWasAlreadyArmed() = runBlocking {
        // Simulates the receiver's withTimeout firing while notifications are being posted.
        store.put(reminder(1, today, LocalTime.of(8, 0)))
        lateinit var job: Job
        notifier.onPost = { job.cancel() }
        job = launch(Dispatchers.Default) { reconciler.reconcileNow("timeout") }
        job.join()
        assertNotNull("a cursor must exist even though delivery was cancelled", scheduler.armed)
    }

    // ---- revision guard (review #2 A2-01) -------------------------------------------------

    @Test
    fun aScheduleEditedMidFlight_isNeverMarkedConsumed_andItsStaleCardIsCancelled() {
        store.put(reminder(1, today, LocalTime.of(8, 0)))
        // Between reading the candidate and marking it, the user moves the reminder (revision 0 -> 1).
        notifier.onPost = { t ->
            store.put(store.tasks[t.id]!!.copy(reminderRevision = 1, reminderFiredAt = null, dueDate = today.plusDays(5)))
        }
        reconcile()
        assertNull("the NEW schedule must not be marked consumed", store.tasks[1]!!.reminderFiredAt)
        assertTrue("the stale card must be gone", notifier.cards.none { it.taskId == 1L })
    }

    // ---- tray sweep (review #1 CR-3) ------------------------------------------------------

    @Test
    fun sweep_keepsOnlyCardsThatStillMatchRoomExactly() {
        val fired = Instant.parse("2026-09-20T09:00:00Z")
        store.put(reminder(1, today, LocalTime.of(8, 0), rev = 0).copy(reminderFiredAt = fired))                 // valid
        store.put(reminder(2, today, LocalTime.of(8, 0), rev = 1).copy(reminderFiredAt = fired))                 // moved (rev differs)
        store.put(reminder(4, today, LocalTime.of(8, 0)).copy(reminderFiredAt = fired, done = true))            // completed
        store.put(reminder(5, today, LocalTime.of(8, 0)).copy(reminderFiredAt = fired, reminderEnabled = false)) // disabled
        // schedule reset AND still in the future (a due one would legitimately be re-delivered by this same pass)
        store.put(reminder(6, today, LocalTime.of(18, 0)).copy(reminderFiredAt = null))
        listOf(1L, 2L, 3L, 4L, 5L, 6L).forEach { notifier.cards += ActiveCard(it, 0) } // 3 = deleted (missing)
        reconcile()
        assertEquals(listOf(1L), notifier.cards.map { it.taskId })
    }

    @Test
    fun onTasksChanged_cancelsTheCardsFastPath_thenReplans() = runBlocking {
        store.put(reminder(1, today, LocalTime.of(18, 0)))
        notifier.cards += ActiveCard(1, 0)
        reconciler.onTasksChanged(setOf(1L), "edit")
        assertTrue(log.events.contains("cancel:1"))
        assertEquals(Instant.parse("2026-09-20T16:00:00Z"), scheduler.armed)
    }

    @Test
    fun resetAll_cancelsCursorAndTray_thenReplansFromRoom() = runBlocking {
        store.put(reminder(1, today, LocalTime.of(18, 0)))
        notifier.cards += ActiveCard(9, 0)
        reconciler.resetAll("import")
        assertTrue(log.events.contains("cancel-cursor"))
        assertTrue(log.events.contains("cancel-all"))
        assertEquals(Instant.parse("2026-09-20T16:00:00Z"), scheduler.armed)
    }

    // ---- idempotence, non-reentrancy, deadlock freedom ------------------------------------

    @Test
    fun reconcile_isIdempotent() {
        store.put(reminder(1, today, LocalTime.of(8, 0)))
        store.put(reminder(2, today, LocalTime.of(18, 0)))
        reconcile()
        val firstArmed = scheduler.armed
        reconcile()
        reconcile()
        assertEquals(listOf(1L), notifier.posted) // no duplicate delivery
        assertEquals(firstArmed, scheduler.armed)
    }

    @Test
    fun aStormOfRequests_completesWithoutDeadlock_andDeliversExactlyOnce() = runBlocking {
        store.put(reminder(1, today, LocalTime.of(8, 0)))
        store.put(reminder(2, today, LocalTime.of(18, 0)))
        withTimeout(10_000) { (1..50).map { reconciler.request("storm-$it") }.joinAll() }
        assertEquals(listOf(1L), notifier.posted)
        assertEquals(Instant.parse("2026-09-20T16:00:00Z"), scheduler.armed)
    }

    @Test
    fun aReconcileThatTriggersAnotherRequest_doesNotDeadlockOnItsOwnMutex() = runBlocking {
        store.put(reminder(1, today, LocalTime.of(8, 0)))
        var spawned: Job? = null
        notifier.onPost = { spawned = reconciler.request("self-trigger") } // re-entrant trigger from inside a reconcile
        withTimeout(10_000) {
            reconciler.reconcileNow("outer")
            spawned?.join()
        }
        assertEquals(listOf(1L), notifier.posted)
    }

    // ---- timezone / DST / clock -----------------------------------------------------------

    @Test
    fun timezoneChange_rearmsForTheNewZone_withNoDataChange() {
        store.put(reminder(1, today.plusDays(1), LocalTime.of(18, 0)))
        reconcile()
        assertEquals(Instant.parse("2026-09-21T16:00:00Z"), scheduler.armed) // Madrid CEST
        time.zoneId = ZoneId.of("America/New_York")
        reconcile()
        assertEquals(Instant.parse("2026-09-21T22:00:00Z"), scheduler.armed) // New York EDT
    }

    @Test
    fun dstGap_armsAtTheFirstValidInstantAfterTheGap() {
        time.instant = Instant.parse("2026-03-28T10:00:00Z")
        store.put(reminder(1, LocalDate.of(2026, 3, 29), LocalTime.of(2, 30)))
        reconcile()
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), scheduler.armed)
    }

    @Test
    fun dstOverlap_armsAtTheEarlierOccurrence() {
        time.instant = Instant.parse("2026-10-24T10:00:00Z")
        store.put(reminder(1, LocalDate.of(2026, 10, 25), LocalTime.of(2, 30)))
        reconcile()
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), scheduler.armed)
    }

    @Test
    fun clockJumpedForward_pastADueReminder_deliversItLate_withinGrace() {
        store.put(reminder(1, today, LocalTime.of(18, 0))) // 16:00Z
        reconcile()
        assertTrue(notifier.posted.isEmpty())
        time.instant = Instant.parse("2026-09-20T20:00:00Z") // user set the clock 4 h ahead
        reconcile()
        assertEquals(listOf(1L), notifier.posted)
    }

    @Test
    fun clockJumpedBackward_rearmsToTheNewFuture() {
        store.put(reminder(1, today, LocalTime.of(18, 0)))
        time.instant = Instant.parse("2026-09-20T13:00:00Z")
        reconcile()
        assertEquals(Instant.parse("2026-09-20T16:00:00Z"), scheduler.armed)
        time.instant = Instant.parse("2026-09-19T13:00:00Z") // a day earlier
        reconcile()
        assertEquals(Instant.parse("2026-09-20T16:00:00Z"), scheduler.armed)
        assertFalse(notifier.posted.contains(1L))
    }
}
