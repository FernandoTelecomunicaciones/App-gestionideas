package com.fernando.ahora.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fernando.ahora.R
import com.fernando.ahora.domain.AppPreferences
import com.fernando.ahora.ui.components.AhoraButton
import com.fernando.ahora.ui.components.AhoraChip
import com.fernando.ahora.ui.components.ButtonKind
import com.fernando.ahora.ui.theme.Ahora
import com.fernando.ahora.ui.theme.AhoraType

/** Route layer. [onExit] = Salir / back (no changes); [onClose] = the session ended (Terminar, Posponer, task gone). */
@Composable
fun FocusRoute(
    onExit: () -> Unit,
    onClose: () -> Unit,
    viewModel: FocusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state is FocusUiState.Closed) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val active = state as? FocusUiState.Active ?: return

    val view = LocalView.current
    DisposableEffect(active.keepScreenOn) {
        view.keepScreenOn = active.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(active.alertPending) {
        if (active.alertPending) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            viewModel.onAlerted()
        }
    }

    FocusScreen(
        state = active,
        onPreset = viewModel::selectPreset,
        onFinish = viewModel::finish,
        onPostpone = viewModel::postpone,
        onExit = onExit,
    )
}

/**
 * Deliberately shows very little (FOC-01): kicker, the one task, "Sólo esto ahora.", the clock, three presets and
 * three actions. No bar, no FAB, no list, no statistics.
 */
@Composable
fun FocusScreen(
    state: FocusUiState.Active,
    onPreset: (Int) -> Unit,
    onFinish: () -> Unit,
    onPostpone: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Centred when it fits, scrollable when it does not (landscape, 200 % font scale — A11Y-05, NFR-07).
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
        ) {
            FocusContent(state, onPreset, onFinish, onPostpone, onExit)
        }
    }
}

@Composable
private fun FocusContent(
    state: FocusUiState.Active,
    onPreset: (Int) -> Unit,
    onFinish: () -> Unit,
    onPostpone: () -> Unit,
    onExit: () -> Unit,
) {
    val c = Ahora.colors
    run {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.focus_kicker).uppercase(), style = AhoraType.focusKicker, color = c.accentText)
            Text(
                state.title,
                style = AhoraType.headline,
                color = c.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
            )
            Text(stringResource(R.string.focus_only_this), style = AhoraType.body, color = c.textSecondary)
        }

        val minutes = (state.remainingSeconds / 60).toInt()
        val seconds = (state.remainingSeconds % 60).toInt()
        Text(
            text = "%02d:%02d".format(minutes, seconds),
            style = AhoraType.focusClock,
            color = c.text,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppPreferences.FOCUS_PRESETS.forEach { m ->
                AhoraChip(
                    label = stringResource(R.string.focus_preset, m),
                    selected = state.presetMinutes == m,
                    onClick = { onPreset(m) },
                    small = true,
                )
            }
        }

        Column(
            Modifier.widthIn(max = 220.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AhoraButton(stringResource(R.string.focus_finish), onFinish, Modifier.fillMaxWidth(), alignStart = true)
            AhoraButton(stringResource(R.string.focus_postpone), onPostpone, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
            AhoraButton(stringResource(R.string.focus_exit), onExit, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
        }
    }
}
