package com.fernando.ahora.ui.editor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.fernando.ahora.R
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.reminders.ReminderPermissions
import com.fernando.ahora.ui.common.DateLabel
import com.fernando.ahora.ui.common.DateLabels
import com.fernando.ahora.ui.common.PickerDates
import com.fernando.ahora.ui.common.asText
import com.fernando.ahora.ui.common.is24Hour
import com.fernando.ahora.ui.common.longName
import com.fernando.ahora.ui.common.timeText
import com.fernando.ahora.ui.components.AhoraButton
import com.fernando.ahora.ui.components.AhoraChip
import com.fernando.ahora.ui.components.AhoraSwitch
import com.fernando.ahora.ui.components.AhoraTextAction
import com.fernando.ahora.ui.components.AhoraTextField
import com.fernando.ahora.ui.components.ButtonKind
import com.fernando.ahora.ui.components.ChipRow
import com.fernando.ahora.ui.components.FieldGroup
import com.fernando.ahora.ui.theme.Ahora
import com.fernando.ahora.ui.theme.AhoraType
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The single create/edit surface (PRODUCT_SPEC §5.4): a fully expanded `ModalBottomSheet` composed once at the root
 * and driven by the Activity-scoped [EditorViewModel]. It is state, never a destination.
 *
 * Progressive disclosure: title, notes, date, time, priority are visible; repeat / duration / list hide behind
 * "+ Más opciones". A task saves with a title alone. Cancelar, scrim, swipe-down and back all discard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSheet(viewModel: EditorViewModel) {
    val draft = viewModel.draft ?: return
    val c = Ahora.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onNotificationPermissionAnswered()
    }
    // The user may have changed a permission in system settings while the sheet was open.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose { }
    }

    /** Animate the sheet away first, then apply the action (so the exit is not a hard cut). */
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(
        onDismissRequest = viewModel::dismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = c.surface,
        contentColor = c.text,
        scrimColor = c.scrim,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        // Drawn here, not through `dragHandle`: M3 wraps that slot in a clickable node that TalkBack reaches as an
        // unlabelled 48x35 dp "button" (release-validation a11y audit). The whole sheet still drags and dismisses.
        Box(Modifier.fillMaxWidth().height(35.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(top = 12.dp).size(width = 36.dp, height = 4.dp).background(c.divider))
        }
        EditorContent(
            draft = draft,
            viewModel = viewModel,
            onCancel = { closeThen(viewModel::dismiss) },
            onSave = { closeThen(viewModel::save) },
            onDelete = { closeThen(viewModel::delete) },
            onAskNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onOpenSettings = { intent -> context.startSafely(intent) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorContent(
    draft: EditorDraft,
    viewModel: EditorViewModel,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onAskNotifications: () -> Unit,
    onOpenSettings: (Intent) -> Unit,
) {
    val c = Ahora.colors
    val context = LocalContext.current
    val today = viewModel.today()
    val date = draft.dueDate
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Creating a task is the "creación rápida" surface (PRODUCT_SPEC §5.4): type immediately. Editing is not.
        if (draft.isNew) {
            delay(150)
            runCatching { titleFocus.requestFocus() }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            stringResource(if (draft.isNew) R.string.editor_new else R.string.editor_edit),
            style = AhoraType.sheetTitle,
            color = c.text,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        FieldGroup(stringResource(R.string.editor_title_label)) {
            AhoraTextField(
                value = draft.title,
                onValueChange = viewModel::onTitle,
                placeholder = stringResource(R.string.editor_title_placeholder),
                modifier = Modifier.focusRequester(titleFocus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            )
        }

        FieldGroup(stringResource(R.string.editor_notes_label)) {
            AhoraTextField(
                value = draft.notes,
                onValueChange = viewModel::onNotes,
                placeholder = stringResource(R.string.editor_notes_placeholder),
                singleLine = false,
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        }

        // --- Fecha ---------------------------------------------------------------------------------------------
        FieldGroup(stringResource(R.string.editor_date_label)) {
            val isToday = date == today
            val isTomorrow = date == today.plusDays(1)
            val isCustom = date != null && !isToday && !isTomorrow
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // "Hoy"/"Mañana" are resolved when tapped: a sheet left open past midnight must not save yesterday (GC-13).
                DateChip(stringResource(R.string.editor_date_today), isToday) {
                    val now = viewModel.today()
                    viewModel.onDate(if (date == now) null else now)
                }
                DateChip(stringResource(R.string.editor_date_tomorrow), isTomorrow) {
                    val tomorrow = viewModel.today().plusDays(1)
                    viewModel.onDate(if (date == tomorrow) null else tomorrow)
                }
                DateChip(
                    label = if (isCustom) (DateLabels.of(date, today) as? DateLabel.Other)?.text ?: date.toString()
                    else stringResource(R.string.editor_date_pick),
                    selected = isCustom,
                ) { if (isCustom) viewModel.onDate(null) else showDatePicker = true }
            }
        }

        // --- Hora + Recordatorio -------------------------------------------------------------------------------
        FieldGroup(stringResource(R.string.editor_time_label)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AhoraButton(
                    text = draft.dueTime?.let { timeText(it) } ?: stringResource(R.string.editor_time_none),
                    onClick = { showTimePicker = true },
                    kind = ButtonKind.Secondary,
                )
                if (draft.dueTime != null) {
                    AhoraTextAction(stringResource(R.string.editor_time_clear), onClick = { viewModel.onTime(null) })
                }
            }
        }

        if (draft.dueTime != null) {
            ReminderSection(
                draft = draft,
                viewModel = viewModel,
                onAskNotifications = onAskNotifications,
                onOpenSettings = { intent -> onOpenSettings(intent) },
                packageName = context.packageName,
            )
        }

        // --- Prioridad -----------------------------------------------------------------------------------------
        FieldGroup(stringResource(R.string.editor_priority_label)) {
            PrioritySegments(selected = draft.priorityValue, onSelect = viewModel::onPriority)
        }

        // --- Más opciones --------------------------------------------------------------------------------------
        AhoraTextAction(
            text = stringResource(if (draft.moreOpen) R.string.editor_more_open else R.string.editor_more_closed),
            onClick = viewModel::toggleMore,
            stateDescription = null,
        )
        if (draft.moreOpen) {
            FieldGroup(stringResource(R.string.editor_repeat_label)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Recurrence.entries.forEach { r ->
                        AhoraChip(
                            label = stringResource(r.labelRes()),
                            selected = draft.recurrenceValue == r,
                            enabled = date != null || r == Recurrence.NONE,
                            onClick = { viewModel.onRecurrence(r) },
                        )
                    }
                }
                if (date == null) {
                    Text(stringResource(R.string.editor_repeat_needs_date), style = AhoraType.caption, color = c.textSecondary)
                }
            }
            FieldGroup(stringResource(R.string.editor_duration_label)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf<Int?>(null, 15, 30, 60, 120).forEach { minutes ->
                        AhoraChip(
                            label = stringResource(durationLabelRes(minutes)),
                            selected = draft.estimatedMinutes == minutes,
                            onClick = { viewModel.onEstimate(minutes) },
                        )
                    }
                }
            }
            FieldGroup(stringResource(R.string.editor_list_label)) {
                AhoraTextField(
                    value = draft.list,
                    onValueChange = viewModel::onList,
                    placeholder = stringResource(R.string.editor_list_placeholder),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                )
            }
        }

        // --- Acciones ------------------------------------------------------------------------------------------
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AhoraButton(
                text = stringResource(R.string.editor_cancel),
                onClick = onCancel,
                kind = ButtonKind.Secondary,
                modifier = Modifier.weight(1f),
            )
            AhoraButton(
                text = stringResource(R.string.editor_save),
                onClick = onSave,
                enabled = draft.canSave,
                modifier = Modifier.weight(1f),
            )
        }
        if (!draft.isNew) {
            AhoraTextAction(
                text = stringResource(R.string.editor_delete),
                onClick = onDelete,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (showDatePicker) {
        DatePickerSheetDialog(
            initial = date ?: today,
            onPicked = { picked -> viewModel.onDate(picked); showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showTimePicker) {
        TimePickerSheetDialog(
            initial = draft.dueTime ?: LocalTime.of(9, 0),
            onPicked = { picked -> viewModel.onTime(picked); showTimePicker = false },
            onDismiss = { showTimePicker = false },
        )
    }
}

@Composable
private fun DateChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AhoraChip(label = label, selected = selected, onClick = onClick)
}

/** EDT-05: the switch, its "already past" helper and the two permission hints (PRODUCT_SPEC §8.1). */
@Composable
private fun ReminderSection(
    draft: EditorDraft,
    viewModel: EditorViewModel,
    onAskNotifications: () -> Unit,
    onOpenSettings: (Intent) -> Unit,
    packageName: String,
) {
    val c = Ahora.colors
    val permissions = viewModel.permissions
    val label = stringResource(R.string.editor_reminder)
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().border(1.dp, c.controlOutline).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = AhoraType.body, color = c.text, modifier = Modifier.weight(1f))
            AhoraSwitch(
                checked = draft.reminder,
                onCheckedChange = { on ->
                    viewModel.onReminder(on)
                    // In context, never up-front: the first time the switch goes on and the dialog can still be shown.
                    if (on && permissions.canAskNotifications && !viewModel.notificationsAsked) onAskNotifications()
                },
                label = label,
                stateOn = stringResource(R.string.switch_state_on),
                stateOff = stringResource(R.string.switch_state_off),
            )
        }
        if (draft.reminder) {
            if (viewModel.isReminderInPast(draft)) {
                Text(
                    stringResource(R.string.editor_reminder_past),
                    style = AhoraType.caption,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            when {
                !permissions.notificationsGranted -> Hint(
                    text = stringResource(R.string.hint_notifications_off),
                    action = stringResource(
                        if (permissions.canAskNotifications && !viewModel.notificationsAsked) R.string.hint_action_allow
                        else R.string.hint_action_open_settings,
                    ),
                    onAction = {
                        if (permissions.canAskNotifications && !viewModel.notificationsAsked) {
                            onAskNotifications()
                        } else {
                            onOpenSettings(ReminderPermissions.notificationSettingsIntent(packageName))
                        }
                    },
                )
                permissions.exactAlarmsNeedSpecialAccess && !permissions.exactAlarmsAllowed -> Hint(
                    text = stringResource(R.string.hint_exact_off),
                    action = stringResource(R.string.hint_action_allow),
                    onAction = { ReminderPermissions.exactAlarmSettingsIntent(packageName)?.let(onOpenSettings) },
                )
            }
        }
    }
}

/** Plain-language inline hint + one action; never a blocking dialog (PRODUCT_SPEC §8). */
@Composable
private fun Hint(text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(text, style = AhoraType.caption, color = Ahora.colors.textSecondary)
        AhoraTextAction(action, onClick = onAction)
    }
}

/**
 * P1 · P2 · P3 as one single-choice group. Tapping the selected segment clears the priority (EDT-06). At large font
 * scales the segments stack vertically so the labels never clip (A11Y-05).
 */
@Composable
private fun PrioritySegments(selected: Priority?, onSelect: (Priority?) -> Unit) {
    val stacked = LocalDensity.current.fontScale > 1.3f
    val segment: @Composable (Priority, Modifier) -> Unit = { p, mod ->
        AhoraChip(
            label = "${p.name} · ${p.longName()}",
            selected = selected == p,
            onClick = { onSelect(if (selected == p) null else p) },
            modifier = mod,
            fillWidth = true,
        )
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Priority.entries.forEach { segment(it, Modifier.fillMaxWidth()) }
        }
    } else {
        // Equal-height segments even when one label wraps to two lines.
        ChipRow(Modifier.height(IntrinsicSize.Min)) {
            Priority.entries.forEach { segment(it, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePickerSheetDialog(initial: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val c = Ahora.colors
    val state = rememberDatePickerState(initialSelectedDateMillis = PickerDates.toPickerMillis(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        colors = DatePickerDefaults.colors(containerColor = c.surface),
        confirmButton = {
            AhoraTextAction(
                stringResource(R.string.picker_ok),
                onClick = { state.selectedDateMillis?.let { onPicked(PickerDates.fromPickerMillis(it)) } ?: onDismiss() },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        },
        dismissButton = {
            AhoraTextAction(stringResource(R.string.picker_cancel), onClick = onDismiss, modifier = Modifier.padding(horizontal = 12.dp))
        },
    ) {
        DatePicker(
            state = state,
            colors = DatePickerDefaults.colors(
                containerColor = c.surface,
                selectedDayContainerColor = c.accentFill,
                selectedDayContentColor = c.onAccentFill,
                todayDateBorderColor = c.accent,
                todayContentColor = c.accentText,
                selectedYearContainerColor = c.accentFill,
                selectedYearContentColor = c.onAccentFill,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheetDialog(initial: LocalTime, onPicked: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val c = Ahora.colors
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour())
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = c.surface,
        title = { Text(stringResource(R.string.picker_time_title), style = AhoraType.sheetTitle, color = c.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = c.bg,
                        selectorColor = c.accentFill,
                        containerColor = c.surface,
                        clockDialSelectedContentColor = c.onAccentFill,
                        clockDialUnselectedContentColor = c.text,
                        timeSelectorSelectedContainerColor = c.accentFill,
                        timeSelectorSelectedContentColor = c.onAccentFill,
                        timeSelectorUnselectedContainerColor = c.bg,
                        timeSelectorUnselectedContentColor = c.text,
                        periodSelectorSelectedContainerColor = c.accentFill,
                        periodSelectorSelectedContentColor = c.onAccentFill,
                        periodSelectorUnselectedContentColor = c.text,
                        periodSelectorBorderColor = c.controlOutline,
                    ),
                )
            }
        },
        confirmButton = {
            AhoraTextAction(stringResource(R.string.picker_ok), onClick = { onPicked(LocalTime.of(state.hour, state.minute)) })
        },
        dismissButton = { AhoraTextAction(stringResource(R.string.picker_cancel), onClick = onDismiss) },
    )
}

private fun Recurrence.labelRes(): Int = when (this) {
    Recurrence.NONE -> R.string.editor_repeat_none
    Recurrence.DAILY -> R.string.editor_repeat_daily
    Recurrence.WEEKLY -> R.string.editor_repeat_weekly
    Recurrence.MONTHLY -> R.string.editor_repeat_monthly
}

private fun durationLabelRes(minutes: Int?): Int = when (minutes) {
    15 -> R.string.editor_duration_15
    30 -> R.string.editor_duration_30
    60 -> R.string.editor_duration_60
    120 -> R.string.editor_duration_120
    else -> R.string.editor_duration_none
}

internal fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No handler for this settings screen on this device: nothing to do, the hint stays visible.
    }
}
