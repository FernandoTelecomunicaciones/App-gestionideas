package com.fernando.ahora.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fernando.ahora.R
import com.fernando.ahora.reminders.LinkDestination
import com.fernando.ahora.ui.components.LucideIcon
import com.fernando.ahora.ui.components.focusRing
import com.fernando.ahora.ui.editor.EditorSheet
import com.fernando.ahora.ui.editor.EditorViewModel
import com.fernando.ahora.ui.focus.FocusRoute
import com.fernando.ahora.ui.home.HomeRoute
import com.fernando.ahora.ui.inbox.InboxRoute
import com.fernando.ahora.ui.navigation.Focus
import com.fernando.ahora.ui.navigation.Home
import com.fernando.ahora.ui.navigation.Inbox
import com.fernando.ahora.ui.navigation.Settings
import com.fernando.ahora.ui.navigation.Tasks
import com.fernando.ahora.ui.navigation.applyLink
import com.fernando.ahora.ui.navigation.isTab
import com.fernando.ahora.ui.navigation.leaveFocusToHome
import com.fernando.ahora.ui.navigation.openFocus
import com.fernando.ahora.ui.navigation.switchTab
import com.fernando.ahora.ui.settings.SettingsRoute
import com.fernando.ahora.ui.tasks.TasksRoute
import com.fernando.ahora.ui.theme.Ahora
import com.fernando.ahora.ui.theme.AhoraType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val FADE_IN_MS = 210
private const val FADE_OUT_MS = 90

/**
 * The real root (ARCHITECTURE §8): one `Scaffold` whose chrome follows the current destination (PRODUCT_SPEC §3.2),
 * the `NavHost`, the Snackbar host and — composed once, above everything — the create/edit sheet.
 *
 * [links] carries the validated notification destinations from `MainActivity` (cold and warm start). Navigation
 * itself lives in the extension functions of `Routes.kt`.
 */
@Composable
fun AhoraRoot(
    links: Flow<LinkDestination>,
    mainViewModel: MainViewModel = hiltViewModel(),
    editor: EditorViewModel = hiltViewModel(),
) {
    val c = Ahora.colors
    val navController = rememberNavController()
    val snackbarHost = remember { SnackbarHostState() }
    val resources = LocalResources.current

    // One Snackbar at a time; a new message replaces the old one. Messages with Deshacer use the platform "Short"
    // duration so the action stays usable and accessibility timeouts apply (PRODUCT_SPEC §9.3, DD-8).
    LaunchedEffect(mainViewModel) {
        mainViewModel.messages.collect { message ->
            snackbarHost.currentSnackbarData?.dismiss()
            launch {
                val result = snackbarHost.showSnackbar(
                    message = resources.getString(message.textRes),
                    actionLabel = message.undo?.let { resources.getString(R.string.snack_undo) },
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) message.undo?.let(mainViewModel::undo)
            }
        }
    }

    // Notification body / ABRIR: close a half-typed sheet (it would otherwise hide the destination) and navigate.
    LaunchedEffect(navController) {
        links.collect { destination ->
            editor.dismiss()
            navController.applyLink(destination)
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showChrome = destination.isTab()

    Scaffold(
        containerColor = c.bg,
        contentColor = c.text,
        snackbarHost = {
            SnackbarHost(snackbarHost) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RectangleShape,
                    containerColor = c.text,
                    contentColor = c.bg,
                    actionColor = c.snackbarAction,
                )
            }
        },
        bottomBar = {
            if (showChrome) AhoraNavigationBar(current = destination, onSelect = navController::switchTab)
        },
        floatingActionButton = {
            // The sheet is modal: the FAB is hidden while it is open (PRODUCT_SPEC §3.2).
            if (showChrome && !editor.isOpen) {
                FloatingActionButton(
                    onClick = editor::openNew,
                    shape = RectangleShape,
                    containerColor = c.accent,
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp, pressedElevation = 1.dp),
                ) {
                    LucideIcon(R.drawable.ic_plus, stringResource(R.string.cd_new_task), tint = Color.White)
                }
            }
        },
    ) { padding ->
        // NFR-07: phone portrait is primary; wider windows keep a centred 640 dp column.
        Box(Modifier.fillMaxHeight().fillMaxWidth().padding(padding), contentAlignment = Alignment.TopCenter) {
            NavHost(
                navController = navController,
                startDestination = Home,
                modifier = Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth(),
                // M3 fade-through, ~300 ms (FOC-08); the system animator scale is honoured by the animation clock.
                enterTransition = { fadeIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS)) },
                exitTransition = { fadeOut(tween(FADE_OUT_MS)) },
                popEnterTransition = { fadeIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS)) },
                popExitTransition = { fadeOut(tween(FADE_OUT_MS)) },
            ) {
                composable<Home> {
                    HomeRoute(
                        navController = navController,
                        onOpenSettings = { navController.navigate(Settings) },
                        onStartFocus = navController::openFocus,
                        onEditTask = editor::openEdit,
                    )
                }
                composable<Inbox> {
                    InboxRoute(onOpenSettings = { navController.navigate(Settings) }, onEditTask = editor::openEdit)
                }
                composable<Tasks> {
                    TasksRoute(onOpenSettings = { navController.navigate(Settings) }, onEditTask = editor::openEdit)
                }
                composable<Focus> {
                    // Salir / system back: no changes. Terminar / Posponer / task gone: back to Hoy.
                    FocusRoute(onExit = { navController.popBackStack() }, onClose = navController::leaveFocusToHome)
                }
                composable<Settings> {
                    SettingsRoute(onBack = { navController.popBackStack() })
                }
            }
        }
    }

    EditorSheet(editor)
}

private data class TabSpec(val route: Any, val labelRes: Int, val iconRes: Int, val matches: (NavDestination?) -> Boolean)

private val Tabs = listOf(
    TabSpec(Home, R.string.nav_home, R.drawable.ic_home) { it?.hasRoute<Home>() == true },
    TabSpec(Inbox, R.string.nav_inbox, R.drawable.ic_inbox) { it?.hasRoute<Inbox>() == true },
    TabSpec(Tasks, R.string.nav_tasks, R.drawable.ic_check_square) { it?.hasRoute<Tasks>() == true },
)

/**
 * The three fixed destinations. Built on `selectable(Role.Tab)` rather than the M3 `NavigationBar` so the row is as
 * dense as the design (56 dp) with the 2 px rule on top and no indicator pill (PRODUCT_SPEC §9.3).
 */
@Composable
private fun AhoraNavigationBar(current: NavDestination?, onSelect: (Any) -> Unit) {
    val c = Ahora.colors
    Column(Modifier.fillMaxWidth().background(c.bg)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.divider))
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).selectableGroup()) {
            Tabs.forEach { tab ->
                val selected = tab.matches(current)
                val source = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .focusRing(source)
                        .selectable(
                            selected = selected,
                            interactionSource = source,
                            indication = null,
                            role = Role.Tab,
                            onClick = { if (!selected) onSelect(tab.route) },
                        )
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LucideIcon(
                        tab.iconRes,
                        contentDescription = null,
                        tint = if (selected) c.accent else c.textSecondary,
                    )
                    Text(
                        stringResource(tab.labelRes).uppercase(),
                        style = AhoraType.navLabel,
                        color = if (selected) c.accentText else c.textSecondary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}
