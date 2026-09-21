package com.fernando.ahora.data

import android.util.Log
import androidx.room.withTransaction
import com.fernando.ahora.core.time.TimeProvider
import com.fernando.ahora.data.local.AhoraDatabase
import com.fernando.ahora.data.local.TaskDao
import com.fernando.ahora.data.local.toDomain
import com.fernando.ahora.data.local.toEntity
import com.fernando.ahora.domain.ReminderSync
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.domain.model.CompleteResult
import com.fernando.ahora.domain.model.EditResult
import com.fernando.ahora.domain.model.PostponeResult
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.ReplaceResult
import com.fernando.ahora.domain.model.SchedulePatch
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.domain.rules.RecurrenceCalculator
import com.fernando.ahora.domain.rules.ReminderPlanner
import com.fernando.ahora.domain.rules.TaskRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [TaskRepository]. Every operation that touches related state runs in ONE transaction;
 * reminder re-planning happens AFTER commit and is awaited (ARCHITECTURE R2-3). Nothing in here is
 * ever called from inside a reconcile, so awaiting [ReminderSync] cannot deadlock.
 */
@Singleton
internal class TaskRepositoryImpl @Inject constructor(
    private val db: AhoraDatabase,
    private val dao: TaskDao,
    private val time: TimeProvider,
    private val reminderSync: ReminderSync,
) : TaskRepository {

    /** ONE consistent view of time per operation: `now`, then `zone`, then `today` derived from both (GA-11). */
    private class Clock(val now: Instant, val zone: ZoneId) {
        val today: LocalDate = now.atZone(zone).toLocalDate()
    }

    private fun clock() = Clock(time.now(), time.zone())

    /**
     * Re-plan the alarm cursor AFTER the commit. Room is the truth and the alarm is a rebuildable cache, so a re-plan
     * that fails (both AlarmManager calls throwing, a failed read) must not turn a committed write into a "failed
     * save": the caller would show an error for data that exists and lose its Undo token. The next receiver / app
     * start reconciles (ARCHITECTURE §10.4). Cancellation still propagates (GC-05).
     */
    private suspend fun replan(taskIds: Set<Long>, reason: String) {
        try {
            reminderSync.onTasksChanged(taskIds, reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "re-plan after $reason failed; the next reconcile heals it", e)
        }
    }

    private suspend fun resetReminders(reason: String) {
        try {
            reminderSync.resetAll(reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "reminder reset after $reason failed; the next reconcile heals it", e)
        }
    }

    override fun observePending(): Flow<List<Task>> = dao.observePending().map { rows -> rows.map { it.toDomain() } }
    override fun observeCompleted(): Flow<List<Task>> = dao.observeCompleted().map { rows -> rows.map { it.toDomain() } }
    override fun observe(id: Long): Flow<Task?> = dao.observeById(id).map { it?.toDomain() }
    override suspend fun get(id: Long): Task? = dao.getById(id)?.toDomain()

    override suspend fun create(fields: TaskFields): Long? {
        val f = TaskRules.normalize(fields) ?: return null
        val c = clock()
        val id = db.withTransaction { dao.insert(newTask(f, c).toEntity()) }
        replan(setOf(id), "create")
        return id
    }

    override suspend fun edit(id: Long, fields: TaskFields): EditResult {
        val f = TaskRules.normalize(fields) ?: return EditResult.InvalidTitle
        val c = clock()

        var scheduleChanged = false
        val result: EditResult = db.withTransaction {
            val row = dao.getById(id)?.toDomain() ?: return@withTransaction EditResult.NotFound
            if (row.done) return@withTransaction EditResult.AlreadyCompleted

            scheduleChanged = row.dueDate != f.dueDate || row.dueTime != f.dueTime ||
                row.reminderEnabled != f.reminderEnabled

            // R2-6: anchor lifecycle. none <=> null; (re)initialised when the rule or date changes.
            val anchor = when {
                f.recurrence == Recurrence.NONE -> null
                row.recurrence != f.recurrence || row.dueDate != f.dueDate || row.recurrenceAnchor == null -> f.dueDate
                else -> row.recurrenceAnchor
            }

            var updated = row.copy(
                title = f.title,
                notes = f.notes,
                dueDate = f.dueDate,
                dueTime = f.dueTime,
                reminderEnabled = f.reminderEnabled,
                priority = f.priority,
                recurrence = f.recurrence,
                recurrenceAnchor = anchor,
                estimatedMinutes = f.estimatedMinutes,
                listName = f.listName,
                updatedAt = c.now,
            )
            if (scheduleChanged) {
                updated = ReminderPlanner.consumeIfPast(
                    updated.copy(
                        reminderFiredAt = null,
                        reminderSnoozeUntil = null,
                        reminderRevision = row.reminderRevision + 1,
                    ),
                    c.zone,
                    c.now,
                )
            }
            dao.update(updated.toEntity())
            EditResult.Saved(updated)
        }
        if (result is EditResult.Saved && scheduleChanged) replan(setOf(id), "edit")
        return result
    }

    override suspend fun complete(id: Long, expectedRevision: Int?): CompleteResult {
        val c = clock()
        val result = db.withTransaction {
            val row = dao.getById(id)?.toDomain()
            if (row == null || row.done || (expectedRevision != null && row.reminderRevision != expectedRevision)) {
                return@withTransaction CompleteResult(id, completed = false, successorId = null)
            }
            val revisionAfter = row.reminderRevision + 1
            dao.update(
                row.copy(
                    done = true,
                    completedAt = c.now,
                    reminderSnoozeUntil = null,
                    reminderRevision = revisionAfter,
                    updatedAt = c.now,
                ).toEntity(),
            )
            val successorId = if (row.recurrence != Recurrence.NONE) {
                dao.insert(successorOf(row, c).toEntity())
            } else {
                null
            }
            CompleteResult(id, completed = true, successorId = successorId, revisionAfter = revisionAfter)
        }
        if (result.completed) replan(setOfNotNull(id, result.successorId), "complete")
        return result
    }

    override suspend fun undoComplete(result: CompleteResult): Boolean {
        if (!result.completed) return false
        val c = clock()
        val undone = db.withTransaction {
            val row = dao.getById(result.taskId)?.toDomain()
            // GA-04: only the exact completion this token describes may be undone (no replay, no stale token).
            if (row == null || !row.done || row.reminderRevision != result.revisionAfter) {
                return@withTransaction false
            }
            // GB-04: the undo removes the occurrence it generated. If that successor was already completed (or
            // deleted), the series has moved on; reopening this occurrence would fork it into two open branches.
            val successorId = result.successorId
            if (successorId != null) {
                val successor = dao.getById(successorId)
                if (successor == null || successor.done) return@withTransaction false
                // GC-06: an occurrence the user has edited since it was generated carries their work; the undo
                // must not delete it (same outcome as "the series has moved on").
                if (successor.updatedAt != successor.createdAt) return@withTransaction false
                dao.deleteById(successorId)
            }
            reopenInTransaction(result.taskId, c)
            true
        }
        if (undone) replan(setOfNotNull(result.taskId, result.successorId), "undoComplete")
        return undone
    }

    override suspend fun reopen(id: Long): Boolean {
        val c = clock()
        val reopened = db.withTransaction {
            // GB-04: a completed occurrence of a recurring task has (or had) a successor and there is no series
            // lineage to find it, so reopening could leave two open occurrences. History is immutable; the way
            // back is `undoComplete`, which removes the successor it created.
            val row = dao.getById(id)
            if (row != null && Recurrence.fromCode(row.recurrence) != Recurrence.NONE) return@withTransaction false
            reopenInTransaction(id, c)
        }
        if (reopened) replan(setOf(id), "reopen")
        return reopened
    }

    override suspend fun postpone(id: Long): PostponeResult? {
        val c = clock()
        val tomorrow = c.today.plusDays(1)
        val result = db.withTransaction {
            val row = dao.getById(id)?.toDomain()?.takeIf { !it.done } ?: return@withTransaction null
            val previous = SchedulePatch(
                dueDate = row.dueDate,
                dueTime = row.dueTime,
                reminderEnabled = row.reminderEnabled,
                reminderFiredAt = row.reminderFiredAt,
                reminderSnoozeUntil = row.reminderSnoozeUntil,
            )
            val updated = row.copy(
                dueDate = tomorrow,
                reminderFiredAt = null,
                reminderSnoozeUntil = null,
                reminderRevision = row.reminderRevision + 1,
                updatedAt = c.now,
            )
            dao.update(updated.toEntity())
            PostponeResult(id, previous, updated.reminderRevision)
        }
        if (result != null) replan(setOf(id), "postpone")
        return result
    }

    override suspend fun undoPostpone(result: PostponeResult): Boolean {
        val c = clock()
        val restored = db.withTransaction {
            val row = dao.getById(result.taskId)?.toDomain() ?: return@withTransaction false
            // Guard: only if untouched since the postpone (so we never overwrite a later edit).
            if (row.done || row.reminderRevision != result.revisionAfter) return@withTransaction false
            val p = result.previous
            val updated = ReminderPlanner.consumeIfPast(
                row.copy(
                    dueDate = p.dueDate,
                    dueTime = p.dueTime,
                    reminderEnabled = p.reminderEnabled,
                    reminderFiredAt = p.reminderFiredAt,
                    reminderSnoozeUntil = p.reminderSnoozeUntil,
                    reminderRevision = row.reminderRevision + 1,
                    updatedAt = c.now,
                ),
                c.zone,
                c.now,
            )
            dao.update(updated.toEntity())
            true
        }
        if (restored) replan(setOf(result.taskId), "undoPostpone")
        return restored
    }

    override suspend fun delete(id: Long): Task? {
        val removed = db.withTransaction {
            val row = dao.getById(id)?.toDomain() ?: return@withTransaction null
            dao.deleteById(id)
            row
        }
        if (removed != null) replan(setOf(id), "delete")
        return removed
    }

    override suspend fun undoDelete(task: Task): Boolean {
        val c = clock()
        val restored = ReminderPlanner.consumeIfPast(
            task.copy(reminderRevision = task.reminderRevision + 1, updatedAt = c.now),
            c.zone,
            c.now,
        )
        // GA-05: never REPLACE. If the id exists again (import, prior restore + edit) this is a no-op.
        val inserted = db.withTransaction { dao.insertIgnore(restored.toEntity()) != -1L }
        if (inserted) replan(setOf(task.id), "undoDelete")
        return inserted
    }

    override suspend fun exportAll(): List<Task> = dao.getAll().map { it.toDomain() }

    override suspend fun replaceAll(tasks: List<Task>): ReplaceResult {
        // GA-03: validate EVERYTHING before touching Room; reject the whole import on any problem.
        val problems = TaskRules.validateForImport(tasks)
        if (problems.isNotEmpty()) return ReplaceResult.Invalid(problems)

        db.withTransaction {
            // GA-02: every imported row gets a revision that equals neither its own nor the displaced row's,
            // so a stale notification action for a reused id can never apply to the imported task.
            val oldRevisions = dao.getAll().associate { it.id to it.reminderRevision }
            dao.deleteAll()
            dao.insertAll(
                tasks.map { t ->
                    // validateForImport bounds imported revisions (GB-03), so this never wraps.
                    val base = maxOf(t.reminderRevision, oldRevisions[t.id] ?: -1)
                    t.copy(reminderRevision = base + 1).toEntity()
                },
            )
        }
        resetReminders("import")
        return ReplaceResult.Replaced(tasks.size)
    }

    // --- helpers (all pure or transaction-scoped) ---------------------------------------------

    private fun newTask(f: TaskFields, c: Clock): Task = ReminderPlanner.consumeIfPast(
        Task(
            id = 0,
            title = f.title,
            notes = f.notes,
            dueDate = f.dueDate,
            dueTime = f.dueTime,
            reminderEnabled = f.reminderEnabled,
            reminderFiredAt = null,
            reminderSnoozeUntil = null,
            reminderRevision = 0,
            priority = f.priority,
            recurrence = f.recurrence,
            recurrenceAnchor = if (f.recurrence != Recurrence.NONE) f.dueDate else null,
            estimatedMinutes = f.estimatedMinutes,
            listName = f.listName,
            done = false,
            completedAt = null,
            createdAt = c.now,
            updatedAt = c.now,
        ),
        c.zone,
        c.now,
    )

    private fun successorOf(row: Task, c: Clock): Task {
        val due = row.dueDate ?: c.today
        val anchor = row.recurrenceAnchor ?: due
        val nextDate = RecurrenceCalculator.next(anchor, due, row.recurrence, c.today) ?: due
        return ReminderPlanner.consumeIfPast(
            row.copy(
                id = 0,
                dueDate = nextDate,
                recurrenceAnchor = anchor,
                reminderFiredAt = null,
                reminderSnoozeUntil = null,
                reminderRevision = 0,
                done = false,
                completedAt = null,
                createdAt = c.now,
                updatedAt = c.now,
            ),
            c.zone,
            c.now,
        )
    }

    /** Must run inside a transaction. */
    private suspend fun reopenInTransaction(id: Long, c: Clock): Boolean {
        val row = dao.getById(id)?.toDomain()?.takeIf { it.done } ?: return false
        val updated = ReminderPlanner.consumeIfPast(
            row.copy(
                done = false,
                completedAt = null,
                reminderFiredAt = null,
                reminderSnoozeUntil = null,
                reminderRevision = row.reminderRevision + 1,
                updatedAt = c.now,
            ),
            c.zone,
            c.now,
        )
        dao.update(updated.toEntity())
        return true
    }

    private companion object {
        const val TAG = "AhoraRepository"
    }
}
