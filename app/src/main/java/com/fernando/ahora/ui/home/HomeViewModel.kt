package com.fernando.ahora.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fernando.ahora.core.time.ClockTicker
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.rules.GreetingBand
import com.fernando.ahora.domain.rules.TodayPlanner
import com.fernando.ahora.ui.common.TaskActions
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface HomeUiState {
    /** Nothing is shown until the first emission, so an empty state never flashes (HOY-08). */
    data object Loading : HomeUiState

    data class Loaded(
        val greeting: GreetingBand,
        val today: LocalDate,
        val ahora: Task?,
        /** At most three (HOY-03). */
        val todayRows: List<Task>,
        val rest: List<Task>,
        val restExpanded: Boolean,
    ) : HomeUiState {
        val isEmpty: Boolean get() = ahora == null && todayRows.isEmpty() && rest.isEmpty()
    }
}

/**
 * Hoy. Composition is [TodayPlanner] (PRODUCT_SPEC §6.2) — this class only feeds it the pending list, the current
 * date (re-read every minute, so midnight and time-zone changes move the plan) and the "También pendiente" flag.
 * Nothing about *which* task is Ahora is decided here or in Compose.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    repository: TaskRepository,
    ticker: ClockTicker,
    private val actions: TaskActions,
) : ViewModel() {

    private val expanded = savedState.getStateFlow(KEY_EXPANDED, false)

    val state: StateFlow<HomeUiState> = combine(repository.observePending(), ticker.minutes(), expanded) { tasks, now, open ->
        val plan = TodayPlanner.plan(tasks, now.toLocalDate())
        HomeUiState.Loaded(
            greeting = GreetingBand.of(now.toLocalTime()),
            today = now.toLocalDate(),
            ahora = plan.ahora,
            todayRows = plan.today,
            rest = plan.rest,
            // Nothing to expand ⇒ never "expanded", so an old flag cannot reappear when the list refills.
            restExpanded = open && plan.rest.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    fun toggleRest() {
        savedState[KEY_EXPANDED] = !(savedState[KEY_EXPANDED] ?: false)
    }

    /** Leaving Hoy collapses "También pendiente" (HOY-04); rotation and process death keep it. */
    fun collapseRest() {
        savedState[KEY_EXPANDED] = false
    }

    fun complete(id: Long) {
        actions.complete(id)
    }

    private companion object {
        const val KEY_EXPANDED = "restExpanded"
    }
}
