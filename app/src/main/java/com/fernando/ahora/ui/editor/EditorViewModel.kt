package com.fernando.ahora.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fernando.ahora.R
import com.fernando.ahora.core.time.TimeProvider
import com.fernando.ahora.di.ApplicationScope
import com.fernando.ahora.domain.ReminderPermissionSource
import com.fernando.ahora.domain.ReminderPermissionState
import com.fernando.ahora.domain.ReminderSync
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.domain.model.EditResult
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.ui.common.TaskActions
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The create/edit sheet's state, scoped to the Activity so any tab (and the FAB) opens the same sheet
 * (ARCHITECTURE §4, §8). The draft and the two permission fields are Compose snapshot state, not a Flow: typed
 * text must update synchronously or the caret jumps (ARCHITECTURE §9.2). The draft is mirrored into the
 * `SavedStateHandle` on every change so the sheet survives rotation and process death (EDT-11).
 *
 * It never schedules a reminder: saving goes through [TaskRepository], which re-plans the alarm cursor itself.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repository: TaskRepository,
    private val actions: TaskActions,
    private val time: TimeProvider,
    private val permissionSource: ReminderPermissionSource,
    private val reminderSync: ReminderSync,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    /** Non-null ⇔ the sheet is open. */
    var draft: EditorDraft? by mutableStateOf(restore())
        private set

    var permissions: ReminderPermissionState by mutableStateOf(permissionSource.snapshot())
        private set

    /** The system will not show the runtime dialog again once it was answered; then "Abrir ajustes" is the way. */
    var notificationsAsked: Boolean by mutableStateOf(savedState[KEY_ASKED] ?: false)
        private set

    val isOpen: Boolean get() = draft != null

    /** Read at composition time by the sheet (date chips, "esa hora ya pasó"). */
    fun today(): LocalDate = time.today()

    fun isReminderInPast(d: EditorDraft): Boolean = d.reminderIsInPast(time.now(), time.zone())

    fun openNew() {
        refreshPermissions()
        publish(EditorDraft())
    }

    fun openEdit(taskId: Long) {
        refreshPermissions()
        viewModelScope.launch {
            val task = repository.get(taskId)
            if (task == null || task.done) {
                actions.say(R.string.snack_no_longer_pending)
            } else {
                publish(EditorDraft.of(task))
            }
        }
    }

    /** Cancelar / back / scrim / swipe down: discard (PRODUCT_SPEC §3.3). */
    fun dismiss() = publish(null)

    fun onTitle(value: String) = update { it.withTitle(value) }
    fun onNotes(value: String) = update { it.withNotes(value) }
    fun onList(value: String) = update { it.withList(value) }
    fun onPriority(value: Priority?) = update { it.withPriority(value) }
    fun onRecurrence(value: Recurrence) = update { it.withRecurrence(value) }
    fun onEstimate(minutes: Int?) = update { it.withEstimate(minutes) }
    fun toggleMore() = update { it.toggleMore() }
    fun onReminder(on: Boolean) = update { it.withReminder(on) }
    fun onDate(date: LocalDate?) = update { it.withDate(date) }

    fun onTime(value: LocalTime?) {
        val now = time.nowLocal()
        update { it.withTime(value, now.toLocalDate(), now.toLocalTime()) }
    }

    /**
     * Guardar. The sheet closes at once; the write runs in the application scope so it cannot be lost with the
     * view. The repository is the only judge of what a save means (never resurrects a completed task, R2-4).
     */
    fun save() {
        val d = draft?.takeIf { it.canSave } ?: return
        publish(null)
        appScope.launch {
            try {
                val id = d.taskId
                if (id == null) {
                    if (repository.create(d.toFields()) != null) actions.say(R.string.snack_saved)
                } else {
                    when (repository.edit(id, d.toFields())) {
                        is EditResult.Saved -> actions.say(R.string.snack_saved)
                        EditResult.NotFound, EditResult.AlreadyCompleted -> actions.say(R.string.snack_no_longer_pending)
                        EditResult.InvalidTitle -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                actions.say(R.string.snack_save_failed)
            }
        }
    }

    /** Eliminar (edit mode only): immediate, with "Eliminada · Deshacer" (EDT-09). */
    fun delete() {
        val id = draft?.taskId ?: return
        publish(null)
        actions.delete(id)
    }

    // --- reminder permissions (asked in context, never up-front — PRODUCT_SPEC §8) -----------------------------

    fun refreshPermissions() {
        permissions = permissionSource.snapshot()
    }

    fun onNotificationPermissionAnswered() {
        savedState[KEY_ASKED] = true
        notificationsAsked = true
        refreshPermissions()
        // A grant changes what the reconciler may deliver; let it re-plan (D-09 "notification-permission result").
        appScope.launch { reminderSync.onTasksChanged(emptySet(), "notification-permission") }
    }

    private fun update(block: (EditorDraft) -> EditorDraft) {
        draft?.let { publish(block(it)) }
    }

    private fun publish(value: EditorDraft?) {
        draft = value
        savedState[KEY_DRAFT] = value?.let { Json.encodeToString(it) }
    }

    private fun restore(): EditorDraft? = savedState.get<String>(KEY_DRAFT)?.let {
        runCatching { Json.decodeFromString<EditorDraft>(it) }.getOrNull()
    }

    private companion object {
        const val KEY_DRAFT = "draft"
        const val KEY_ASKED = "notificationsAsked"
    }
}
