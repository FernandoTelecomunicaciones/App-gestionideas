package com.fernando.ahora.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fernando.ahora.BuildConfig
import com.fernando.ahora.R
import com.fernando.ahora.domain.model.ThemeMode
import com.fernando.ahora.reminders.ReminderPermissions
import com.fernando.ahora.ui.components.AhoraChip
import com.fernando.ahora.ui.components.AhoraSwitch
import com.fernando.ahora.ui.components.AhoraTextAction
import com.fernando.ahora.ui.components.BackTopBar
import com.fernando.ahora.ui.components.SectionKicker
import com.fernando.ahora.ui.components.focusRing
import com.fernando.ahora.ui.editor.startSafely
import com.fernando.ahora.ui.theme.Ahora
import com.fernando.ahora.ui.theme.AhoraType

@Composable
fun SettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The user may come back from a system settings screen with a different permission state.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onNotificationPermissionAnswered()
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.readImport(uri)
    }

    SettingsScreen(
        state = state,
        onBack = onBack,
        onAskNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onOpenNotificationSettings = { context.startSafely(ReminderPermissions.notificationSettingsIntent(context.packageName)) },
        onOpenExactAlarmSettings = { ReminderPermissions.exactAlarmSettingsIntent(context.packageName)?.let(context::startSafely) },
        onOpenChannelSettings = { context.startSafely(ReminderPermissions.channelSettingsIntent(context.packageName)) },
        onTheme = viewModel::setTheme,
        onKeepScreenOn = viewModel::setKeepScreenOn,
        onExport = { exportLauncher.launch(viewModel.suggestedExportName()) },
        onImport = { importLauncher.launch(arrayOf("*/*")) },
        onConfirmImport = viewModel::confirmImport,
        onCancelImport = viewModel::cancelImport,
    )
}

/**
 * Ajustes (PRODUCT_SPEC §5.7): five small sections, nothing mandatory, every value has a default. Settings is a pushed
 * screen — no bottom bar, no FAB — reached from the tab top bars.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onAskNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenChannelSettings: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Ahora.colors
    Column(modifier.fillMaxSize()) {
        BackTopBar(title = stringResource(R.string.settings_title), onBack = onBack, modifier = Modifier.padding(horizontal = 4.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {

            // --- Recordatorios ------------------------------------------------------------------------------
            Section(stringResource(R.string.settings_reminders)) {
                val p = state.permissions
                SettingRow(
                    title = stringResource(R.string.settings_notifications),
                    value = stringResource(if (p.notificationsGranted) R.string.settings_state_allowed else R.string.settings_state_not_allowed),
                    detail = if (p.notificationsGranted) null else stringResource(R.string.settings_notifications_off_detail),
                    action = if (p.notificationsGranted) null else stringResource(
                        if (p.canAskNotifications && !state.notificationsAsked) R.string.hint_action_allow else R.string.hint_action_open_settings,
                    ),
                    onAction = if (p.canAskNotifications && !state.notificationsAsked) onAskNotifications else onOpenNotificationSettings,
                )
                SettingRow(
                    title = stringResource(R.string.settings_exact_alarms),
                    value = stringResource(
                        when {
                            !p.exactAlarmsNeedSpecialAccess -> R.string.settings_state_not_needed
                            p.exactAlarmsAllowed -> R.string.settings_state_allowed
                            else -> R.string.settings_state_not_allowed
                        },
                    ),
                    detail = if (p.exactAlarmsNeedSpecialAccess && !p.exactAlarmsAllowed) stringResource(R.string.settings_exact_off_detail) else null,
                    action = if (p.exactAlarmsNeedSpecialAccess && !p.exactAlarmsAllowed) stringResource(R.string.hint_action_allow) else null,
                    onAction = onOpenExactAlarmSettings,
                )
                SettingRow(
                    title = stringResource(R.string.settings_sound),
                    value = null,
                    detail = null,
                    action = stringResource(R.string.settings_sound_action),
                    onAction = onOpenChannelSettings,
                )
                Text(
                    stringResource(R.string.settings_reminders_note),
                    style = AhoraType.caption,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // --- Apariencia ---------------------------------------------------------------------------------
            Section(stringResource(R.string.settings_appearance)) {
                Text(stringResource(R.string.settings_theme), style = AhoraType.body, color = c.text, modifier = Modifier.padding(bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        AhoraChip(
                            label = stringResource(mode.labelRes()),
                            selected = state.theme == mode,
                            onClick = { onTheme(mode) },
                        )
                    }
                }
            }

            // --- Comportamiento -----------------------------------------------------------------------------
            Section(stringResource(R.string.settings_behavior)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_keep_screen_on),
                        style = AhoraType.body,
                        color = c.text,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    AhoraSwitch(
                        checked = state.keepScreenOnInFocus,
                        onCheckedChange = onKeepScreenOn,
                        stateOn = stringResource(R.string.settings_state_allowed),
                        stateOff = stringResource(R.string.settings_state_not_allowed),
                    )
                }
            }

            // --- Datos --------------------------------------------------------------------------------------
            Section(stringResource(R.string.settings_data)) {
                TappableRow(stringResource(R.string.settings_export), stringResource(R.string.settings_export_detail), onExport)
                TappableRow(stringResource(R.string.settings_import), stringResource(R.string.settings_import_detail), onImport)
            }

            // --- Acerca de ----------------------------------------------------------------------------------
            Section(stringResource(R.string.settings_about), last = true) {
                Text(stringResource(R.string.app_name), style = AhoraType.cardTitle, color = c.text)
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = AhoraType.bodySmall,
                    color = c.textSecondary,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Text(stringResource(R.string.settings_privacy), style = AhoraType.body, color = c.text, modifier = Modifier.padding(bottom = 12.dp))
                Text(stringResource(R.string.settings_licenses), style = AhoraType.label, color = c.textSecondary)
                Text(stringResource(R.string.settings_licenses_body), style = AhoraType.caption, color = c.textSecondary)
            }
        }
    }

    state.pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = onCancelImport,
            shape = RectangleShape,
            containerColor = c.surface,
            title = { Text(stringResource(R.string.settings_import_title), style = AhoraType.sheetTitle, color = c.text) },
            text = {
                Text(
                    stringResource(R.string.settings_import_body, pending.currentCount, pending.incomingCount),
                    style = AhoraType.body,
                    color = c.text,
                )
            },
            confirmButton = { AhoraTextAction(stringResource(R.string.settings_import_confirm), onClick = onConfirmImport) },
            dismissButton = { AhoraTextAction(stringResource(R.string.settings_import_cancel), onClick = onCancelImport) },
        )
    }
}

/** A titled group separated from the next by the design's strong 2 px rule. */
@Composable
private fun Section(title: String, last: Boolean = false, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        SectionKicker(title, Modifier.padding(bottom = 8.dp))
        content()
        if (!last) {
            Column(Modifier.padding(top = 20.dp)) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(2.dp).background(Ahora.colors.divider))
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String?, detail: String?, action: String?, onAction: () -> Unit) {
    val c = Ahora.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = AhoraType.body, color = c.text)
            if (value != null) Text(value, style = AhoraType.caption, color = c.textSecondary)
            if (detail != null) Text(detail, style = AhoraType.caption, color = c.textSecondary)
        }
        if (action != null) AhoraTextAction(action, onClick = onAction)
    }
}

@Composable
private fun TappableRow(title: String, detail: String, onClick: () -> Unit) {
    val c = Ahora.colors
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = AhoraType.bodyStrong, color = c.accentText)
        Text(detail, style = AhoraType.caption, color = c.textSecondary)
    }
}

private fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}
