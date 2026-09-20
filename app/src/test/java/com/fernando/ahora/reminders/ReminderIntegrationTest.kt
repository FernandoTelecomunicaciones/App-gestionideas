package com.fernando.ahora.reminders

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.data.TaskRepositoryImpl
import com.fernando.ahora.data.local.AhoraDatabase
import com.fernando.ahora.data.local.RoomReminderStore
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.testing.EventLog
import com.fernando.ahora.testing.FakeNotifier
import com.fernando.ahora.testing.FakeScheduler
import com.fernando.ahora.testing.FakeTimeProvider
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real repository + real Room + the real reconciler, wired exactly as in production
 * (repository -> ReminderSync = reconciler). Only the two Android edges (alarm, tray) are fakes.
 * This is also GA-01's proof that a repository mutation reaches the reconciler.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderIntegrationTest {
    private val time = FakeTimeProvider(Instant.parse("2026-09-20T10:00:00Z")) // 12:00 Madrid
    private val log = EventLog()
    private val scheduler = FakeScheduler(log)
    private val notifier = FakeNotifier(log)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AhoraDatabase
    private lateinit var store: RoomReminderStore
    private lateinit var reconciler: ReminderReconciler
    private lateinit var repo: TaskRepositoryImpl
    private lateinit var actions: ReminderActionHandler

    private val today = LocalDate.of(2026, 9, 20)
    private val tomorrow = today.plusDays(1)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AhoraDatabase::class.java)
            .allowMainThreadQueries().build()
        store = RoomReminderStore(db.taskDao())
        reconciler = ReminderReconciler(store, scheduler, notifier, time, scope)
        repo = TaskRepositoryImpl(db, db.taskDao(), time, reconciler)
        actions = ReminderActionHandler(repo, store, notifier, reconciler, time)
    }

    @After
    fun tearDown() = db.close()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private val at18 = Instant.parse("2026-09-20T16:00:00Z") // 18:00 Madrid

    // ---- GA-01 / scheduling on every mutation ----------------------------------------------

    @Test
    fun creatingAReminder_armsTheCursorAtItsDueInstant_beforeCreateReturns() = run {
        repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))
        assertEquals(at18, scheduler.armed)
    }

    @Test
    fun editingTheSchedule_rearms_andRemovesTheStaleCard() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        store.markDelivered(id, 0, time.instant)
        notifier.cards += ActiveCard(id, 0) // the card for revision 0 is on screen

        repo.edit(id, TaskFields("x", dueDate = today, dueTime = LocalTime.of(20, 0), reminderEnabled = true))

        assertEquals(Instant.parse("2026-09-20T18:00:00Z"), scheduler.armed)
        assertTrue("stale card removed", notifier.cards.isEmpty())
    }

    @Test
    fun disablingTheReminder_cancelsTheCursorAndTheCard() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        notifier.cards += ActiveCard(id, 0)
        repo.edit(id, TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = false))
        assertNull(scheduler.armed)
        assertTrue(notifier.cards.isEmpty())
    }

    @Test
    fun completingAScheduledTask_cancelsTheCursorAndTheCard() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        notifier.cards += ActiveCard(id, 0)
        repo.complete(id)
        assertNull(scheduler.armed)
        assertTrue(notifier.cards.isEmpty())
    }

    @Test
    fun deletingAScheduledTask_cancelsTheCursorAndTheCard_undoRestoresIt() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        notifier.cards += ActiveCard(id, 0)
        val removed = repo.delete(id)!!
        assertNull(scheduler.armed)
        assertTrue(notifier.cards.isEmpty())

        assertTrue(repo.undoDelete(removed))
        assertEquals(at18, scheduler.armed)
    }

    @Test
    fun postponing_movesTheCursorToTomorrow_andUndoMovesItBack() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        val p = repo.postpone(id)!!
        assertEquals(Instant.parse("2026-09-21T16:00:00Z"), scheduler.armed)
        assertTrue(repo.undoPostpone(p))
        assertEquals(at18, scheduler.armed)
    }

    @Test
    fun importingReplacesTheCursorAndClearsTheWholeTray() = run {
        repo.create(TaskFields("old", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))
        notifier.cards += ActiveCard(1, 0)
        notifier.cards += ActiveCard(77, 0)
        val imported = listOf(
            com.fernando.ahora.testing.aTask(5, "new", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true),
        )
        repo.replaceAll(imported)
        assertTrue(log.events.contains("cancel-all"))
        assertTrue(notifier.cards.isEmpty())
        assertEquals(Instant.parse("2026-09-21T07:00:00Z"), scheduler.armed)
    }

    // ---- full delivery + actions ------------------------------------------------------------

    @Test
    fun aDueReminder_isDelivered_thenHechoCompletesItIdempotently_andDismissesTheCard() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        time.instant = at18
        reconciler.reconcileNow("alarm")
        assertEquals(listOf(id), notifier.posted)
        val card = notifier.cards.single()

        actions.done(id, card.revision)
        actions.done(id, card.revision) // second tap: silent no-op

        assertTrue(repo.get(id)!!.done)
        assertTrue(notifier.cards.isEmpty())
        assertEquals(1, repo.exportAll().size)
    }

    @Test
    fun snooze_rearmsTenMinutesAfterTheTap_leavesTheDueTimeAlone_andRedeliversLater() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        time.instant = at18
        reconciler.reconcileNow("alarm")
        val card = notifier.cards.single()

        actions.snooze(id, card.revision)

        val t = repo.get(id)!!
        assertEquals(at18.plusSeconds(600), t.reminderSnoozeUntil)
        assertEquals(LocalTime.of(18, 0), t.dueTime) // the plan is untouched
        assertEquals(at18.plusSeconds(600), scheduler.armed)
        assertTrue(notifier.cards.isEmpty())

        time.instant = at18.plusSeconds(601)
        reconciler.reconcileNow("alarm")
        assertEquals(listOf(id, id), notifier.posted) // delivered again
        assertNull(repo.get(id)!!.reminderSnoozeUntil)
    }

    @Test
    fun aStaleSnoozeAction_afterTheScheduleMoved_neverOverwritesTheNewSchedule() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        time.instant = at18
        reconciler.reconcileNow("alarm")
        val staleRevision = notifier.cards.single().revision

        repo.edit(id, TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true)) // moved
        actions.snooze(id, staleRevision) // the OLD card's +10 MIN arrives late

        val t = repo.get(id)!!
        assertNull("stale snooze must not apply", t.reminderSnoozeUntil)
        assertEquals(tomorrow, t.dueDate)
        assertEquals(Instant.parse("2026-09-21T07:00:00Z"), scheduler.armed) // the new schedule is armed
    }

    @Test
    fun aStaleHechoAction_afterTheScheduleMoved_doesNotCompleteTheTask() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        time.instant = at18
        reconciler.reconcileNow("alarm")
        val staleRevision = notifier.cards.single().revision
        repo.edit(id, TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))

        actions.done(id, staleRevision)

        assertFalse(repo.get(id)!!.done)
    }

    @Test
    fun aSnoozeForATaskThatNeverFired_isIgnored() = run {
        val id = repo.create(TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!
        actions.snooze(id, 0)
        assertNull(repo.get(id)!!.reminderSnoozeUntil)
    }

    @Test
    fun actionsOnADeletedTask_areSilentNoOps() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        repo.delete(id)
        actions.done(id, 0)
        actions.snooze(id, 0)
        assertNull(repo.get(id))
    }

    @Test
    fun aRecurringTaskCompletedFromTheNotification_yieldsExactlyOneArmedSuccessor() = run {
        val id = repo.create(
            TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true, recurrence = Recurrence.DAILY),
        )!!
        time.instant = at18
        reconciler.reconcileNow("alarm")
        val card = notifier.cards.single()

        actions.done(id, card.revision)
        actions.done(id, card.revision)

        val open = repo.exportAll().filter { !it.done }
        assertEquals(1, open.size)
        assertEquals(tomorrow, open.single().dueDate)
        assertEquals(Instant.parse("2026-09-21T16:00:00Z"), scheduler.armed)
    }

    // ---- races (review #2 A2-01) ------------------------------------------------------------

    @Test
    fun editVersusReconcile_theNewScheduleSurvives_andNoStaleCardRemains() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        time.instant = at18

        // A real concurrent edit runs on ANOTHER thread and only commits to Room; its own post-commit reconcile
        // then queues behind the one in flight. (Calling repo.edit from inside the notifier would deadlock on the
        // reconciler's own mutex by construction — which is exactly why the reconciler never calls a repository
        // mutation.) So model the interleaving: the edit COMMITS between the candidate read and the mark.
        val commitOnly = TaskRepositoryImpl(db, db.taskDao(), time, com.fernando.ahora.testing.RecordingReminderSync())
        notifier.onPost = {
            runBlocking {
                commitOnly.edit(id, TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))
            }
        }
        reconciler.reconcileNow("alarm") // read A -> post A -> [edit commits B] -> guarded mark must fail -> cancel A's card

        val afterFirst = repo.get(id)!!
        assertEquals(tomorrow, afterFirst.dueDate)
        assertNull("the new schedule must NOT be consumed", afterFirst.reminderFiredAt)
        assertTrue("no actionable stale card", notifier.cards.none { it.taskId == id })

        // the edit's own post-commit reconcile then runs and arms the new schedule
        notifier.onPost = {}
        reconciler.reconcileNow("post-edit")
        assertEquals(Instant.parse("2026-09-21T07:00:00Z"), scheduler.armed)
        assertEquals("nothing was delivered for the new schedule yet", listOf(id), notifier.posted)
    }

    // ---- recovery ---------------------------------------------------------------------------

    @Test
    fun rebootRecovery_aFreshProcessRebuildsTheCursorFromRoom() = run {
        repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))
        // reboot: alarms are gone, a NEW scheduler/reconciler exists over the same database
        val rebootedScheduler = FakeScheduler()
        val rebooted = ReminderReconciler(store, rebootedScheduler, FakeNotifier(), time, scope)
        assertNull(rebootedScheduler.armed)

        rebooted.reconcileNow("android.intent.action.BOOT_COMPLETED")

        assertEquals(at18, rebootedScheduler.armed)
    }

    @Test
    fun killAfterCommit_theNextStartHealsTheMissingArming() = run {
        // Simulate "process died between the Room commit and the alarm call": commit with a no-op sync.
        val committedOnly = TaskRepositoryImpl(db, db.taskDao(), time, com.fernando.ahora.testing.RecordingReminderSync())
        committedOnly.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))
        assertNull("nothing armed yet", scheduler.armed)

        reconciler.reconcileNow("app-start")

        assertEquals(at18, scheduler.armed)
    }

    @Test
    fun timezoneChange_rearmsWithoutTouchingTheStoredTask() = run {
        val id = repo.create(TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        val before = repo.get(id)!!
        time.zoneId = java.time.ZoneId.of("America/New_York")
        reconciler.reconcileNow("android.intent.action.TIMEZONE_CHANGED")
        assertEquals(Instant.parse("2026-09-21T22:00:00Z"), scheduler.armed)
        assertEquals(before, repo.get(id)) // floating local time: no data migration
    }

    @Test
    fun aMissedReminder_isDeliveredOnceLate_thenNeverAgain() = run {
        repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))
        time.instant = at18.plusSeconds(3 * 3600) // device was off; back 3 h later
        reconciler.reconcileNow("android.intent.action.BOOT_COMPLETED")
        reconciler.reconcileNow("app-start")
        assertEquals(1, notifier.posted.size)
        assertNotNull(repo.exportAll().single().reminderFiredAt)
    }
}
