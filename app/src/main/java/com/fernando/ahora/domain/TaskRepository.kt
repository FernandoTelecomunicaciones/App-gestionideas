package com.fernando.ahora.domain

import com.fernando.ahora.domain.model.CompleteResult
import com.fernando.ahora.domain.model.EditResult
import com.fernando.ahora.domain.model.PostponeResult
import com.fernando.ahora.domain.model.ReplaceResult
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.model.TaskFields
import kotlinx.coroutines.flow.Flow

/**
 * The ONLY write path for tasks (ARCHITECTURE §4, R2-10). ViewModels depend on this interface;
 * the Room implementation lives in `data`. Reminder-affecting operations return only after the
 * alarm cursor has been re-planned (R2-3).
 */
interface TaskRepository {
    fun observePending(): Flow<List<Task>>
    fun observeCompleted(): Flow<List<Task>>
    fun observe(id: Long): Flow<Task?>
    suspend fun get(id: Long): Task?

    /** Returns the new id, or null when the title is blank. */
    suspend fun create(fields: TaskFields): Long?

    /** Merges only the editable fields; never resurrects, uncompletes or overwrites (R2-4). */
    suspend fun edit(id: Long, fields: TaskFields): EditResult

    /** Idempotent. [expectedRevision] guards notification actions against stale cards. */
    suspend fun complete(id: Long, expectedRevision: Int? = null): CompleteResult

    /**
     * Reopens the task and removes the generated successor. Guarded by the revision the completion produced, so
     * a replayed/obsolete token is a no-op (returns false); also a no-op if the generated successor is no longer
     * open (already completed or deleted), because reopening would then fork the series into two open branches.
     */
    suspend fun undoComplete(result: CompleteResult): Boolean

    /**
     * Reopens a completed task (from Completadas). Returns false if it was not completed, and ALWAYS false for an
     * occurrence of a recurring task: its successor already carries the series forward and there is no lineage to
     * find it, so a reopen could leave two open occurrences (GB-04). Undo right after completing is the way back.
     */
    suspend fun reopen(id: Long): Boolean

    /** Moves the task to tomorrow. Null if the task is missing or done. */
    suspend fun postpone(id: Long): PostponeResult?

    /** Restores the previous schedule only if the task was not touched since (R2-5). */
    suspend fun undoPostpone(result: PostponeResult): Boolean

    /** Returns the deleted task (for undo), or null if it did not exist. */
    suspend fun delete(id: Long): Task?

    /** Conflict-safe: if the id exists again (e.g. after an import) nothing is overwritten and this returns false. */
    suspend fun undoDelete(task: Task): Boolean

    suspend fun exportAll(): List<Task>

    /**
     * Atomic, validated replace (import). The WHOLE list is validated before Room is touched; any problem
     * rejects the import and leaves current data unchanged (R2-8). Every imported row receives a fresh
     * schedule revision so stale notification actions can never apply to it (GA-02).
     */
    suspend fun replaceAll(tasks: List<Task>): ReplaceResult
}
