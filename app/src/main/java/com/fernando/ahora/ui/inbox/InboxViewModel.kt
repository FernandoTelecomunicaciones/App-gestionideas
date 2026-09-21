package com.fernando.ahora.ui.inbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fernando.ahora.core.time.TimeProvider
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.domain.rules.TaskRules
import com.fernando.ahora.ui.common.TaskActions
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface InboxUiState {
    data object Loading : InboxUiState
    data class Loaded(val items: List<Task>) : InboxUiState
}

/**
 * Bandeja and Quick Capture (PRODUCT_SPEC §5.2). The typed text and the three optional chips are snapshot state
 * mirrored into the `SavedStateHandle` synchronously (INB-08, ARCHITECTURE §9.2). Saving a task needs a title and
 * nothing else; the chips can never block it.
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    repository: TaskRepository,
    private val actions: TaskActions,
    private val time: TimeProvider,
) : ViewModel() {

    var capture: String by mutableStateOf(savedState[KEY_TEXT] ?: "")
        private set

    /** Optional pre-set for the item being captured; reset after every save (INB-05). */
    var chipDate: LocalDate? by mutableStateOf((savedState.get<Long>(KEY_DATE))?.let(LocalDate::ofEpochDay))
        private set

    var chipPriority: Priority? by mutableStateOf(Priority.fromValue(savedState[KEY_PRIORITY]))
        private set

    val canSave: Boolean get() = capture.isNotBlank()

    val state: StateFlow<InboxUiState> = repository.observePending()
        .map { tasks ->
            InboxUiState.Loaded(
                tasks.filter { it.isInbox }.sortedWith(compareByDescending<Task> { it.createdAt }.thenByDescending { it.id }),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState.Loading)

    fun today(): LocalDate = time.today()

    fun onCapture(value: String) {
        val text = value.take(TaskRules.MAX_TITLE) // same limit the backup accepts (GC-09)
        capture = text
        savedState[KEY_TEXT] = text
    }

    fun toggleToday() = setDate(if (chipDate == time.today()) null else time.today())

    fun setDate(date: LocalDate?) {
        chipDate = date
        savedState[KEY_DATE] = date?.toEpochDay()
    }

    fun setPriority(priority: Priority?) {
        chipPriority = priority
        savedState[KEY_PRIORITY] = priority?.value
    }

    /**
     * Guardar / IME Done. Returns false for a blank title (nothing happens). The field and chips clear at once so the
     * next thought can go straight in; the write runs in the application scope (never lost with the view).
     */
    fun save(): Boolean {
        val title = capture.trim()
        if (title.isEmpty()) return false
        val fields = TaskFields(title = title, dueDate = chipDate, priority = chipPriority)
        onCapture("")
        setDate(null)
        setPriority(null)
        actions.create(fields)
        return true
    }

    private companion object {
        const val KEY_TEXT = "capture"
        const val KEY_DATE = "chipDate"
        const val KEY_PRIORITY = "chipPriority"
    }
}
