package com.fernando.ahora.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class Priority(val value: Int) {
    P1(1), P2(2), P3(3);

    companion object {
        fun fromValue(value: Int?): Priority? = entries.firstOrNull { it.value == value }
    }
}

enum class Recurrence(val code: String) {
    NONE("none"), DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly");

    companion object {
        fun fromCode(code: String?): Recurrence = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** A task. An "idea" is just a task with no date and no priority (see [isInbox]). */
data class Task(
    val id: Long,
    val title: String,
    val notes: String,
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val reminderEnabled: Boolean,
    /** Non-null ⇒ the due-time reminder for the *current* schedule is consumed. */
    val reminderFiredAt: Instant?,
    val reminderSnoozeUntil: Instant?,
    /** Incremented on every change that affects scheduling (ARCHITECTURE R2-1). */
    val reminderRevision: Int,
    val priority: Priority?,
    val recurrence: Recurrence,
    val recurrenceAnchor: LocalDate?,
    val estimatedMinutes: Int?,
    val listName: String?,
    val done: Boolean,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Derived, never stored (D-14). */
    val isInbox: Boolean get() = !done && dueDate == null && priority == null

    /** A reminder is *defined* when it can ever fire (PRODUCT_SPEC §4.1). */
    val hasDefinedReminder: Boolean
        get() = !done && reminderEnabled && dueDate != null && dueTime != null
}

/** The user-editable part of a task. Everything else is bookkeeping owned by the repository. */
data class TaskFields(
    val title: String,
    val notes: String = "",
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val reminderEnabled: Boolean = false,
    val priority: Priority? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    val estimatedMinutes: Int? = null,
    val listName: String? = null,
) {
    companion object {
        fun of(task: Task) = TaskFields(
            title = task.title,
            notes = task.notes,
            dueDate = task.dueDate,
            dueTime = task.dueTime,
            reminderEnabled = task.reminderEnabled,
            priority = task.priority,
            recurrence = task.recurrence,
            estimatedMinutes = task.estimatedMinutes,
            listName = task.listName,
        )
    }
}

sealed interface EditResult {
    data class Saved(val task: Task) : EditResult
    data object NotFound : EditResult
    data object AlreadyCompleted : EditResult
    data object InvalidTitle : EditResult
}

/**
 * [completed] is false when the call was a no-op (already done, missing, or stale revision).
 * [revisionAfter] guards [com.fernando.ahora.domain.TaskRepository.undoComplete] against replay (GA-04).
 */
data class CompleteResult(
    val taskId: Long,
    val completed: Boolean,
    val successorId: Long?,
    val revisionAfter: Int = -1,
)

sealed interface ReplaceResult {
    data class Replaced(val count: Int) : ReplaceResult

    /** Nothing was written; [problems] are human-readable for logs/diagnostics. */
    data class Invalid(val problems: List<String>) : ReplaceResult
}

data class SchedulePatch(
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val reminderEnabled: Boolean,
    val reminderFiredAt: Instant?,
    val reminderSnoozeUntil: Instant?,
)

/** Everything needed to roll a postpone back (ARCHITECTURE R2-5). */
data class PostponeResult(val taskId: Long, val previous: SchedulePatch, val revisionAfter: Int)
