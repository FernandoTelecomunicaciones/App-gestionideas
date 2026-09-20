package com.fernando.ahora.ui.focus

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.R
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.testing.UiEnv
import com.fernando.ahora.testing.eventually
import com.fernando.ahora.testing.nextMessage
import com.fernando.ahora.ui.common.UndoToken
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Foco (PRODUCT_SPEC 5.5, OD-3 option A). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FocusViewModelTest {
    private lateinit var env: UiEnv
    private val start = Instant.parse("2026-09-20T10:00:00Z")

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

    private fun vm(taskId: Long, handle: SavedStateHandle = SavedStateHandle(mapOf("taskId" to taskId))) =
        FocusViewModel(handle, env.repo, env.prefs, env.actions, env.time)

    private suspend fun FocusViewModel.active(predicate: (FocusUiState.Active) -> Boolean = { true }): FocusUiState.Active =
        withTimeout(6_000) { state.first { it is FocusUiState.Active && predicate(it) } as FocusUiState.Active }

    private suspend fun FocusViewModel.closed() {
        withTimeout(6_000) { state.first { it is FocusUiState.Closed } }
    }

    private suspend fun task(title: String = "Escribir informe") = env.repo.create(TaskFields(title))!!

    @Test
    fun entering_startsACountdown_atTheRememberedPreset() = runBlocking {
        val s = vm(task()).active()
        assertEquals("Escribir informe", s.title)
        assertEquals(25, s.presetMinutes)
        assertEquals(25 * 60L, s.remainingSeconds)
        assertFalse(s.finished)
    }

    @Test
    fun aDifferentRememberedPreset_isUsed() = runBlocking {
        env.prefs.preset.value = 45
        assertEquals(45 * 60L, vm(task()).active().remainingSeconds)
    }

    @Test
    fun theClock_counts_towardsZero() = runBlocking {
        val vm = vm(task())
        vm.active()
        env.time.instant = start.plusSeconds(10 * 60)
        assertEquals(15 * 60L, vm.active { it.remainingSeconds == 15 * 60L }.remainingSeconds)
    }

    @Test
    fun selectingAPreset_restartsTheCountdown_andRemembersIt() = runBlocking {
        val vm = vm(task())
        vm.active()
        env.time.instant = start.plusSeconds(5 * 60)

        vm.selectPreset(15)

        assertEquals(15 * 60L, vm.active { it.presetMinutes == 15 }.remainingSeconds)
        eventually { env.prefs.preset.value == 15 }
    }

    @Test
    fun anUnknownPreset_isIgnored() = runBlocking {
        val vm = vm(task())
        vm.active()
        vm.selectPreset(7)
        assertEquals(25, vm.active().presetMinutes)
    }

    @Test
    fun atZero_theClockRests_andTheHapticIsRequestedExactlyOnce() = runBlocking {
        val vm = vm(task())
        vm.active()

        env.time.instant = start.plusSeconds(26 * 60)
        val done = vm.active { it.finished }
        assertEquals(0, done.remainingSeconds)
        assertTrue(done.alertPending)

        vm.onAlerted()
        assertFalse("no second alert after rotation or another tick", vm.active { !it.alertPending }.alertPending)
        assertTrue(vm.active().finished) // it just rests at 00:00: no auto-complete, nothing else happens
    }

    @Test
    fun rotationOrProcessDeath_resumesTheSameSession_evenIfThePreferenceChangedMeanwhile() = runBlocking {
        val id = task()
        val handle = SavedStateHandle(mapOf("taskId" to id))
        val first = vm(id, handle)
        first.active()

        env.time.instant = start.plusSeconds(5 * 60)
        env.prefs.preset.value = 45 // DataStore never overwrites a restored session (R2-12)
        val restored = vm(id, handle)

        val s = restored.active { it.remainingSeconds == 20 * 60L }
        assertEquals(25, s.presetMinutes)
    }

    @Test
    fun terminar_completesTheTask_andLeaves() = runBlocking {
        val id = task()
        val vm = vm(id)
        vm.active()

        vm.finish()

        vm.closed()
        assertEquals(R.string.snack_completed, env.nextMessage().textRes)
        assertTrue(env.repo.get(id)!!.done)
    }

    @Test
    fun posponer_movesTheTaskToTomorrow_withUndo() = runBlocking {
        val id = task()
        val vm = vm(id)
        vm.active()

        vm.postpone()

        vm.closed()
        val msg = env.nextMessage()
        assertEquals(R.string.snack_postponed, msg.textRes)
        assertEquals(LocalDate.of(2026, 9, 21), env.repo.get(id)!!.dueDate)

        env.actions.undo(msg.undo as UndoToken.Postpone).join()
        assertEquals(null, env.repo.get(id)!!.dueDate)
    }

    @Test
    fun ifTheTaskIsCompletedElsewhere_focusClosesSilently() = runBlocking {
        val id = task()
        val vm = vm(id)
        vm.active()

        env.repo.complete(id) // e.g. HECHO on the notification

        vm.closed()
    }

    @Test
    fun aTaskThatDoesNotExist_closesInsteadOfShowingAnEmptyFocus() = runBlocking {
        vm(424242).closed()
    }

    @Test
    fun aDeletedTask_closes() = runBlocking {
        val id = task()
        val vm = vm(id)
        vm.active()
        env.repo.delete(id)
        vm.closed()
    }
}
