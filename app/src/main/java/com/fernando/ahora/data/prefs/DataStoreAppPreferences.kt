package com.fernando.ahora.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.fernando.ahora.domain.AppPreferences
import com.fernando.ahora.domain.model.ThemeMode
import com.fernando.ahora.domain.rules.TaskFilter
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** DataStore-backed [AppPreferences]. Unreadable or unknown values fall back to the documented defaults. */
@Singleton
internal class DataStoreAppPreferences @Inject constructor(
    private val store: DataStore<Preferences>,
) : AppPreferences {

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val themeMode: Flow<ThemeMode> =
        data.map { ThemeMode.fromCode(it[THEME]) }.distinctUntilChanged()

    override val tasksFilter: Flow<TaskFilter> =
        data.map { prefs -> TaskFilter.entries.firstOrNull { it.name == prefs[TASKS_FILTER] } ?: TaskFilter.PENDING }
            .distinctUntilChanged()

    override val focusPreset: Flow<Int> =
        data.map { prefs ->
            prefs[FOCUS_PRESET]?.takeIf { it in AppPreferences.FOCUS_PRESETS } ?: AppPreferences.DEFAULT_FOCUS_PRESET
        }.distinctUntilChanged()

    override val keepScreenOnInFocus: Flow<Boolean> =
        data.map { it[KEEP_SCREEN_ON] ?: true }.distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME] = mode.code }
    }

    override suspend fun setTasksFilter(filter: TaskFilter) {
        store.edit { it[TASKS_FILTER] = filter.name }
    }

    override suspend fun setFocusPreset(minutes: Int) {
        if (minutes !in AppPreferences.FOCUS_PRESETS) return
        store.edit { it[FOCUS_PRESET] = minutes }
    }

    override suspend fun setKeepScreenOnInFocus(keepOn: Boolean) {
        store.edit { it[KEEP_SCREEN_ON] = keepOn }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val TASKS_FILTER = stringPreferencesKey("tasksFilter")
        val FOCUS_PRESET = intPreferencesKey("focusPreset")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keepScreenOnInFocus")
    }
}
