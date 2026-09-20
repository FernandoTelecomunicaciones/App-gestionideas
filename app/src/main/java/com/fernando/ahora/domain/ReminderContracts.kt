package com.fernando.ahora.domain

import com.fernando.ahora.domain.model.Task
import java.time.Instant

/**
 * What the repository needs from the reminder engine. Implemented by the reconciler (M8).
 * Called AFTER a Room commit; awaiting it means "the alarm cursor is armed" (ARCHITECTURE R2-3).
 * The reconciler never calls a [TaskRepository] mutation, so awaiting cannot deadlock (§10.1 rule 3).
 */
interface ReminderSync {
    /** Cancel visible cards for [taskIds] (fast path) and re-plan the cursor. */
    suspend fun onTasksChanged(taskIds: Set<Long>, reason: String)

    /** After import: cancel the cursor and ALL reminder notifications, then re-plan. */
    suspend fun resetAll(reason: String)
}

/**
 * Trigger-free reminder bookkeeping used only by the reconciler and the notification action
 * receivers (ARCHITECTURE §10.3). Every write is conditional on [Task.reminderRevision] (R2-1).
 */
interface ReminderStore {
    suspend fun candidates(): List<Task>
    suspend fun get(taskId: Long): Task?

    /** True iff the row still had [revision] (and was open) so the mark applied. */
    suspend fun markDelivered(taskId: Long, revision: Int, now: Instant): Boolean

    /** Snoozes only a reminder that actually fired for this exact schedule. */
    suspend fun snoozeIfDelivered(taskId: Long, revision: Int, until: Instant): Boolean
}
