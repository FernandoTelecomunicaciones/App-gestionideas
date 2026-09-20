package com.fernando.ahora.reminders

import android.util.Log
import com.fernando.ahora.core.time.TimeProvider
import com.fernando.ahora.di.ApplicationScope
import com.fernando.ahora.domain.ReminderStore
import com.fernando.ahora.domain.ReminderSync
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.rules.ReminderPlanner
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The reminder engine (ARCHITECTURE §10, §17). Room is the truth; AlarmManager is a rebuildable cache
 * with ONE cursor.
 *
 *  * **Non-reentrant / deadlock-free:** the reconciler only touches [ReminderStore] (trigger-free) and never
 *    a repository mutation. [request] launches on the app scope and is never awaited from inside a reconcile.
 *  * **Arm first, deliver second:** the cursor is persisted before any notification work, so a slow,
 *    failing or cancelled delivery can never strand future reminders (R2-2).
 *  * **Per-item, revision-guarded marking:** each delivered item is marked on its own with the revision it was
 *    read at; a schedule edited mid-flight is never marked consumed, and its just-posted card is cancelled (R2-1).
 *  * **Tray sweep:** visible cards are reconciled against Room (§10.4).
 */
@Singleton
class ReminderReconciler @Inject constructor(
    private val store: ReminderStore,
    private val scheduler: ReminderScheduler,
    private val notifier: ReminderNotifier,
    private val time: TimeProvider,
    @ApplicationScope private val appScope: CoroutineScope,
) : ReminderSync {

    private val mutex = Mutex()

    /** Consecutive transient failures per (task, revision); bounded so a poison item cannot retry forever. */
    private val transientFailures = HashMap<Pair<Long, Int>, Int>()

    /** Test seams. */
    internal var nanoClock: () -> Long = System::nanoTime
    internal var deliveryBudget: Duration = Duration.ofSeconds(6)

    private val requestLock = Any()
    private var queued: Job? = null

    /**
     * Fire-and-forget, CONFLATED trigger for callers that do not need to wait. While a pass is queued behind the
     * mutex, further requests share it (that pass reads Room only after it acquires the lock, so it sees their
     * changes); a request made while a pass is running queues exactly one follow-up. A storm therefore costs at
     * most two passes, never N (GB-05). Never call this and then block on it inside a reconcile.
     */
    fun request(reason: String): Job = synchronized(requestLock) {
        queued ?: appScope.launch(start = CoroutineStart.LAZY) {
            try {
                mutex.withLock {
                    synchronized(requestLock) { queued = null } // anything requested from here on needs a fresh pass
                    runPass(reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reconcile($reason) failed", e)
            }
        }.also { job ->
            queued = job
            job.invokeOnCompletion { synchronized(requestLock) { if (queued === job) queued = null } }
            job.start()
        }
    }

    override suspend fun onTasksChanged(taskIds: Set<Long>, reason: String) {
        taskIds.forEach { notifier.cancel(it) } // fast path; the sweep is the safety net
        reconcileNow(reason)
    }

    override suspend fun resetAll(reason: String) {
        scheduler.cancel()
        notifier.cancelAll()
        reconcileNow(reason)
    }

    private data class Fire(val task: Task, val at: Instant)

    /** Suspends until the cursor is armed and due items were processed (or the budget ran out). */
    suspend fun reconcileNow(reason: String) = mutex.withLock { runPass(reason) }

    /** Must run under [mutex]. */
    private suspend fun runPass(reason: String) {
        val startedAt = nanoClock()
        val now = time.now()
        val zone = time.zone()

        // Phase 1 — read + ARM. The read is cancellable; if it fails, stalls into the receiver timeout or the
        // arm itself throws, a short retry cursor is installed first so the alarm that just fired can never
        // leave pending work with no cursor (GB-02). The arm calls are non-cancellable and do no I/O.
        val plan = try {
            val fires = store.candidates().mapNotNull { t -> ReminderPlanner.nextFire(t, zone)?.let { Fire(t, it) } }
            val (due, future) = fires.partition { ReminderPlanner.isDue(it.at, now) }
            val next = future.minOfOrNull { it.at }
            withContext(NonCancellable) {
                if (due.isEmpty()) {
                    armOrCancel(next)
                } else {
                    // While undelivered work exists the cursor is a short retry; the real next cursor is set at the end.
                    val retry = now.plus(RETRY_DELAY)
                    scheduler.arm(if (next != null && next.isBefore(retry)) next else retry)
                }
            }
            Plan(due.sortedBy { it.at }, next)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                runCatching { scheduler.arm(now.plus(RETRY_DELAY)) }
                    .onFailure { Log.e(TAG, "could not install the emergency cursor", it) }
            }
            throw e
        }

        // Phase 2 — deliver, item by item, bounded by the budget.
        var unfinished = false
        for (item in plan.due) {
            if (!currentCoroutineContext().isActive || elapsed(startedAt) > deliveryBudget) {
                unfinished = true
                break
            }
            if (!deliver(item, now)) unfinished = true
        }

        // Phase 3 — settle the cursor FIRST (no I/O, non-cancellable), then sweep the tray (a safety net that may
        // be cut short by the receiver timeout without consequence).
        if (plan.due.isNotEmpty() && !unfinished) withContext(NonCancellable) { armOrCancel(plan.next) }
        try {
            sweep()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sweep failed", e)
        }
        Log.d(TAG, "reconcile($reason): due=${plan.due.size} unfinished=$unfinished next=${plan.next}")
    }

    private data class Plan(val due: List<Fire>, val next: Instant?)

    /** @return true when the item is finished (delivered, consumed or intentionally dropped). */
    private suspend fun deliver(item: Fire, now: Instant): Boolean {
        val task = item.task
        val key = task.id to task.reminderRevision
        val wantsDelivery = ReminderPlanner.shouldDeliver(item.at, now) && notifier.canNotify()
        var posted = false
        if (wantsDelivery) {
            try {
                notifier.post(task)
                posted = true
                transientFailures.remove(key)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: PermanentNotificationFailure) {
                Log.w(TAG, "permanent notification failure for task ${task.id}", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "notification not permitted for task ${task.id}", e)
            } catch (e: Exception) {
                val failures = (transientFailures[key] ?: 0) + 1
                if (failures < MAX_TRANSIENT_FAILURES) {
                    transientFailures[key] = failures
                    Log.w(TAG, "transient notification failure #$failures for task ${task.id}", e)
                    return false // leave unmarked; the retry cursor will pick it up
                }
                transientFailures.remove(key)
                Log.w(TAG, "giving up on task ${task.id} after $failures failures", e)
            }
        }
        currentCoroutineContext().ensureActive()
        val marked = withContext(NonCancellable) { store.markDelivered(task.id, task.reminderRevision, now) }
        if (posted && !marked) notifier.cancel(task.id) // the schedule changed underneath us: the card is stale
        return true
    }

    /** Keep a card only if it still matches Room exactly (ARCHITECTURE §10.4 sweep rule). */
    private suspend fun sweep() {
        for (card in notifier.activeCards()) {
            val t = store.get(card.taskId)
            val valid = t != null && !t.done && t.reminderEnabled && t.reminderFiredAt != null &&
                t.reminderSnoozeUntil == null && t.reminderRevision == card.revision
            if (!valid) notifier.cancel(card.taskId)
        }
    }

    private fun armOrCancel(next: Instant?) {
        if (next == null) scheduler.cancel() else scheduler.arm(next)
    }

    private fun elapsed(startNanos: Long): Duration = Duration.ofNanos(nanoClock() - startNanos)

    companion object {
        private const val TAG = "AhoraReminders"
        private val RETRY_DELAY: Duration = Duration.ofSeconds(60)
        private const val MAX_TRANSIENT_FAILURES = 3
    }
}
