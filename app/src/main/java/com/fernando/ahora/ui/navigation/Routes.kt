package com.fernando.ahora.ui.navigation

import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import com.fernando.ahora.reminders.LinkDestination
import kotlinx.serialization.Serializable

/** Type-safe routes (ARCHITECTURE §8). Edit/create is a sheet, never a destination. */
@Serializable data object Home
@Serializable data object Inbox
@Serializable data object Tasks
@Serializable data class Focus(val taskId: Long)
@Serializable data object Settings

/** True for the three bottom-bar destinations (chrome visibility, PRODUCT_SPEC §3.2). */
fun NavDestination?.isTab(): Boolean =
    this != null && (hasRoute<Home>() || hasRoute<Inbox>() || hasRoute<Tasks>())

/**
 * Bottom-bar switching: Hoy is the root of the stack, so going there pops back to it (its "También pendiente"
 * collapses when it leaves the screen — see HomeViewModel); Bandeja/Tareas replace each other above it, keeping their
 * own scroll state. System back from either lands on Hoy (PRODUCT_SPEC §3.3).
 */
fun NavController.switchTab(route: Any) {
    if (route == Home) {
        if (!popBackStack<Home>(inclusive = false)) navigate(Home) { popUpTo(graph.id) { inclusive = true } }
        return
    }
    navigate(route) {
        popUpTo<Home> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Empezar (Hoy → Foco): plain push, the back stack becomes [Home, Focus]. */
fun NavController.openFocus(taskId: Long) {
    navigate(Focus(taskId)) { launchSingleTop = true }
}

/** Terminar / Posponer / task gone: back to Hoy from Foco. Idempotent (a second call is a no-op). */
fun NavController.leaveFocusToHome() {
    if (currentDestination?.hasRoute<Focus>() != true) return
    if (!popBackStack<Home>(inclusive = false)) navigate(Home) { popUpTo(graph.id) { inclusive = true } }
}

/**
 * Applies a validated notification destination for cold AND warm starts (ARCHITECTURE §8 / §17 R2-11):
 *  * body tap → Hoy, clearing everything above it;
 *  * ABRIR → Foco with the back stack `[Home, Focus]`, from wherever the user is (another tab, Ajustes, another Foco).
 * Re-delivering the Foco that is already on screen changes nothing (the running timer is not reset).
 */
fun NavController.applyLink(destination: LinkDestination) {
    when (destination) {
        LinkDestination.Home -> switchTab(Home)
        is LinkDestination.Focus -> {
            val current = currentBackStackEntry
            val alreadyThere = current?.destination?.hasRoute<Focus>() == true &&
                runCatching { current.toRoute<Focus>().taskId }.getOrNull() == destination.taskId
            if (alreadyThere) return
            if (!popBackStack<Home>(inclusive = false)) navigate(Home) { popUpTo(graph.id) { inclusive = true } }
            navigate(Focus(destination.taskId)) { launchSingleTop = true }
        }
    }
}
