package com.fernando.ahora.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.data.local.AhoraDatabase
import com.fernando.ahora.data.local.RoomReminderStore
import com.fernando.ahora.domain.model.CompleteResult
import com.fernando.ahora.domain.model.EditResult
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.ReplaceResult
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.domain.rules.TaskRules
import com.fernando.ahora.testing.FakeTimeProvider
import com.fernando.ahora.testing.RecordingReminderSync
import com.fernando.ahora.testing.aTask
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
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

/** Real Room (in-memory) through Robolectric: transactions, invariants and lifecycle. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TaskRepositoryTest {
    // 2026-09-20T10:00Z = 12:00 in Madrid (CEST)
    private val time = FakeTimeProvider(Instant.parse("2026-09-20T10:00:00Z"))
    private val sync = RecordingReminderSync()
    private lateinit var db: AhoraDatabase
    private lateinit var repo: TaskRepositoryImpl
    private lateinit var store: RoomReminderStore

    private val today = LocalDate.of(2026, 9, 20)
    private val tomorrow = today.plusDays(1)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AhoraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TaskRepositoryImpl(db, db.taskDao(), time, sync)
        store = RoomReminderStore(db.taskDao())
    }

    @After
    fun tearDown() = db.close()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    // ---- create / quick capture -----------------------------------------------------------

    @Test
    fun quickCapture_titleOnly_persistsAsInboxItem() = run {
        val id = repo.create(TaskFields("Llamar al dentista"))!!
        val t = repo.get(id)!!
        assertTrue(t.isInbox)
        assertEquals(0, t.reminderRevision)
        assertEquals(listOf(t), repo.observePending().first())
        assertEquals(setOf(id) to "create", sync.changed.single())
    }

    @Test
    fun create_blankTitle_isRejectedAndWritesNothing() = run {
        assertNull(repo.create(TaskFields("   ")))
        assertTrue(repo.exportAll().isEmpty())
        assertTrue(sync.changed.isEmpty())
    }

    @Test
    fun create_normalisesDependentFields() = run {
        val id = repo.create(TaskFields("x", dueTime = LocalTime.NOON, reminderEnabled = true, recurrence = Recurrence.DAILY))!!
        val t = repo.get(id)!!
        assertNull(t.dueTime)
        assertFalse(t.reminderEnabled)
        assertEquals(Recurrence.NONE, t.recurrence)
        assertNull(t.recurrenceAnchor)
    }

    @Test
    fun create_recurring_setsAnchorToDueDate() = run {
        val id = repo.create(TaskFields("x", dueDate = tomorrow, recurrence = Recurrence.MONTHLY))!!
        assertEquals(tomorrow, repo.get(id)!!.recurrenceAnchor)
    }

    @Test
    fun create_reminderInThePast_isConsumedNotRetroactive() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(8, 0), reminderEnabled = true))!!
        assertEquals(time.instant, repo.get(id)!!.reminderFiredAt)
    }

    @Test
    fun create_reminderInTheFuture_staysPending() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        assertNull(repo.get(id)!!.reminderFiredAt)
        assertEquals(1, store.candidates().size)
    }

    // ---- edit (R2-4) ----------------------------------------------------------------------

    @Test
    fun edit_mergesEditableFieldsOnly_andKeepsBookkeeping() = run {
        val id = repo.create(TaskFields("old"))!!
        val r = repo.edit(id, TaskFields("new", notes = "n", priority = Priority.P1, listName = "Casa"))
        assertTrue(r is EditResult.Saved)
        val t = repo.get(id)!!
        assertEquals("new", t.title)
        assertEquals(Priority.P1, t.priority)
        assertEquals("Casa", t.listName)
        assertFalse(t.done)
        assertEquals(0, t.reminderRevision) // title/priority are not scheduling changes
    }

    @Test
    fun edit_titleOnly_doesNotTouchTheReminderEngine() = run {
        val id = repo.create(TaskFields("old"))!!
        sync.changed.clear()
        repo.edit(id, TaskFields("new"))
        assertTrue(sync.changed.isEmpty())
    }

    @Test
    fun edit_scheduleChange_bumpsRevision_resetsReminderState_andSyncs() = run {
        val id = repo.create(TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!
        assertTrue(store.markDelivered(id, 0, time.instant))
        sync.changed.clear()

        repo.edit(id, TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(10, 0), reminderEnabled = true))

        val t = repo.get(id)!!
        assertEquals(1, t.reminderRevision)
        assertNull(t.reminderFiredAt)
        assertNull(t.reminderSnoozeUntil)
        assertEquals(setOf(id) to "edit", sync.changed.single())
    }

    @Test
    fun edit_onCompletedTask_returnsAlreadyCompleted_andNeverUncompletes() = run {
        val id = repo.create(TaskFields("x"))!!
        repo.complete(id)
        val r = repo.edit(id, TaskFields("hacked", priority = Priority.P1))
        assertEquals(EditResult.AlreadyCompleted, r)
        val t = repo.get(id)!!
        assertTrue(t.done)
        assertEquals("x", t.title)
    }

    @Test
    fun edit_onDeletedTask_returnsNotFound_andDoesNotResurrect() = run {
        val id = repo.create(TaskFields("x"))!!
        repo.delete(id)
        assertEquals(EditResult.NotFound, repo.edit(id, TaskFields("y")))
        assertNull(repo.get(id))
    }

    @Test
    fun edit_blankTitle_isInvalid() = run {
        val id = repo.create(TaskFields("x"))!!
        assertEquals(EditResult.InvalidTitle, repo.edit(id, TaskFields(" ")))
    }

    @Test
    fun edit_pastReminder_isConsumedImmediately() = run {
        val id = repo.create(TaskFields("x"))!!
        repo.edit(id, TaskFields("x", dueDate = today, dueTime = LocalTime.of(8, 0), reminderEnabled = true))
        assertNotNull(repo.get(id)!!.reminderFiredAt)
    }

    @Test
    fun edit_anchorLifecycle_R2_6() = run {
        val id = repo.create(TaskFields("x", dueDate = today, recurrence = Recurrence.MONTHLY))!!
        assertEquals(today, repo.get(id)!!.recurrenceAnchor)

        // date edited -> anchor follows
        repo.edit(id, TaskFields("x", dueDate = tomorrow, recurrence = Recurrence.MONTHLY))
        assertEquals(tomorrow, repo.get(id)!!.recurrenceAnchor)

        // recurrence removed -> anchor cleared
        repo.edit(id, TaskFields("x", dueDate = tomorrow, recurrence = Recurrence.NONE))
        assertNull(repo.get(id)!!.recurrenceAnchor)

        // re-enabled -> re-initialised from the current date
        repo.edit(id, TaskFields("x", dueDate = tomorrow, recurrence = Recurrence.WEEKLY))
        assertEquals(tomorrow, repo.get(id)!!.recurrenceAnchor)

        // unrelated edit keeps the anchor
        repo.edit(id, TaskFields("renamed", dueDate = tomorrow, recurrence = Recurrence.WEEKLY))
        assertEquals(tomorrow, repo.get(id)!!.recurrenceAnchor)
    }

    // ---- complete / reopen / undo ---------------------------------------------------------

    @Test
    fun complete_marksDone_bumpsRevision_andSyncs() = run {
        val id = repo.create(TaskFields("x"))!!
        sync.changed.clear()
        val r = repo.complete(id)
        assertTrue(r.completed)
        val t = repo.get(id)!!
        assertTrue(t.done)
        assertEquals(time.instant, t.completedAt)
        assertEquals(1, t.reminderRevision)
        assertEquals(setOf(id) to "complete", sync.changed.single())
        assertTrue(repo.observePending().first().isEmpty())
        assertEquals(1, repo.observeCompleted().first().size)
    }

    @Test
    fun complete_isIdempotent_noSecondSuccessorForRecurringTasks() = run {
        val id = repo.create(TaskFields("x", dueDate = today, recurrence = Recurrence.DAILY))!!
        val first = repo.complete(id)
        val second = repo.complete(id)
        assertTrue(first.completed)
        assertFalse(second.completed)
        assertEquals(2, repo.exportAll().size) // original + exactly one successor
    }

    @Test
    fun complete_concurrentCallers_produceExactlyOneSuccessor() = runBlocking {
        val id = repo.create(TaskFields("x", dueDate = today, recurrence = Recurrence.WEEKLY))!!
        val results = (1..8).map { async(Dispatchers.Default) { repo.complete(id) } }.awaitAll()
        assertEquals(1, results.count { it.completed })
        assertEquals(2, repo.exportAll().size)
    }

    @Test
    fun complete_recurring_createsSuccessorWithInheritedFieldsAndAnchor() = run {
        val id = repo.create(
            TaskFields(
                "Pagar alquiler", dueDate = LocalDate.of(2026, 9, 14), dueTime = LocalTime.of(9, 0),
                reminderEnabled = true, priority = Priority.P2, recurrence = Recurrence.WEEKLY, listName = "Casa",
            ),
        )!!
        val r = repo.complete(id)
        val s = repo.get(r.successorId!!)!!
        assertEquals(LocalDate.of(2026, 9, 21), s.dueDate) // strictly after today (09-20), Monday series
        assertEquals(LocalDate.of(2026, 9, 14), s.recurrenceAnchor)
        assertEquals(LocalTime.of(9, 0), s.dueTime)
        assertTrue(s.reminderEnabled)
        assertEquals(Priority.P2, s.priority)
        assertEquals("Casa", s.listName)
        assertFalse(s.done)
        assertEquals(0, s.reminderRevision)
        assertNull(s.reminderFiredAt)
    }

    @Test
    fun complete_withStaleRevision_isANoOp() = run {
        val id = repo.create(TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!
        repo.edit(id, TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(10, 0), reminderEnabled = true)) // rev 1
        val r = repo.complete(id, expectedRevision = 0)
        assertFalse(r.completed)
        assertFalse(repo.get(id)!!.done)
    }

    @Test
    fun complete_missingTask_isANoOp() = run {
        assertFalse(repo.complete(999L).completed)
    }

    @Test
    fun undoComplete_reopens_andRemovesTheGeneratedSuccessor() = run {
        val id = repo.create(TaskFields("x", dueDate = today, recurrence = Recurrence.DAILY))!!
        val r = repo.complete(id)
        repo.undoComplete(r)
        assertFalse(repo.get(id)!!.done)
        assertNull(repo.get(r.successorId!!))
        assertEquals(1, repo.exportAll().size)
    }

    @Test
    fun undoComplete_afterTheSuccessorWasCompleted_isANoOp_soTheSeriesNeverForks_GB04() = run {
        val id = repo.create(TaskFields("x", dueDate = today, recurrence = Recurrence.DAILY))!!
        val r = repo.complete(id)
        val second = repo.complete(r.successorId!!) // user finished the next one too -> S2 is open
        assertFalse("the series has moved on: undo must refuse", repo.undoComplete(r))
        assertTrue(repo.get(id)!!.done)
        assertTrue(repo.get(r.successorId!!)!!.done)
        val open = repo.exportAll().filter { !it.done }
        assertEquals("exactly one open occurrence", listOf(second.successorId), open.map { it.id })
    }

    @Test
    fun undoComplete_afterTheSuccessorWasDeleted_isANoOp_GB04() = run {
        val id = repo.create(TaskFields("x", dueDate = today, recurrence = Recurrence.DAILY))!!
        val r = repo.complete(id)
        repo.delete(r.successorId!!) // ends the series (D-16)
        assertFalse(repo.undoComplete(r))
        assertTrue(repo.get(id)!!.done)
        assertTrue(repo.exportAll().none { !it.done })
    }

    @Test
    fun reopen_ofARecurringOccurrence_isRefused_soTwoOpenOccurrencesCanNeverExist_GB04() = run {
        val id = repo.create(TaskFields("x", dueDate = today, recurrence = Recurrence.WEEKLY))!!
        val r = repo.complete(id)
        assertFalse(repo.reopen(id))
        assertTrue(repo.get(id)!!.done)
        assertEquals(listOf(r.successorId), repo.exportAll().filter { !it.done }.map { it.id })
        // ...even for an occurrence whose successor is itself done (a chain A -> S1 -> S2)
        val s1 = r.successorId!!
        repo.complete(s1)
        assertFalse(repo.reopen(id))
        assertFalse(repo.reopen(s1))
        assertEquals(1, repo.exportAll().count { !it.done })
    }

    @Test
    fun reopen_bringsBackACompletedTask_andFreshlyConsumesAPastReminder() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(8, 0), reminderEnabled = true))!!
        val createdAt = time.instant // 08:00 was already past at creation, so it was consumed at 10:00Z
        assertEquals(createdAt, repo.get(id)!!.reminderFiredAt)
        repo.complete(id)

        time.instant = Instant.parse("2026-09-20T10:30:00Z")
        assertTrue(repo.reopen(id))

        val t = repo.get(id)!!
        assertFalse(t.done)
        assertNull(t.completedAt)
        // GA-08: proves reopen RE-consumed (a new timestamp), it did not just keep the old one
        assertEquals(Instant.parse("2026-09-20T10:30:00Z"), t.reminderFiredAt)
        assertEquals(2, t.reminderRevision) // complete (+1), reopen (+1)
        assertFalse(repo.reopen(id)) // not completed anymore
    }

    // ---- postpone / undo (R2-5) -----------------------------------------------------------

    @Test
    fun postpone_movesToTomorrow_keepsTimeAndReminder_clearsDeliveredAndSnoozedState() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        // seed a DELIVERED and SNOOZED reminder so "resets state" is actually exercised (GA-08)
        assertTrue(store.markDelivered(id, 0, time.instant))
        assertTrue(store.snoozeIfDelivered(id, 0, time.instant.plusSeconds(600)))
        assertNotNull(repo.get(id)!!.reminderFiredAt)
        assertNotNull(repo.get(id)!!.reminderSnoozeUntil)

        val r = repo.postpone(id)!!

        val t = repo.get(id)!!
        assertEquals(tomorrow, t.dueDate)
        assertEquals(LocalTime.of(18, 0), t.dueTime)
        assertTrue(t.reminderEnabled)
        assertNull(t.reminderFiredAt)
        assertNull(t.reminderSnoozeUntil)
        assertEquals(1, t.reminderRevision)
        assertEquals(1, r.revisionAfter)
        assertEquals(setOf(id) to "postpone", sync.changed.last())
    }

    @Test
    fun postpone_undatedTask_getsTomorrow() = run {
        val id = repo.create(TaskFields("x"))!!
        repo.postpone(id)
        assertEquals(tomorrow, repo.get(id)!!.dueDate)
    }

    @Test
    fun postpone_doesNotMoveTheRecurrenceAnchor() = run {
        val id = repo.create(TaskFields("x", dueDate = today, recurrence = Recurrence.MONTHLY))!!
        repo.postpone(id)
        assertEquals(today, repo.get(id)!!.recurrenceAnchor)
    }

    @Test
    fun postpone_doneOrMissing_isNull() = run {
        val id = repo.create(TaskFields("x"))!!
        repo.complete(id)
        assertNull(repo.postpone(id))
        assertNull(repo.postpone(12345L))
    }

    @Test
    fun undoPostpone_restoresPreviousSchedule_includingDeliveredAndSnoozedState() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        val firedAt = time.instant
        val snoozeUntil = time.instant.plusSeconds(600)
        store.markDelivered(id, 0, firedAt)
        store.snoozeIfDelivered(id, 0, snoozeUntil)

        val r = repo.postpone(id)!!
        assertNull(repo.get(id)!!.reminderFiredAt)

        assertTrue(repo.undoPostpone(r))
        val t = repo.get(id)!!
        assertEquals(today, t.dueDate)
        assertEquals(firedAt, t.reminderFiredAt) // GA-08: non-null state really restored
        assertEquals(snoozeUntil, t.reminderSnoozeUntil)
        assertEquals(2, t.reminderRevision) // postpone (+1), undo (+1)
    }

    @Test
    fun undoPostpone_ofAnUntouchedFutureReminder_makesItPendingAgain() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        val r = repo.postpone(id)!!
        assertTrue(repo.undoPostpone(r))
        assertNull(repo.get(id)!!.reminderFiredAt) // 18:00 still in the future -> pending
    }

    @Test
    fun undoPostpone_afterTheOriginalInstantPassed_doesNotNotifyRetroactively() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        val r = repo.postpone(id)!!
        time.instant = Instant.parse("2026-09-20T17:00:00Z") // 19:00 Madrid, after 18:00
        assertTrue(repo.undoPostpone(r))
        assertNotNull(repo.get(id)!!.reminderFiredAt)
    }

    @Test
    fun undoPostpone_afterAnInterveningEdit_isANoOp() = run {
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        val r = repo.postpone(id)!!
        repo.edit(id, TaskFields("x", dueDate = tomorrow.plusDays(3), dueTime = LocalTime.of(7, 0), reminderEnabled = true))
        assertFalse(repo.undoPostpone(r))
        assertEquals(tomorrow.plusDays(3), repo.get(id)!!.dueDate)
    }

    // ---- delete / undo --------------------------------------------------------------------

    @Test
    fun delete_returnsTheTaskForUndo_andUndoRestoresItWithTheSameId() = run {
        val id = repo.create(TaskFields("x", priority = Priority.P2))!!
        val removed = repo.delete(id)!!
        assertNull(repo.get(id))
        repo.undoDelete(removed)
        val t = repo.get(id)!!
        assertEquals("x", t.title)
        assertEquals(removed.reminderRevision + 1, t.reminderRevision)
    }

    @Test
    fun delete_missing_isNull() = run { assertNull(repo.delete(42L)) }

    // ---- import (R2-8) --------------------------------------------------------------------

    @Test
    fun replaceAll_replacesEverything_andResetsTheReminderEngine() = run {
        repo.create(TaskFields("old"))
        val r = repo.replaceAll(listOf(aTask(10, "imported A"), aTask(11, "imported B")))
        assertEquals(ReplaceResult.Replaced(2), r)
        assertEquals(setOf(10L, 11L), repo.exportAll().map { it.id }.toSet())
        assertEquals(listOf("import"), sync.resets)
    }

    @Test
    fun replaceAll_givesEveryImportedRowAFreshRevision_soStaleActionsCannotApply_GA02() = run {
        // old task 10 at revision 3 has a notification card on screen
        val oldId = repo.create(TaskFields("old", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!
        repo.edit(oldId, TaskFields("old", dueDate = tomorrow, dueTime = LocalTime.of(10, 0), reminderEnabled = true))
        repo.edit(oldId, TaskFields("old", dueDate = tomorrow, dueTime = LocalTime.of(11, 0), reminderEnabled = true))
        repo.edit(oldId, TaskFields("old", dueDate = tomorrow, dueTime = LocalTime.of(12, 0), reminderEnabled = true))
        val staleRevision = repo.get(oldId)!!.reminderRevision // 3
        store.markDelivered(oldId, staleRevision, time.instant)

        // an import installs a DIFFERENT task with the same id and the same revision
        val imported = aTask(oldId, "imported", dueDate = tomorrow, dueTime = LocalTime.of(8, 0), reminderEnabled = true)
            .copy(reminderRevision = staleRevision, reminderFiredAt = time.instant)
        assertEquals(ReplaceResult.Replaced(1), repo.replaceAll(listOf(imported)))

        val after = repo.get(oldId)!!
        assertEquals("imported", after.title)
        assertEquals(staleRevision + 1, after.reminderRevision)
        // the stale HECHO / +10 MIN for revision 3 must now be inert
        assertFalse(repo.complete(oldId, expectedRevision = staleRevision).completed)
        assertFalse(store.snoozeIfDelivered(oldId, staleRevision, time.instant.plusSeconds(600)))
    }

    @Test
    fun replaceAll_neverWrapsARevisionBackToTheDisplacedRowsValue_GB03() = run {
        // a stale HECHO for revision 0 exists for id 7; the import brings the LARGEST accepted revision for id 7
        val id = repo.create(TaskFields("old", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!
        assertEquals(0, repo.get(id)!!.reminderRevision)
        val imported = aTask(id, "imported", dueDate = tomorrow, dueTime = LocalTime.of(8, 0), reminderEnabled = true)
            .copy(reminderRevision = TaskRules.MAX_IMPORT_REVISION, reminderFiredAt = time.instant)
        assertEquals(ReplaceResult.Replaced(1), repo.replaceAll(listOf(imported)))
        assertEquals(TaskRules.MAX_IMPORT_REVISION + 1, repo.get(id)!!.reminderRevision)
        assertFalse("the stale revision-0 action stays inert", repo.complete(id, expectedRevision = 0).completed)
        assertFalse(store.snoozeIfDelivered(id, 0, time.instant.plusSeconds(600)))
    }

    @Test
    fun replaceAll_rejectsTheWholeImport_andLeavesCurrentDataUntouched_GA03() = run {
        val keep = repo.create(TaskFields("keep me"))!!
        val bad = listOf(
            listOf(aTask(10, "a"), aTask(10, "duplicate id")),
            listOf(aTask(10, "   ")),
            listOf(aTask(10, " untrimmed ")),
            listOf(aTask(0, "id zero")),
            listOf(aTask(Int.MAX_VALUE + 1L, "id beyond int")),
            listOf(aTask(10, "time no date", dueTime = LocalTime.NOON)),
            listOf(aTask(10, "reminder no time", dueDate = tomorrow, reminderEnabled = true)),
            listOf(aTask(10, "recurrence no date", recurrence = Recurrence.DAILY)),
            listOf(aTask(10, "anchor without recurrence", dueDate = tomorrow).copy(recurrenceAnchor = tomorrow)),
            listOf(aTask(10, "recurrence without anchor", dueDate = tomorrow, recurrence = Recurrence.DAILY).copy(recurrenceAnchor = null)),
            listOf(aTask(10, "bad estimate").copy(estimatedMinutes = 7)),
            listOf(aTask(10, "done without completedAt").copy(done = true, completedAt = null)),
            listOf(aTask(10, "completedAt without done").copy(completedAt = time.instant)),
            listOf(aTask(10, "seconds", dueDate = tomorrow, dueTime = LocalTime.of(9, 30, 30))),
            listOf(aTask(10, "negative revision").copy(reminderRevision = -1)),
            listOf(aTask(10, "exhausted revision").copy(reminderRevision = Int.MAX_VALUE)),
            listOf(aTask(10, "revision beyond bound").copy(reminderRevision = TaskRules.MAX_IMPORT_REVISION + 1)),
        )
        for (import in bad) {
            val r = repo.replaceAll(import)
            assertTrue("expected rejection for ${import.map { it.title }}", r is ReplaceResult.Invalid)
        }
        assertEquals(listOf(keep), repo.exportAll().map { it.id })
        assertTrue(sync.resets.isEmpty())
    }

    @Test
    fun replaceAll_acceptsAnEmptyList_asAnIntentionalWipe() = run {
        repo.create(TaskFields("old"))
        assertEquals(ReplaceResult.Replaced(0), repo.replaceAll(emptyList()))
        assertTrue(repo.exportAll().isEmpty())
    }

    // ---- review Gate A regressions ---------------------------------------------------------

    @Test
    fun undoComplete_replayOfAnObsoleteToken_isANoOp_andNeverDuplicatesTheSeries_GA04() = run {
        val id = repo.create(TaskFields("x", dueDate = today, recurrence = Recurrence.DAILY))!!
        val first = repo.complete(id)
        assertTrue(repo.undoComplete(first)) // A reopened, S1 removed
        val second = repo.complete(id)       // A completed again -> S2
        assertTrue(second.completed)

        assertFalse("replaying the first token must do nothing", repo.undoComplete(first))
        assertTrue(repo.get(id)!!.done)
        val open = repo.exportAll().filter { !it.done }
        assertEquals("exactly one open occurrence", 1, open.size)
        assertEquals(second.successorId, open.single().id)
    }

    @Test
    fun undoComplete_returnsFalseForANoOpCompletion() = run {
        assertFalse(repo.undoComplete(CompleteResult(1, completed = false, successorId = null)))
    }

    @Test
    fun undoDelete_neverOverwritesARowThatExistsAgain_GA05() = run {
        val id = repo.create(TaskFields("original"))!!
        val removed = repo.delete(id)!!
        // an import installs a different task under the same id
        repo.replaceAll(listOf(aTask(id, "imported after delete")))

        assertFalse(repo.undoDelete(removed))
        assertEquals("imported after delete", repo.get(id)!!.title)
    }

    @Test
    fun undoDelete_replayAfterARestoreAndEdit_doesNotRollBack_GA05() = run {
        val id = repo.create(TaskFields("v1"))!!
        val removed = repo.delete(id)!!
        assertTrue(repo.undoDelete(removed))
        repo.edit(id, TaskFields("v2 edited"))
        assertFalse(repo.undoDelete(removed))
        assertEquals("v2 edited", repo.get(id)!!.title)
    }

    @Test
    fun dueTimeIsCanonicalisedToTheMinute_beforeAnyReminderDecision_GA06() = run {
        // clock 12:00:00 Madrid; 12:00:59 truncates to 12:00, which is NOT in the future
        val id = repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(12, 0, 59), reminderEnabled = true))!!
        val t = repo.get(id)!!
        assertEquals(LocalTime.of(12, 0), t.dueTime)
        assertNotNull("consumed with the truncated value, so it cannot be retro-delivered later", t.reminderFiredAt)
    }

    @Test
    fun theSchemaCarriesTheSqlDefaultsTheArchitectureDeclares_GA07() {
        val defaults = mutableMapOf<String, String?>()
        val notNull = mutableMapOf<String, Boolean>()
        db.openHelper.readableDatabase.query("PRAGMA table_info(tasks)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                defaults[name] = c.getString(c.getColumnIndexOrThrow("dflt_value"))
                notNull[name] = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1
            }
        }
        assertEquals("''", defaults["notes"])
        assertEquals("0", defaults["reminderEnabled"])
        assertEquals("0", defaults["reminderRevision"])
        assertEquals("'none'", defaults["recurrence"])
        assertEquals("0", defaults["done"])
        // nullable vs required columns
        listOf("dueDate", "dueTime", "priority", "recurrenceAnchor", "estimatedMinutes", "listName", "reminderFiredAt", "reminderSnoozeUntil", "completedAt")
            .forEach { assertFalse("$it must be nullable", notNull[it]!!) }
        listOf("title", "notes", "reminderEnabled", "reminderRevision", "recurrence", "done", "createdAt", "updatedAt")
            .forEach { assertTrue("$it must be NOT NULL", notNull[it]!!) }
    }

    @Test
    fun estimatedMinutes_onlyAllowsTheEditorChoices_GA10() = run {
        val ok = repo.create(TaskFields("a", estimatedMinutes = 30))!!
        val bad = repo.create(TaskFields("b", estimatedMinutes = 7))!!
        assertEquals(30, repo.get(ok)!!.estimatedMinutes)
        assertNull(repo.get(bad)!!.estimatedMinutes)
    }

    @Test
    fun operationsNeverReadTheClockTwice_soMidnightOrAZoneChangeCannotSplitADecision_GA11() = run {
        // `today()` throws: if any repository operation used it, this test would fail.
        val strict = object : com.fernando.ahora.core.time.TimeProvider {
            override fun now() = Instant.parse("2026-09-20T21:59:59Z") // 23:59:59 Madrid, one second before midnight
            override fun zone() = java.time.ZoneId.of("Europe/Madrid")
            override fun today(): LocalDate = error("today() must not be read separately from now()/zone()")
        }
        val r = TaskRepositoryImpl(db, db.taskDao(), strict, sync)
        val id = r.create(TaskFields("x", dueDate = today, recurrence = Recurrence.DAILY))!!
        val done = r.complete(id)
        // successor is derived from the SAME instant: still 09-20 in Madrid -> next is 09-21
        assertEquals(today.plusDays(1), r.get(done.successorId!!)!!.dueDate)
        // postpone also reads only now/zone: tomorrow is 09-21 in Madrid even though UTC is still 09-20
        val p = r.postpone(id)
        assertNull("the original is done, so it cannot be postponed", p)
    }

    // ---- GA-01: a mutation reaches the real reconciler (see also ReminderIntegrationTest) ---

    @Test
    fun theRepositoryAwaitsTheReminderSyncAfterEveryReminderAffectingMutation() = run {
        val id = repo.create(TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!
        repo.edit(id, TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(10, 0), reminderEnabled = true))
        repo.postpone(id)
        repo.complete(id)
        repo.reopen(id)
        repo.delete(id)
        assertEquals(
            listOf("create", "edit", "postpone", "complete", "reopen", "delete"),
            sync.changed.map { it.second },
        )
    }

    // ---- reminder store: revision guards (R2-1) -------------------------------------------

    @Test
    fun markDelivered_isConditionalOnTheRevisionTheCandidateWasReadAt() = run {
        val id = repo.create(TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!
        val candidate = store.candidates().single() // reconcile reads schedule A (revision 0)

        // ... the user edits it to schedule B while the reconcile is in flight ...
        repo.edit(id, TaskFields("x", dueDate = tomorrow.plusDays(5), dueTime = LocalTime.of(9, 0), reminderEnabled = true))

        assertFalse(store.markDelivered(id, candidate.reminderRevision, time.instant))
        assertNull(repo.get(id)!!.reminderFiredAt) // schedule B is NOT marked consumed
    }

    @Test
    fun snoozeIfDelivered_requiresAFiredReminderOfTheCurrentRevision() = run {
        val id = repo.create(TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!
        val until = time.instant.plusSeconds(600)

        assertFalse(store.snoozeIfDelivered(id, 0, until)) // never fired -> no snooze
        assertTrue(store.markDelivered(id, 0, time.instant))
        assertTrue(store.snoozeIfDelivered(id, 0, until))
        assertEquals(until, repo.get(id)!!.reminderSnoozeUntil)
    }

    @Test
    fun snoozeIfDelivered_isANoOpWhileASnoozeIsAlreadyPending_GB06() = run {
        val id = repo.create(TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!
        val first = time.instant.plusSeconds(600)
        store.markDelivered(id, 0, time.instant)
        assertTrue(store.snoozeIfDelivered(id, 0, first))
        assertFalse("a duplicated +10 MIN must not push it further", store.snoozeIfDelivered(id, 0, first.plusSeconds(900)))
        assertEquals(first, repo.get(id)!!.reminderSnoozeUntil)
        // once the snooze has fired (mark clears it) a new card's +10 MIN works again
        store.markDelivered(id, 0, first)
        assertTrue(store.snoozeIfDelivered(id, 0, first.plusSeconds(600)))
    }

    @Test
    fun staleSnooze_afterTheScheduleMoved_doesNothing() = run {
        val id = repo.create(TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!
        store.markDelivered(id, 0, time.instant) // card for revision 0 is on screen
        repo.edit(id, TaskFields("x", dueDate = tomorrow.plusDays(2), dueTime = LocalTime.of(9, 0), reminderEnabled = true)) // -> revision 1

        assertFalse(store.snoozeIfDelivered(id, 0, time.instant.plusSeconds(600))) // stale +10 MIN
        assertNull(repo.get(id)!!.reminderSnoozeUntil)
    }

    @Test
    fun snoozeIfDelivered_ignoresCompletedAndDisabledReminders() = run {
        val done = repo.create(TaskFields("a", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!
        store.markDelivered(done, 0, time.instant)
        repo.complete(done)
        assertFalse(store.snoozeIfDelivered(done, 1, time.instant.plusSeconds(600)))

        val off = repo.create(TaskFields("b", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!
        store.markDelivered(off, 0, time.instant)
        repo.edit(off, TaskFields("b", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = false))
        assertFalse(store.snoozeIfDelivered(off, 1, time.instant.plusSeconds(600)))
    }
}
