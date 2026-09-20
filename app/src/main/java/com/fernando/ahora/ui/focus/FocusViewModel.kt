package com.fernando.ahora.ui.focus

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.fernando.ahora.core.time.TimeProvider
import com.fernando.ahora.domain.AppPreferences
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.ui.common.TaskActions
import com.fernando.ahora.ui.navigation.Focus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface FocusUiState {
    data object Loading : FocusUiState

    /** The task is gone, done elsewhere (e.g. HECHO), or the user finished/postponed: leave silently to Hoy (FOC-07). */
    data object Closed : FocusUiState

    data class Active(
        val title: String,
        val presetMinutes: Int,
        val remainingSeconds: Long,
        val keepScreenOn: Boolean,
        /** True exactly once when the countdown reaches 00:00; the screen answers with one soft haptic (FOC-06). */
        val alertPending: Boolean,
    ) : FocusUiState {
        val finished: Boolean get() = remainingSeconds <= 0
    }
}

/**
 * Foco (PRODUCT_SPEC §5.5, OD-3 option A). Entering starts a countdown at the last-used preset; tapping a preset
 * restarts at that length; at 00:00 the clock rests — no notification, no auto-complete, no statistics.
 *
 * The session (preset + END timestamp in wall-clock ms + "already alerted") lives in the `SavedStateHandle`, so
 * rotation and process death resume the same countdown (remaining = end − now). DataStore only remembers the
 * last-used default and never overwrites a restored session (ARCHITECTURE R2-12).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FocusViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    repository: TaskRepository,
    private val preferences: AppPreferences,
    private val actions: TaskActions,
    private val time: TimeProvider,
) : ViewModel() {

    private val taskId: Long = savedState.toRoute<Focus>().taskId

    /** `[presetMinutes, endEpochMillis]` as ONE value, so a preset change can never be observed with the old end time. */
    private val sessionRecord = savedState.getStateFlow<LongArray?>(KEY_SESSION, null)
    private val alerted = savedState.getStateFlow(KEY_ALERTED, false)
    private val leaving = MutableStateFlow(false)

    /** Ticks on the second boundary while collected. */
    private val ticks = flow {
        while (true) {
            val now = time.now().toEpochMilli()
            emit(now)
            delay(1_000L - (now % 1_000L) + 5L)
        }
    }

    private data class Session(val preset: Int, val remaining: Long)

    /** A new session restarts the ticker, so the first frame after a preset tap is computed from the current time. */
    private val session = sessionRecord.flatMapLatest { record ->
        if (record == null) {
            flowOf(null)
        } else {
            ticks.map { now -> Session(record[0].toInt(), ((record[1] - now + 999L) / 1_000L).coerceAtLeast(0L)) }
        }
    }

    val state: StateFlow<FocusUiState> = combine(
        repository.observe(taskId),
        session,
        alerted,
        combine(leaving, preferences.keepScreenOnInFocus) { l, k -> l to k },
    ) { task, session, alerted, (leaving, keepOn) ->
        when {
            leaving || (task != null && task.done) -> FocusUiState.Closed
            task == null -> FocusUiState.Closed
            session == null -> FocusUiState.Loading
            else -> FocusUiState.Active(
                title = task.title,
                presetMinutes = session.preset,
                remainingSeconds = session.remaining,
                keepScreenOn = keepOn,
                alertPending = session.remaining <= 0 && !alerted,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusUiState.Loading)

    init {
        // A restored session wins; only a fresh entry reads the remembered preset.
        if (savedState.get<LongArray>(KEY_SESSION) == null) {
            viewModelScope.launch {
                val remembered = preferences.focusPreset.first()
                if (savedState.get<LongArray>(KEY_SESSION) == null) start(remembered)
            }
        }
    }

    /** Tapping a preset restarts the countdown at that length and remembers it as the default. */
    fun selectPreset(minutes: Int) {
        if (minutes !in AppPreferences.FOCUS_PRESETS) return
        start(minutes)
        viewModelScope.launch { preferences.setFocusPreset(minutes) }
    }

    fun onAlerted() {
        savedState[KEY_ALERTED] = true
    }

    /** Terminar: complete exactly as PRODUCT_SPEC §6.3, then Hoy. The write runs in the application scope. */
    fun finish() {
        leaving.value = true
        actions.complete(taskId)
    }

    /** Posponer: to tomorrow (§6.4), with Deshacer in the Snackbar, then Hoy. */
    fun postpone() {
        leaving.value = true
        actions.postpone(taskId)
    }

    private fun start(minutes: Int) {
        // Session first, then the flag: the reverse order would expose "finished + not yet alerted" for an instant
        // and fire a spurious haptic on restart.
        savedState[KEY_SESSION] = longArrayOf(minutes.toLong(), time.now().toEpochMilli() + minutes * 60_000L)
        savedState[KEY_ALERTED] = false
    }

    private companion object {
        const val KEY_SESSION = "session"
        const val KEY_ALERTED = "alerted"
    }
}
