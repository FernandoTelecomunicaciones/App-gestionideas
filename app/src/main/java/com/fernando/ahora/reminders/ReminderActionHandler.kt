package com.fernando.ahora.reminders

import com.fernando.ahora.core.time.TimeProvider
import com.fernando.ahora.domain.ReminderStore
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.domain.rules.ReminderPlanner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HECHO and +10 MIN, testable without a BroadcastReceiver. Both are idempotent, both validate the schedule
 * revision the card was posted for (a stale card can never overwrite a newer schedule), and both ALWAYS
 * dismiss the card (PRODUCT_SPEC NTF-08). Callers await this inside `goAsync()` (ARCHITECTURE R2-3).
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
        repository.complete(taskId, expectedRevision = revision)
        notifier.cancel(taskId)
    }

    /** Snoozes only a reminder that actually fired for this exact schedule; then re-plans. */
    suspend fun snooze(taskId: Long, revision: Int) {
        store.snoozeIfDelivered(taskId, revision, time.now().plus(ReminderPlanner.SNOOZE))
        notifier.cancel(taskId)
        reconciler.reconcileNow("snooze")
    }
}
