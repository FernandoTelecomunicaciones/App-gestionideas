package com.fernando.ahora.ui.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fernando.ahora.core.time.ClockTicker
import com.fernando.ahora.domain.AppPreferences
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.rules.TaskFilter
import com.fernando.ahora.domain.rules.TaskFilters
import com.fernando.ahora.domain.rules.TextNormalizer
import com.fernando.ahora.ui.common.TaskActions
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TasksUiState {
    data object Loading : TasksUiState

    data class Loaded(
        val filter: TaskFilter,
        val showCompleted: Boolean,
        val query: String,
        val items: List<Task>,
        val today: LocalDate,
    )  : TasksUiState
}

/**
 * Tareas (PRODUCT_SPEC §5.3). Filtering, sorting and accent-insensitive search are [TaskFilters] — the ViewModel only
 * feeds them. The query is snapshot state (synchronous typing) mirrored to a debounced flow (≤ 150 ms, TSK-04); the
 * selected filter is persisted in DataStore (TSK-03).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class TasksViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repository: TaskRepository,
    private val preferences: AppPreferences,
    ticker: ClockTicker,
    private val actions: TaskActions,
) : ViewModel() {

    var query: String by mutableStateOf(savedState[KEY_QUERY] ?: "")
        private set

    private val queryFlow = MutableStateFlow(query)
    private val showCompleted = savedState.getStateFlow(KEY_COMPLETED, false)

    private data class Source(val tasks: List<Task>, val completed: Boolean)

    private val source: Flow<Source> = showCompleted.flatMapLatest { completed ->
        (if (completed) repository.observeCompleted() else repository.observePending()).map { Source(it, completed) }
    }

    val state: StateFlow<TasksUiState> = combine(
        source,
        preferences.tasksFilter,
        queryFlow.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS },
        ticker.minutes(),
    ) { src, filter, q, now ->
        val items = if (src.completed) {
            val normalized = TextNormalizer.normalize(q.trim())
            if (normalized.isEmpty()) src.tasks else src.tasks.filter { TaskFilters.matches(it, normalized) }
        } else {
            TaskFilters.apply(src.tasks, filter, q)
        }
        TasksUiState.Loaded(filter, src.completed, q, items, now.toLocalDate())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState.Loading)

    fun onQuery(value: String) {
        query = value
        savedState[KEY_QUERY] = value
        queryFlow.value = value
    }

    fun onFilter(filter: TaskFilter) {
        viewModelScope.launch { preferences.setTasksFilter(filter) }
    }

    /** "Completadas" / "Pendientes" text link (TSK-05). */
    fun setShowCompleted(completed: Boolean) {
        savedState[KEY_COMPLETED] = completed
    }

    fun complete(id: Long) {
        actions.complete(id)
    }

    /** Refused by the repository for recurring occurrences (D-31); the UI does not offer it there either. */
    fun reopen(id: Long) {
        actions.reopen(id)
    }

    private companion object {
        const val KEY_QUERY = "query"
        const val KEY_COMPLETED = "showCompleted"
        const val SEARCH_DEBOUNCE_MS = 150L
    }
}
