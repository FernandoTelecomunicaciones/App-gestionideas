package com.fernando.ahora.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.rules.GreetingBand
import com.fernando.ahora.domain.rules.TaskFilter
import com.fernando.ahora.testing.aTask
import com.fernando.ahora.ui.focus.FocusScreen
import com.fernando.ahora.ui.focus.FocusUiState
import com.fernando.ahora.ui.home.HomeScreen
import com.fernando.ahora.ui.home.HomeUiState
import com.fernando.ahora.ui.inbox.InboxScreen
import com.fernando.ahora.ui.inbox.InboxUiState
import com.fernando.ahora.ui.tasks.TasksScreen
import com.fernando.ahora.ui.tasks.TasksUiState
import com.fernando.ahora.ui.theme.AhoraTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The stateless screens, rendered for real (Robolectric): copy, structure, and which controls are actionable. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class ScreensComposeTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 9, 20)

    private fun home(state: HomeUiState, onComplete: (Long) -> Unit = {}, onStart: (Long) -> Unit = {}, onToggle: () -> Unit = {}) =
        compose.setContent {
            AhoraTheme(dark = false) {
                HomeScreen(state, onOpenSettings = {}, onStart = onStart, onEdit = {}, onComplete = onComplete, onToggleRest = onToggle)
            }
        }

    private fun loaded(
        ahora: com.fernando.ahora.domain.model.Task? = null,
        rows: List<com.fernando.ahora.domain.model.Task> = emptyList(),
        rest: List<com.fernando.ahora.domain.model.Task> = emptyList(),
        expanded: Boolean = false,
    ) = HomeUiState.Loaded(GreetingBand.AFTERNOON, today, ahora, rows, rest, expanded)

    // ---- Hoy -------------------------------------------------------------------------------------------------

    @Test
    fun hoy_whileLoading_showsNoEmptyStateAtAll() {
        home(HomeUiState.Loading)
        compose.onNodeWithText("Nada pendiente.").assertDoesNotExist()
        compose.onNodeWithText("¿Qué toca ahora?").assertDoesNotExist()
    }

    @Test
    fun hoy_empty_saysNadaPendiente() {
        home(loaded())
        compose.onNodeWithText("¿Qué toca ahora?").assertIsDisplayed()
        compose.onNodeWithText("Nada pendiente.").assertIsDisplayed()
    }

    @Test
    fun hoy_showsOneAhoraCard_withEmpezar_andTheThreeRowsHeader() {
        var started = -1L
        home(
            loaded(
                ahora = aTask(1, "Preparar documentación", today, priority = Priority.P1),
                rows = listOf(aTask(2, "Llamar al banco", today, priority = Priority.P2), aTask(3, "Enviar factura", today, priority = Priority.P3)),
                rest = listOf(aTask(4, "Idea suelta")),
            ),
            onStart = { started = it },
        )

        compose.onNodeWithText("AHORA").assertIsDisplayed()
        compose.onNodeWithText("Preparar documentación").assertIsDisplayed()
        compose.onNodeWithText("TUS 3 DE HOY").assertIsDisplayed()
        compose.onNodeWithText("Llamar al banco").assertIsDisplayed()
        compose.onNodeWithText("También pendiente · 1").assertIsDisplayed()
        compose.onNodeWithText("Idea suelta").assertDoesNotExist() // collapsed by default

        compose.onNodeWithText("Empezar").performClick()
        assertEquals(1L, started)
    }

    @Test
    fun hoy_priorityIsAlwaysText_neverColourAlone() {
        home(loaded(ahora = aTask(1, "a", today, priority = Priority.P1), rows = listOf(aTask(2, "b", today, priority = Priority.P2))))
        compose.onAllNodesWithText("P1").assertCountEquals(1)
        compose.onAllNodesWithText("P2").assertCountEquals(1)
    }

    @Test
    fun hoy_overdue_looksLikeAnyOtherTask_noBlameCopy() {
        home(loaded(ahora = aTask(1, "Renovar pasaporte", today.minusDays(9), priority = Priority.P2)))
        compose.onNodeWithText("Renovar pasaporte").assertIsDisplayed()
        compose.onNodeWithText("11 sep", substring = true).assertIsDisplayed() // a plain date, like any other
        for (word in listOf("vencid", "retras", "atras", "urgent", "perdid")) {
            compose.onAllNodesWithText(word, substring = true, ignoreCase = true).assertCountEquals(0)
        }
    }

    @Test
    fun hoy_tappingTheRestCounter_asksToExpand_andExpandedRowsAreShown() {
        var toggled = 0
        home(loaded(rest = listOf(aTask(4, "Idea suelta")), expanded = true), onToggle = { toggled++ })
        compose.onNodeWithText("Idea suelta").assertIsDisplayed()
        compose.onNodeWithText("También pendiente · 1").performClick()
        assertEquals(1, toggled)
    }

    @Test
    fun hoy_theCheckbox_completes() {
        var completed = -1L
        home(loaded(rows = listOf(aTask(2, "Llamar al banco", today, priority = Priority.P2))), onComplete = { completed = it })
        compose.onNodeWithContentDescription("Marcar como hecha: Llamar al banco").performClick()
        assertEquals(2L, completed)
    }

    // ---- Bandeja ---------------------------------------------------------------------------------------------

    private fun inbox(capture: String, items: List<com.fernando.ahora.domain.model.Task> = emptyList(), onSave: () -> Boolean = { true }) =
        compose.setContent {
            AhoraTheme(dark = false) {
                InboxScreen(
                    state = InboxUiState.Loaded(items), capture = capture, canSave = capture.isNotBlank(),
                    chipDate = null, chipPriority = null, today = today,
                    onCapture = {}, onSave = onSave, onToggleToday = {}, onPickDate = {}, onPickPriority = {},
                    onOpenSettings = {}, onEdit = {},
                )
            }
        }

    @Test
    fun bandeja_guardar_isDisabledWhileTheTitleIsBlank_andEnabledOtherwise() {
        inbox("")
        compose.onNode(hasClickAction() and androidx.compose.ui.test.hasText("Guardar")).assertIsNotEnabled()
    }

    @Test
    fun bandeja_guardar_isEnabledWithATitle_andSaves() {
        var saved = false
        inbox("Llamar al dentista", onSave = { saved = true; true })
        compose.onNode(hasClickAction() and androidx.compose.ui.test.hasText("Guardar")).assertIsEnabled().performClick()
        assertEquals(true, saved)
    }

    @Test
    fun bandeja_showsTheThreeShortcutChips_andTheHeadingCopy() {
        inbox("")
        for (label in listOf("Hoy", "Fecha", "Prioridad")) compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("¿Qué tienes en la cabeza?").assertIsDisplayed()
        compose.onNodeWithText("Todo fuera de tu cabeza.").assertIsDisplayed()
    }

    @Test
    fun bandeja_rowsAreBare_noCheckboxNoMetadata() {
        inbox("", items = listOf(aTask(1, "Comprar pan")))
        compose.onNodeWithText("Comprar pan").assertIsDisplayed()
        compose.onAllNodesWithText("Marcar como hecha", substring = true).assertCountEquals(0)
    }

    // ---- Tareas ----------------------------------------------------------------------------------------------

    private fun tasks(state: TasksUiState.Loaded, onReopen: (Long) -> Unit = {}, onFilter: (TaskFilter) -> Unit = {}) =
        compose.setContent {
            AhoraTheme(dark = false) {
                TasksScreen(state, "", {}, onFilter, {}, {}, onReopen, {}, {})
            }
        }

    @Test
    fun tareas_showsTheFourFilters_andAppliesTheTappedOne() {
        var picked: TaskFilter? = null
        tasks(TasksUiState.Loaded(TaskFilter.PENDING, false, "", listOf(aTask(1, "x", today)), today), onFilter = { picked = it })
        for (label in listOf("Pendientes", "Próximas", "Sin fecha", "P1")) compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("Próximas").performClick()
        assertEquals(TaskFilter.UPCOMING, picked)
    }

    @Test
    fun tareas_emptyResult_saysNadaCoincide() {
        tasks(TasksUiState.Loaded(TaskFilter.PENDING, false, "zzz", emptyList(), today))
        compose.onNodeWithText("Nada coincide.").assertIsDisplayed()
    }

    @Test
    fun tareas_completedView_reopensNormalTasks_butNotRecurringOccurrences() {
        var reopened = -1L
        tasks(
            TasksUiState.Loaded(
                TaskFilter.PENDING, true, "",
                listOf(aTask(1, "Normal", done = true), aTask(2, "Se repite", today, done = true, recurrence = Recurrence.DAILY)),
                today,
            ),
            onReopen = { reopened = it },
        )
        compose.onNodeWithText("Pendientes").assertIsDisplayed() // the link back
        compose.onNodeWithContentDescription("Normal").performClick()
        assertEquals("a normal completed task reopens", 1L, reopened)

        reopened = -1
        compose.onNodeWithContentDescription("Se repite").performClick()
        assertEquals("a recurring occurrence's checkbox is read-only (D-31)", -1L, reopened)
    }

    // ---- Foco ------------------------------------------------------------------------------------------------

    @Test
    fun foco_showsVeryLittle_andTheThreeActions() {
        var finished = 0; var postponed = 0; var exited = 0; var preset = 0
        compose.setContent {
            AhoraTheme(dark = false) {
                FocusScreen(
                    FocusUiState.Active("Escribir informe", 25, 25 * 60L, keepScreenOn = true, alertPending = false),
                    onPreset = { preset = it }, onFinish = { finished++ }, onPostpone = { postponed++ }, onExit = { exited++ },
                )
            }
        }
        compose.onNodeWithText("FOCO").assertIsDisplayed()
        compose.onNodeWithText("Escribir informe").assertIsDisplayed()
        compose.onNodeWithText("Sólo esto ahora.").assertIsDisplayed()
        compose.onNodeWithText("25:00").assertIsDisplayed()
        for (m in listOf("15 min", "25 min", "45 min")) compose.onNodeWithText(m).assertIsDisplayed()

        compose.onNodeWithText("15 min").performClick(); assertEquals(15, preset)
        compose.onNodeWithText("Terminar").performClick(); assertEquals(1, finished)
        compose.onNodeWithText("Posponer").performClick(); assertEquals(1, postponed)
        compose.onNodeWithText("Salir").performClick(); assertEquals(1, exited)
        // Nothing else: no tab bar, no FAB, no other task, no statistics.
        compose.onNodeWithText("Hoy").assertDoesNotExist()
        compose.onNodeWithContentDescription("Nueva tarea").assertDoesNotExist()
    }

    @Test
    fun foco_clock_restsAtZero() {
        compose.setContent {
            AhoraTheme(dark = false) {
                FocusScreen(FocusUiState.Active("x", 15, 0, keepScreenOn = true, alertPending = false), {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("00:00").assertIsDisplayed()
    }
}
