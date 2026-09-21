package com.fernando.ahora.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.domain.rules.TaskRules
import com.fernando.ahora.testing.UiEnv
import com.fernando.ahora.testing.eventually
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Codex Gate C regression tests for the sheet: GC-07 (a slow read must not reopen or overwrite) and GC-09 (limits). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GateCEditorTest {
    private lateinit var env: UiEnv

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        env = UiEnv()
    }

    @After
    fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    /** The read has finished ([reached]) but its result is held back until [open]: a slow read that lands late. */
    private class Gate {
        val reached = CompletableDeferred<Unit>()
        val open = CompletableDeferred<Unit>()
    }

    private class GatedRepository(private val inner: TaskRepository, val gates: Map<Long, Gate>) : TaskRepository by inner {
        override suspend fun get(id: Long): Task? {
            val task = inner.get(id)
            gates[id]?.let {
                it.reached.complete(Unit)
                it.open.await()
            }
            return task
        }
    }

    private fun vm(gates: Map<Long, Gate>) = EditorViewModel(
        SavedStateHandle(), GatedRepository(env.repo, gates), env.actions, env.time, env.permissions, env.sync, env.appScope,
    )

    @Test
    fun aSlowRead_doesNotReopenTheSheetAfterADismiss() = runBlocking {
        val a = env.repo.create(TaskFields("A"))!!
        val gate = Gate()
        val vm = vm(mapOf(a to gate))

        vm.openEdit(a)
        gate.reached.await()
        vm.dismiss() // e.g. a notification link closed the sheet and navigated to Foco
        gate.open.complete(Unit) // the late result arrives now

        assertFalse("the late read must not put the sheet back over the new destination", vm.isOpen)
    }

    @Test
    fun aSlowReadOfA_doesNotOverwriteALaterOpenOfB() = runBlocking {
        val a = env.repo.create(TaskFields("A"))!!
        val b = env.repo.create(TaskFields("B"))!!
        val gate = Gate()
        val vm = vm(mapOf(a to gate))

        vm.openEdit(a)
        gate.reached.await()
        vm.openEdit(b)
        eventually(message = "B to open") { vm.draft?.taskId == b }
        gate.open.complete(Unit)

        assertEquals("B stays", b, vm.draft!!.taskId)
    }

    @Test
    fun aSlowReadOfA_doesNotOverwriteANewBlankDraft() = runBlocking {
        val a = env.repo.create(TaskFields("A"))!!
        val gate = Gate()
        val vm = vm(mapOf(a to gate))

        vm.openEdit(a)
        gate.reached.await()
        vm.openNew()
        vm.onTitle("typing")
        gate.open.complete(Unit)

        assertTrue(vm.draft!!.isNew)
        assertEquals("typing", vm.draft!!.title)
    }

    @Test
    fun aRead_thatIsNotSuperseded_stillOpensTheSheet() = runBlocking {
        val a = env.repo.create(TaskFields("A"))!!
        val gate = Gate()
        val vm = vm(mapOf(a to gate))

        vm.openEdit(a)
        gate.reached.await()
        gate.open.complete(Unit)

        eventually(message = "A to open") { vm.draft != null }
        assertNotNull(vm.draft)
        assertEquals(a, vm.draft!!.taskId)
    }

    // ---- GC-09 -----------------------------------------------------------------------------------------------------

    @Test
    fun typing_stopsAtTheBackupLimits() {
        val d = EditorDraft()
            .withTitle("t".repeat(TaskRules.MAX_TITLE + 10))
            .withNotes("n".repeat(TaskRules.MAX_NOTES + 10))
            .withList("l".repeat(TaskRules.MAX_LIST + 10))
        assertEquals(TaskRules.MAX_TITLE, d.title.length)
        assertEquals(TaskRules.MAX_NOTES, d.notes.length)
        assertEquals(TaskRules.MAX_LIST, d.list.length)
    }
}
