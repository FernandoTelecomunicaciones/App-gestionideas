package com.fernando.ahora.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fernando.ahora.R
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.rules.TaskFilter
import com.fernando.ahora.ui.common.DateLabels
import com.fernando.ahora.ui.common.asText
import com.fernando.ahora.ui.components.AhoraChip
import com.fernando.ahora.ui.components.AhoraTextAction
import com.fernando.ahora.ui.components.AhoraTextField
import com.fernando.ahora.ui.components.TabTopBar
import com.fernando.ahora.ui.components.TaskRow
import com.fernando.ahora.ui.theme.Ahora
import com.fernando.ahora.ui.theme.AhoraType

@Composable
fun TasksRoute(
    onOpenSettings: () -> Unit,
    onEditTask: (Long) -> Unit,
    viewModel: TasksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TasksScreen(
        state = state,
        query = viewModel.query,
        onQuery = viewModel::onQuery,
        onFilter = viewModel::onFilter,
        onShowCompleted = viewModel::setShowCompleted,
        onComplete = viewModel::complete,
        onReopen = viewModel::reopen,
        onEdit = onEditTask,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksScreen(
    state: TasksUiState,
    query: String,
    onQuery: (String) -> Unit,
    onFilter: (TaskFilter) -> Unit,
    onShowCompleted: (Boolean) -> Unit,
    onComplete: (Long) -> Unit,
    onReopen: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = state as? TasksUiState.Loaded
    Column(modifier.fillMaxSize()) {
        TabTopBar(onOpenSettings = onOpenSettings, modifier = Modifier.padding(horizontal = 12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
        ) {
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.tasks_title), style = AhoraType.title, color = Ahora.colors.text)
                    // TSK-05: a discreet text link, not a section or a chip. Completed tasks never dominate the default view.
                    val completed = loaded?.showCompleted == true
                    AhoraTextAction(
                        text = stringResource(if (completed) R.string.tasks_link_pending else R.string.tasks_link_completed),
                        onClick = { onShowCompleted(!completed) },
                    )
                }
            }
            item(key = "search") {
                AhoraTextField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = stringResource(R.string.tasks_search),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (loaded != null && !loaded.showCompleted) {
                item(key = "filters") {
                    FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TaskFilter.entries.forEach { f ->
                            AhoraChip(
                                label = stringResource(f.labelRes()),
                                selected = loaded.filter == f,
                                onClick = { onFilter(f) },
                                small = true,
                            )
                        }
                    }
                }
            }
            if (loaded != null) {
                if (loaded.items.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(
                                    if (loaded.showCompleted && loaded.query.isBlank()) R.string.tasks_empty_completed else R.string.tasks_empty,
                                ),
                                style = AhoraType.bodySmall,
                                color = Ahora.colors.textSecondary,
                            )
                        }
                    }
                } else {
                    items(loaded.items, key = { it.id }) { task ->
                        TaskListRow(task, loaded, onComplete, onReopen, onEdit)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskListRow(
    task: Task,
    state: TasksUiState.Loaded,
    onComplete: (Long) -> Unit,
    onReopen: (Long) -> Unit,
    onEdit: (Long) -> Unit,
) {
    val date = DateLabels.of(task.dueDate, state.today).asText()
    if (state.showCompleted) {
        // A completed occurrence of a recurring task cannot be reopened (D-31): a read-only, announced checkbox and no
        // editor (the editor never resurrects a completed task, R2-4). Undo right after completing is the way back.
        val recurring = task.recurrence != Recurrence.NONE
        TaskRow(
            title = task.title,
            priority = task.priority,
            secondary = date,
            checked = true,
            onCheckedChange = if (recurring) null else { _ -> onReopen(task.id) },
            checkedStateLabel = if (recurring) stringResource(R.string.tasks_recurring_done) else null,
        )
    } else {
        TaskRow(
            title = task.title,
            priority = task.priority,
            secondary = date,
            checked = false,
            onCheckedChange = { onComplete(task.id) },
            onClick = { onEdit(task.id) },
        )
    }
}

private fun TaskFilter.labelRes(): Int = when (this) {
    TaskFilter.PENDING -> R.string.tasks_filter_pending
    TaskFilter.UPCOMING -> R.string.tasks_filter_upcoming
    TaskFilter.NO_DATE -> R.string.tasks_filter_no_date
    TaskFilter.P1 -> R.string.tasks_filter_p1
}
