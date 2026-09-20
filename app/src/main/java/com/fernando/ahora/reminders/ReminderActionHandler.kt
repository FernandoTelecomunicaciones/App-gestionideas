package com.fernando.ahora.reminders

import com.fernando.ahora.core.time.TimeProvider
import com.fernando.ahora.domain.ReminderStore
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.domain.rules.ReminderPlanner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HECHO and +10 MIN, testable without a BroadcastReceiver. Both are idempotent and both validate the schedule
 * revision the card was posted for, so a stale card can never overwrite a newer schedule.
 *
 * A no-op action (stale, duplicate, task gone) dismisses only ITS OWN card — the one in the tray whose
 * revision equals the action's. It must never cancel by task id alone: a delayed action from revision N
 * could otherwise remove the valid card of revision N+1, which Room already records as delivered and which
 * therefore could never be posted again (GB-01). The remaining check-then-cancel window is microseconds,
 * and the reconcile sweep after each action heals anything left over.
 *
 * Callers await this inside `goAsync()` (ARCHITECTURE R2-3).
 */
@Singleton
class ReminderActionHandler @Inject constructor(
    private val repository: TaskRepository,
    private val store: ReminderStore,
    private val notifier: ReminderNotifier,
    private val reconciler: ReminderReconciler,
    private val time: TimeProvider,
) {
    /** Completes only if the task is open AND still at [revision]; the repository awaits the re-plan. */
    suspend fun done(taskId: Long, revision: Int) {
        val result = repository.complete(taskId, expectedRevision = revision)
        if (result.completed) {
            notifier.cancel(taskId) // the repository already cancelled it; harmless and explicit
        } else {
            dismissOwnCard(taskId, revision)
            reconciler.reconcileNow("action-noop")
        }
    }

    /** Snoozes only a reminder that fired for this exact schedule and is not already snoozed; then re-plans. */
    suspend fun snooze(taskId: Long, revision: Int) {
        val snoozed = store.snoozeIfDelivered(taskId, revision, time.now().plus(ReminderPlanner.SNOOZE))
        if (snoozed) notifier.cancel(taskId) else dismissOwnCard(taskId, revision)
        reconciler.reconcileNow(if (snoozed) "snooze" else "action-noop")
    }

    private fun dismissOwnCard(taskId: Long, revision: Int) {
        if (notifier.activeCards().any { it.taskId == taskId && it.revision == revision }) notifier.cancel(taskId)
    }
}
