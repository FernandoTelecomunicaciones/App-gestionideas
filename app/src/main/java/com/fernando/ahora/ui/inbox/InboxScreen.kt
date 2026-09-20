package com.fernando.ahora.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fernando.ahora.R
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.ui.common.DateLabel
import com.fernando.ahora.ui.common.DateLabels
import com.fernando.ahora.ui.common.asText
import com.fernando.ahora.ui.common.longName
import com.fernando.ahora.ui.components.AhoraButton
import com.fernando.ahora.ui.components.AhoraChip
import com.fernando.ahora.ui.components.AhoraTextField
import com.fernando.ahora.ui.components.SectionKicker
import com.fernando.ahora.ui.components.TabTopBar
import com.fernando.ahora.ui.components.TaskRow
import com.fernando.ahora.ui.editor.DatePickerSheetDialog
import com.fernando.ahora.ui.theme.Ahora
import com.fernando.ahora.ui.theme.AhoraType
import java.time.LocalDate

@Composable
fun InboxRoute(
    onOpenSettings: () -> Unit,
    onEditTask: (Long) -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InboxScreen(
        state = state,
        capture = viewModel.capture,
        canSave = viewModel.canSave,
        chipDate = viewModel.chipDate,
        chipPriority = viewModel.chipPriority,
        today = viewModel.today(),
        onCapture = viewModel::onCapture,
        onSave = viewModel::save,
        onToggleToday = viewModel::toggleToday,
        onPickDate = viewModel::setDate,
        onPickPriority = viewModel::setPriority,
        onOpenSettings = onOpenSettings,
        onEdit = onEditTask,
    )
}

/**
 * Bandeja (PRODUCT_SPEC §5.2). One always-visible capture row; Guardar and IME Done both save and KEEP the focus and
 * keyboard so several thoughts can be captured in a row (INB-04). The list below is deliberately bare: one line per
 * item, no checkbox, no metadata — deciding happens in the editor (INB-06).
 */
@Composable
fun InboxScreen(
    state: InboxUiState,
    capture: String,
    canSave: Boolean,
    chipDate: LocalDate?,
    chipPriority: Priority?,
    today: LocalDate,
    onCapture: (String) -> Unit,
    onSave: () -> Boolean,
    onToggleToday: () -> Unit,
    onPickDate: (LocalDate?) -> Unit,
    onPickPriority: (Priority?) -> Unit,
    onOpenSettings: () -> Unit,
    onEdit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        TabTopBar(onOpenSettings = onOpenSettings, modifier = Modifier.padding(horizontal = 12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
        ) {
            item(key = "header") {
                Column(Modifier.padding(bottom = 16.dp)) {
                    Text(stringResource(R.string.inbox_title), style = AhoraType.title, color = Ahora.colors.text)
                    Text(
                        stringResource(R.string.inbox_subtitle),
                        style = AhoraType.bodySmall,
                        color = Ahora.colors.textSecondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item(key = "capture") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AhoraTextField(
                        value = capture,
                        onValueChange = onCapture,
                        placeholder = stringResource(R.string.inbox_placeholder),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        // Providing onDone replaces the default "hide the keyboard": the field keeps focus (INB-04).
                        keyboardActions = KeyboardActions(onDone = { onSave() }),
                    )
                    AhoraButton(
                        text = stringResource(R.string.inbox_save),
                        onClick = { onSave() },
                        enabled = canSave,
                        modifier = Modifier.height(56.dp),
                    )
                }
            }
            item(key = "chips") {
                CaptureChips(chipDate, chipPriority, today, onToggleToday, onPickDate = { showDatePicker = true }, onClearDate = { onPickDate(null) }, onPickPriority)
            }
            item(key = "section") {
                SectionKicker(stringResource(R.string.inbox_section), Modifier.padding(top = 12.dp, bottom = 6.dp))
            }
            when (state) {
                InboxUiState.Loading -> Unit
                is InboxUiState.Loaded -> {
                    if (state.items.isEmpty()) {
                        item(key = "empty") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.inbox_empty), style = AhoraType.bodySmall, color = Ahora.colors.textSecondary)
                            }
                        }
                    } else {
                        items(state.items, key = { it.id }) { task -> InboxRow(task, onEdit) }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        DatePickerSheetDialog(
            initial = chipDate ?: today,
            onPicked = { onPickDate(it); showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
}

@Composable
private fun InboxRow(task: Task, onEdit: (Long) -> Unit) {
    TaskRow(title = task.title, singleLine = true, onClick = { onEdit(task.id) })
}

/** INB-05: Hoy · Fecha · Prioridad pre-set the item being captured. A set chip clears when tapped; none can block saving. */
@Composable
private fun CaptureChips(
    chipDate: LocalDate?,
    chipPriority: Priority?,
    today: LocalDate,
    onToggleToday: () -> Unit,
    onPickDate: () -> Unit,
    onClearDate: () -> Unit,
    onPickPriority: (Priority?) -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val isToday = chipDate == today
    val isCustom = chipDate != null && !isToday
    Row(Modifier.fillMaxWidth().padding(top = 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AhoraChip(
            label = stringResource(R.string.inbox_chip_today),
            selected = isToday,
            onClick = onToggleToday,
            small = true,
        )
        AhoraChip(
            label = if (isCustom) (DateLabels.of(chipDate, today) as? DateLabel.Other)?.text ?: chipDate.toString()
            else stringResource(R.string.inbox_chip_date),
            selected = isCustom,
            onClick = { if (isCustom) onClearDate() else onPickDate() },
            small = true,
        )
        Box {
            AhoraChip(
                label = chipPriority?.name ?: stringResource(R.string.inbox_chip_priority),
                selected = chipPriority != null,
                onClick = { if (chipPriority != null) onPickPriority(null) else menuOpen = true },
                small = true,
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RectangleShape,
                containerColor = Ahora.colors.surface,
            ) {
                Priority.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("${p.name} · ${p.longName()}", style = AhoraType.body, color = Ahora.colors.text) },
                        onClick = { onPickPriority(p); menuOpen = false },
                    )
                }
            }
        }
    }
}
