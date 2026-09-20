package com.fernando.ahora.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import com.fernando.ahora.R
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.rules.GreetingBand
import com.fernando.ahora.ui.common.DateLabels
import com.fernando.ahora.ui.common.asText
import com.fernando.ahora.ui.common.timeText
import com.fernando.ahora.ui.components.AhoraButton
import com.fernando.ahora.ui.components.LucideIcon
import com.fernando.ahora.ui.components.PriorityTag
import com.fernando.ahora.ui.components.SectionKicker
import com.fernando.ahora.ui.components.TabTopBar
import com.fernando.ahora.ui.components.TaskRow
import com.fernando.ahora.ui.components.focusRing
import com.fernando.ahora.ui.navigation.Home
import com.fernando.ahora.ui.theme.Ahora
import com.fernando.ahora.ui.theme.AhoraType
import java.time.LocalDate

/** Route layer: owns the ViewModel, wires navigation, and collapses "También pendiente" when Hoy is left. */
@Composable
fun HomeRoute(
    navController: NavController,
    onOpenSettings: () -> Unit,
    onStartFocus: (Long) -> Unit,
    onEditTask: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // PRODUCT_SPEC HOY-04 / ARCHITECTURE R2-13: the expanded list collapses when the user leaves the tab, but survives
    // rotation and process death (it lives in SavedStateHandle). A destination change (not a config change) collapses it.
    DisposableEffect(navController, viewModel) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            if (!destination.hasRoute<Home>()) viewModel.collapseRest()
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    HomeScreen(
        state = state,
        onOpenSettings = onOpenSettings,
        onStart = onStartFocus,
        onEdit = onEditTask,
        onComplete = viewModel::complete,
        onToggleRest = viewModel::toggleRest,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenSettings: () -> Unit,
    onStart: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onComplete: (Long) -> Unit,
    onToggleRest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TabTopBar(onOpenSettings = onOpenSettings, modifier = Modifier.padding(horizontal = 12.dp))
        when (state) {
            HomeUiState.Loading -> Unit
            is HomeUiState.Loaded -> HomeContent(state, onStart, onEdit, onComplete, onToggleRest)
        }
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState.Loaded,
    onStart: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onComplete: (Long) -> Unit,
    onToggleRest: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // HOY-09: >= 88 dp so the FAB never covers the last row.
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 96.dp),
    ) {
        item(key = "header") {
            Column(Modifier.padding(bottom = 20.dp)) {
                Text(stringResource(state.greeting.textRes()), style = AhoraType.label, color = Ahora.colors.textSecondary)
                Text(stringResource(R.string.home_title), style = AhoraType.headline, color = Ahora.colors.text)
            }
        }

        state.ahora?.let { ahora ->
            item(key = "ahora") { AhoraCard(ahora, state.today, onStart = { onStart(ahora.id) }, onEdit = { onEdit(ahora.id) }) }
        }

        if (state.todayRows.isNotEmpty()) {
            item(key = "today-header") {
                SectionKicker(stringResource(R.string.home_today_header), Modifier.padding(top = 24.dp, bottom = 6.dp))
            }
            items(state.todayRows, key = { "t${it.id}" }) { task ->
                HoyRow(task, onEdit, onComplete)
            }
        }

        if (state.rest.isNotEmpty()) {
            item(key = "rest-toggle") {
                RestToggle(count = state.rest.size, expanded = state.restExpanded, onToggle = onToggleRest)
            }
            if (state.restExpanded) {
                items(state.rest, key = { "r${it.id}" }) { task -> HoyRow(task, onEdit, onComplete) }
            }
        }

        if (state.isEmpty) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.home_empty), style = AhoraType.bodySmall, color = Ahora.colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun HoyRow(task: Task, onEdit: (Long) -> Unit, onComplete: (Long) -> Unit) {
    TaskRow(
        title = task.title,
        priority = task.priority,
        checked = false,
        onCheckedChange = { onComplete(task.id) },
        onClick = { onEdit(task.id) },
    )
}

/** HOY-02: one outlined card (never a coloured fill): kicker, title, tag + date (+ bell/time), Empezar. */
@Composable
private fun AhoraCard(task: Task, today: LocalDate, onStart: () -> Unit, onEdit: () -> Unit) {
    val c = Ahora.colors
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        border = BorderStroke(1.dp, c.divider),
        colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent, contentColor = c.text),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(stringResource(R.string.home_kicker_now).uppercase(), style = AhoraType.kicker, color = c.accentText)
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .focusRing(source)
                    .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onEdit),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(task.title, style = AhoraType.cardTitle, color = c.text)
            }
            Row(
                Modifier.padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                task.priority?.let { PriorityTag(it) }
                Text(DateLabels.of(task.dueDate, today).asText(), style = AhoraType.label, color = c.textSecondary)
                val time = task.dueTime
                if (task.reminderEnabled && time != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LucideIcon(R.drawable.ic_bell, null, size = 14.dp, tint = c.textSecondary)
                        Text(timeText(time), style = AhoraType.label, color = c.textSecondary)
                    }
                }
            }
            AhoraButton(
                text = stringResource(R.string.home_start),
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                alignStart = true,
            )
        }
    }
}

@Composable
private fun RestToggle(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val state = stringResource(if (expanded) R.string.home_rest_expanded_state else R.string.home_rest_collapsed_state)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .heightIn(min = 48.dp)
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(stringResource(R.string.home_rest, count), style = AhoraType.bodySmallStrong, color = Ahora.colors.accentText)
    }
}

private fun GreetingBand.textRes(): Int = when (this) {
    GreetingBand.MORNING -> R.string.greeting_morning
    GreetingBand.AFTERNOON -> R.string.greeting_afternoon
    GreetingBand.NIGHT -> R.string.greeting_night
}
