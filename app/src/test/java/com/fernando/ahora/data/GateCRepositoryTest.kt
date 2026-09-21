package com.fernando.ahora.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.data.local.AhoraDatabase
import com.fernando.ahora.domain.ReminderSync
import com.fernando.ahora.domain.model.EditResult
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.ReplaceResult
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.testing.FakeTimeProvider
import com.fernando.ahora.testing.aTask
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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

/** Codex Gate C regression tests for the repository (GC-05 committed-but-not-replanned, GC-06 edited successor). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GateCRepositoryTest {
    private val time = FakeTimeProvider(Instant.parse("2026-09-20T10:00:00Z")) // 12:00 in Madrid
    private val today = LocalDate.of(2026, 9, 20)

    /** The engine is down: every re-plan throws, as when both AlarmManager calls fail. */
    private class BrokenSync : ReminderSync {
        var calls = 0
        override suspend fun onTasksChanged(taskIds: Set<Long>, reason: String) {
            calls++
            throw IllegalStateException("alarm manager gone")
        }

        override suspend fun resetAll(reason: String) {
            calls++
            throw IllegalStateException("alarm manager gone")
        }
    }

    private lateinit var db: AhoraDatabase
    private val sync = BrokenSync()
    private lateinit var repo: TaskRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AhoraDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = TaskRepositoryImpl(db, db.taskDao(), time, sync)
    }

    @After
    fun tearDown() = db.close()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    // ---- GC-05 -----------------------------------------------------------------------------------------------------

    @Test
    fun create_isNotReportedAsFailed_whenOnlyTheReplanFails() = run {
        val id = repo.create(TaskFields("Sigue existiendo"))
        assertNotNull("the write committed, so the caller must get the id", id)
        assertEquals("Sigue existiendo", repo.get(id!!)!!.title)
        assertEquals(1, sync.calls)
    }

    @Test
    fun complete_keepsItsUndoResult_whenOnlyTheReplanFails() = run {
        val id = repo.create(TaskFields("x", dueDate = today, recurrence = Recurrence.DAILY))!!

        val result = repo.complete(id)

        assertTrue(result.completed)
        assertNotNull("the undo token must survive a failed re-plan", result.successorId)
        assertTrue(repo.get(id)!!.done)
    }

    @Test
    fun edit_delete_postpone_andImport_alsoSurviveAFailedReplan() = run {
        val id = repo.create(TaskFields("x", dueDate = today))!!
        assertTrue(repo.edit(id, TaskFields("y", dueDate = today.plusDays(2))) is EditResult.Saved)
        assertNotNull(repo.postpone(id))
        assertNotNull(repo.delete(id))
        assertTrue(repo.replaceAll(listOf(aTask(5, "importada"))) is ReplaceResult.Replaced)
        assertEquals(listOf("importada"), repo.exportAll().map { it.title })
    }

    // ---- GC-06 -----------------------------------------------------------------------------------------------------

    @Test
    fun undoComplete_doesNotDeleteASuccessorTheUserHasEdited() = run {
        val id = repo.create(TaskFields("Regar", dueDate = today, recurrence = Recurrence.DAILY))!!
        val done = repo.complete(id)
        val successor = done.successorId!!

        time.instant = time.instant.plus(Duration.ofSeconds(3))
        val edited = repo.edit(
            successor,
            TaskFields("Regar las macetas", notes = "con abono", dueDate = today.plusDays(1), recurrence = Recurrence.DAILY),
        )
        assertTrue(edited is EditResult.Saved)

        assertFalse("undo must not destroy the user's edit", repo.undoComplete(done))
        assertEquals("Regar las macetas", repo.get(successor)!!.title)
        assertTrue("the original stays completed", repo.get(id)!!.done)
    }

    @Test
    fun undoComplete_stillRemovesAnUntouchedSuccessor() = run {
        val id = repo.create(TaskFields("Regar", dueDate = today, recurrence = Recurrence.DAILY))!!
        val done = repo.complete(id)
        time.instant = time.instant.plus(Duration.ofSeconds(3))

        assertTrue(repo.undoComplete(done))
        assertFalse(repo.get(id)!!.done)
        assertNull(repo.get(done.successorId!!))
    }
}
