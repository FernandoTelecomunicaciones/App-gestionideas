package com.fernando.ahora.testing

import com.fernando.ahora.core.time.TimeProvider
import com.fernando.ahora.domain.ReminderSync
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class FakeTimeProvider(
    var instant: Instant = Instant.parse("2026-09-20T10:00:00Z"),
    var zoneId: ZoneId = ZoneId.of("Europe/Madrid"),
) : TimeProvider {
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
}

/** Records what the repository asked the reminder engine to do. */
class RecordingReminderSync : ReminderSync {
    val changed = mutableListOf<Pair<Set<Long>, String>>()
    val resets = mutableListOf<String>()

    override suspend fun onTasksChanged(taskIds: Set<Long>, reason: String) {
        changed += taskIds to reason
    }

    override suspend fun resetAll(reason: String) {
        resets += reason
    }
}

fun aTask(
    id: Long,
    title: String = "task $id",
    dueDate: LocalDate? = null,
    dueTime: LocalTime? = null,
    priority: Priority? = null,
    done: Boolean = false,
    notes: String = "",
    reminderEnabled: Boolean = false,
    recurrence: Recurrence = Recurrence.NONE,
    createdAt: Instant = Instant.ofEpochSecond(1_700_000_000L + id),
) = Task(
    id = id,
    title = title,
    notes = notes,
    dueDate = dueDate,
    dueTime = dueTime,
    reminderEnabled = reminderEnabled,
    reminderFiredAt = null,
    reminderSnoozeUntil = null,
    reminderRevision = 0,
    priority = priority,
    recurrence = recurrence,
    recurrenceAnchor = if (recurrence != Recurrence.NONE) dueDate else null,
    estimatedMinutes = null,
    listName = null,
    done = done,
    completedAt = if (done) createdAt else null,
    createdAt = createdAt,
    updatedAt = createdAt,
)
