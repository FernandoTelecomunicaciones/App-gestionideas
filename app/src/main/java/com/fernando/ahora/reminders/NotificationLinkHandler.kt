package com.fernando.ahora.reminders

import com.fernando.ahora.domain.TaskRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Where a notification entry point should take the user. The navigation host consumes this (M3). */
sealed interface LinkDestination {
    data object Home : LinkDestination
    data class Focus(val taskId: Long) : LinkDestination
}

/**
 * Turns an intent that entered the (exported) activity into a validated destination, cold or warm start
 * (ARCHITECTURE §8, D-13). Any app can send an explicit intent to the activity, so nothing is trusted:
 *  * unrecognised / malformed links resolve to nothing;
 *  * a Foco link for a task that is missing or already done resolves to Hoy;
 *  * opening Foco from ABRIR dismisses that card (action buttons do not auto-cancel) — but only the card of the
 *    revision the button belonged to, never a newer one (same rule as GB-01).
 */
@Singleton
class NotificationLinkHandler @Inject constructor(
    private val repository: TaskRepository,
    private val notifier: ReminderNotifier,
) {
    suspend fun resolve(link: AppLink?): LinkDestination? = when (link) {
        null -> null
        AppLink.Home -> LinkDestination.Home
        is AppLink.Focus -> {
            val task = repository.get(link.taskId)
            if (task == null || task.done) {
                LinkDestination.Home
            } else {
                val revision = link.revision
                if (revision != null &&
                    notifier.activeCards().any { it.taskId == task.id && it.revision == revision }
                ) {
                    notifier.cancel(task.id)
                }
                LinkDestination.Focus(task.id)
            }
        }
    }
}
