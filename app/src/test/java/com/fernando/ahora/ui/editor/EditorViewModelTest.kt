package com.fernando.ahora.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.R
import com.fernando.ahora.domain.ReminderPermissionState
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.testing.UiEnv
import com.fernando.ahora.testing.eventually
import com.fernando.ahora.testing.nextMessage
import com.fernando.ahora.ui.common.UndoToken
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

/** Create/edit sheet (PRODUCT_SPEC 5.4) over the real repository: it never schedules anything itself. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EditorViewModelTest {
    private lateinit var env: UiEnv
    private val today = LocalDate.of(2026, 9, 20) // FakeTimeProvider: 12:00 in Madrid

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

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = EditorViewModel(
        handle, env.repo, env.actions, env.time, env.permissions, env.sync, env.appScope,
    )

    @Test
    fun newTask_withOnlyATitle_saves_andClosesTheSheet() = runBlocking {
        val vm = vm()
        vm.openNew()
        assertTrue(vm.isOpen)
        vm.onTitle("Solo un titulo")

        vm.save()

        assertFalse(vm.isOpen)
        assertEquals(R.string.snack_saved, env.nextMessage().textRes)
        val task = env.repo.exportAll().single()
        assertEquals("Solo un titulo", task.title)
        assertTrue(task.isInbox)
    }

    @Test
    fun save_isIgnored_whileTheTitleIsBlank() = runBlocking {
        val vm = vm()
        vm.openNew()
        vm.onTitle("  ")
        vm.save()
        assertTrue("the sheet stays open", vm.isOpen)
        assertTrue(env.repo.exportAll().isEmpty())
    }

    @Test
    fun edit_changesTitleAndPriority() = runBlocking {
        val id = env.repo.create(TaskFields("Comprar pan"))!!
        val vm = vm()
        vm.openEdit(id)
        eventually(message = "draft loaded") { vm.draft != null }
        assertEquals("Comprar pan", vm.draft!!.title)

        vm.onTitle("Comprar pan integral")
        vm.onPriority(Priority.P1)
        vm.save()
        env.nextMessage()

        val saved = env.repo.get(id)!!
        assertEquals("Comprar pan integral", saved.title)
        assertEquals(Priority.P1, saved.priority)
    }

    @Test
    fun editing_canClearPriority_andDate() = runBlocking {
        val id = env.repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), priority = Priority.P2))!!
        val vm = vm()
        vm.openEdit(id)
        eventually { vm.draft != null }

        vm.onPriority(null)
        vm.onDate(null)
        vm.save()
        env.nextMessage()

        val saved = env.repo.get(id)!!
        assertNull(saved.priority)
        assertNull(saved.dueDate)
        assertNull("clearing the date clears the time too", saved.dueTime)
        assertTrue(saved.isInbox)
    }

    @Test
    fun addingADate_thenARepeatRule_isStoredWithItsAnchor() = runBlocking {
        val vm = vm()
        vm.openNew()
        vm.onTitle("Regar las plantas")
        vm.onDate(today)
        vm.onRecurrence(Recurrence.WEEKLY)
        vm.save()
        env.nextMessage()

        val task = env.repo.exportAll().single()
        assertEquals(Recurrence.WEEKLY, task.recurrence)
        assertEquals(today, task.recurrenceAnchor)
    }

    @Test
    fun reminder_isSavedThroughTheRepository_whichReplansTheEngine() = runBlocking {
        val vm = vm()
        vm.openNew()
        vm.onTitle("Llamar")
        vm.onDate(today)
        vm.onTime(LocalTime.of(18, 0))
        vm.onReminder(true)
        vm.save()
        env.nextMessage()

        val task = env.repo.exportAll().single()
        assertTrue(task.reminderEnabled)
        // The ViewModel schedules nothing: the only re-plan is the one the repository requested after its commit.
        assertEquals(listOf(setOf(task.id) to "create"), env.sync.changed)
    }

    @Test
    fun editingTheReminder_replansAgain_butTitleOnlyEditsDoNot() = runBlocking {
        val id = env.repo.create(TaskFields("x", dueDate = today, dueTime = LocalTime.of(18, 0), reminderEnabled = true))!!
        env.sync.changed.clear()
        val vm = vm()

        vm.openEdit(id); eventually { vm.draft != null }
        vm.onTitle("y"); vm.save(); env.nextMessage()
        assertTrue("a title-only edit does not touch the schedule", env.sync.changed.isEmpty())

        vm.openEdit(id); eventually { vm.draft != null }
        vm.onReminder(false); vm.save(); env.nextMessage()
        assertEquals(listOf(setOf(id) to "edit"), env.sync.changed)
    }

    @Test
    fun delete_removesTheTask_andOffersUndo() = runBlocking {
        val id = env.repo.create(TaskFields("Borrar esto"))!!
        val vm = vm()
        vm.openEdit(id); eventually { vm.draft != null }

        vm.delete()

        val msg = env.nextMessage()
        assertEquals(R.string.snack_deleted, msg.textRes)
        assertNull(env.repo.get(id))
        val token = msg.undo as UndoToken.Delete
        env.actions.undo(token).join()
        assertEquals("Borrar esto", env.repo.get(id)?.title)
    }

    @Test
    fun aTaskCompletedElsewhere_isNeverResurrected_byTheSheet() = runBlocking {
        val id = env.repo.create(TaskFields("Hecha desde la notificacion"))!!
        val vm = vm()
        vm.openEdit(id); eventually { vm.draft != null }

        env.repo.complete(id) // e.g. HECHO on the notification while the sheet is open
        vm.onTitle("intento de edicion")
        vm.save()

        assertEquals(R.string.snack_no_longer_pending, env.nextMessage().textRes)
        val task = env.repo.get(id)!!
        assertTrue(task.done)
        assertEquals("Hecha desde la notificacion", task.title)
    }

    @Test
    fun openingATaskThatIsGone_showsTheNeutralMessage_andNoSheet() = runBlocking {
        val vm = vm()
        vm.openEdit(999)
        assertEquals(R.string.snack_no_longer_pending, env.nextMessage().textRes)
        assertFalse(vm.isOpen)
    }

    @Test
    fun theDraft_survivesRotationAndProcessDeath() {
        val handle = SavedStateHandle()
        val first = vm(handle)
        first.openNew()
        first.onTitle("Escribiendo")
        first.onDate(today)
        first.onPriority(Priority.P3)

        val restored = vm(handle)
        assertNotNull(restored.draft)
        assertEquals("Escribiendo", restored.draft!!.title)
        assertEquals(today, restored.draft!!.dueDate)
        assertEquals(Priority.P3, restored.draft!!.priorityValue)
    }

    @Test
    fun dismiss_discardsTheDraft_everywhere() {
        val handle = SavedStateHandle()
        val vm = vm(handle)
        vm.openNew(); vm.onTitle("descartar"); vm.dismiss()
        assertFalse(vm.isOpen)
        assertNull(vm(handle).draft)
    }

    @Test
    fun notificationPermissionAnswer_marksItAsAsked_andAsksTheEngineToReplan() = runBlocking {
        env.permissions.state = ReminderPermissionState(
            notificationsGranted = false, canAskNotifications = true,
            exactAlarmsAllowed = true, exactAlarmsNeedSpecialAccess = true,
        )
        val vm = vm()
        assertTrue(vm.permissions.canAskNotifications)
        assertFalse(vm.notificationsAsked)

        env.permissions.state = env.permissions.state.copy(notificationsGranted = true, canAskNotifications = false)
        vm.onNotificationPermissionAnswered()

        assertTrue(vm.notificationsAsked)
        assertTrue(vm.permissions.notificationsGranted)
        eventually(message = "reconcile requested") { env.sync.changed.any { it.second == "notification-permission" } }
    }
}
