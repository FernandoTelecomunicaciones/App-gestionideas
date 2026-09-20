package com.fernando.ahora.domain

import com.fernando.ahora.domain.model.ThemeMode
import com.fernando.ahora.domain.rules.TaskFilter
import kotlinx.coroutines.flow.Flow

/**
 * The four small settings that outlive the process (ARCHITECTURE §7). No task data ever lives here.
 * Implemented over DataStore in `data.prefs`; every flow falls back to its default when the store is unreadable.
 */
interface AppPreferences {
    val themeMode: Flow<ThemeMode>
    val tasksFilter: Flow<TaskFilter>

    /** Last-used Foco preset in minutes, always one of [FOCUS_PRESETS]. */
    val focusPreset: Flow<Int>
    val keepScreenOnInFocus: Flow<Boolean>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setTasksFilter(filter: TaskFilter)
    suspend fun setFocusPreset(minutes: Int)
    suspend fun setKeepScreenOnInFocus(keepOn: Boolean)

    companion object {
        val FOCUS_PRESETS: List<Int> = listOf(15, 25, 45)
        const val DEFAULT_FOCUS_PRESET: Int = 25
    }
}
