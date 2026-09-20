package com.fernando.ahora.ui.inbox

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.R
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.testing.UiEnv
import com.fernando.ahora.testing.eventually
import com.fernando.ahora.testing.nextMessage
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Bandeja + Quick Capture (PRODUCT_SPEC §5.2) over the real repository and Room. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QuickCaptureTest {
    private lateinit var env: UiEnv
    private val today = LocalDate.of(2026, 9, 20)

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

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) =
        InboxViewModel(handle, env.repo, env.actions, env.time)

    @Test
    fun titleOnly_savesAnInboxItem_andSaysGuardadoEnBandeja() = runBlocking {
        val vm = vm()
        vm.onCapture("Llamar al dentista")

        assertTrue(vm.save())

        val msg = env.nextMessage()
        assertEquals(R.string.snack_saved_inbox, msg.textRes)
        val saved = env.repo.exportAll().single()
        assertEquals("Llamar al dentista", saved.title)
        assertTrue("title alone must produce an inbox item", saved.isInbox)
        assertEquals("", vm.capture) // the field clears so the next thought can go straight in
    }

    @Test
    fun blankTitle_isRejected_andNothingIsWritten() = runBlocking {
        val vm = vm()
        vm.onCapture("   ")

        assertFalse(vm.canSave)
        assertFalse(vm.save())

        assertTrue(env.repo.exportAll().isEmpty())
        assertEquals("   ", vm.capture) // left as typed; nothing was consumed
    }

    @Test
    fun title_isTrimmed() = runBlocking {
        val vm = vm()
        vm.onCapture("  Comprar pan  ")
        vm.save()
        env.nextMessage()
        assertEquals("Comprar pan", env.repo.exportAll().single().title)
    }

    @Test
    fun chips_preSetDateAndPriority_thenResetAfterEverySave() = runBlocking {
        val vm = vm()
        vm.onCapture("Pagar el alquiler")
        vm.setDate(today)
        vm.setPriority(Priority.P2)

        vm.save()

        // No longer an inbox item, so the message is the plain "Guardado" (INB-05).
        assertEquals(R.string.snack_saved, env.nextMessage().textRes)
        val saved = env.repo.exportAll().single()
        assertEquals(today, saved.dueDate)
        assertEquals(Priority.P2, saved.priority)
        assertNull(vm.chipDate)
        assertNull(vm.chipPriority)
    }

    @Test
    fun todayChip_togglesOff_whenTappedAgain() {
        val vm = vm()
        vm.toggleToday()
        assertEquals(env.time.today(), vm.chipDate)
        vm.toggleToday()
        assertNull(vm.chipDate)
    }

    @Test
    fun capturedText_andChips_surviveProcessRecreation() {
        val handle = SavedStateHandle()
        val first = vm(handle)
        first.onCapture("Idea a medio escribir")
        first.setDate(today)
        first.setPriority(Priority.P1)

        val restored = vm(handle) // same SavedStateHandle = process death + restore
        assertEquals("Idea a medio escribir", restored.capture)
        assertEquals(today, restored.chipDate)
        assertEquals(Priority.P1, restored.chipPriority)
    }

    @Test
    fun list_showsOnlyInboxItems_newestFirst() = runBlocking {
        val a = env.repo.create(com.fernando.ahora.domain.model.TaskFields("primera"))!!
        env.time.instant = env.time.instant.plusSeconds(5)
        val b = env.repo.create(com.fernando.ahora.domain.model.TaskFields("segunda"))!!
        env.repo.create(com.fernando.ahora.domain.model.TaskFields("con fecha", dueDate = today))
        env.repo.create(com.fernando.ahora.domain.model.TaskFields("con prioridad", priority = Priority.P3))

        val vm = vm()
        var state: InboxUiState = InboxUiState.Loading
        eventually(message = "inbox loaded") {
            state = vm.state.first { it is InboxUiState.Loaded }
            true
        }
        assertEquals(listOf(b, a), (state as InboxUiState.Loaded).items.map { it.id })
    }
}
