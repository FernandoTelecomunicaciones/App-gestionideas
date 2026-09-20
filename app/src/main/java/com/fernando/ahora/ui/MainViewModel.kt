package com.fernando.ahora.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fernando.ahora.domain.AppPreferences
import com.fernando.ahora.domain.model.ThemeMode
import com.fernando.ahora.ui.common.TaskActions
import com.fernando.ahora.ui.common.UiMessage
import com.fernando.ahora.ui.common.UiMessenger
import com.fernando.ahora.ui.common.UndoToken
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Activity-level state: the theme (null until the first DataStore read, which holds the splash so the wrong theme never
 * flashes — NFR-08) and the one-shot Snackbar messages with their Deshacer tokens.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    preferences: AppPreferences,
    messenger: UiMessenger,
    private val actions: TaskActions,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode?> =
        preferences.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages: Flow<UiMessage> = messenger.messages

    fun undo(token: UndoToken) {
        actions.undo(token)
    }
}
