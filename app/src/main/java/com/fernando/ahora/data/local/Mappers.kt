package com.fernando.ahora.data.local

import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

internal fun TaskEntity.toDomain() = Task(
    id = id,
    title = title,
    notes = notes,
    dueDate = dueDate?.let(LocalDate::ofEpochDay),
    dueTime = dueTime?.let { LocalTime.ofSecondOfDay(it * 60L) },
    reminderEnabled = reminderEnabled,
    reminderFiredAt = reminderFiredAt?.let(Instant::ofEpochMilli),
    reminderSnoozeUntil = reminderSnoozeUntil?.let(Instant::ofEpochMilli),
    reminderRevision = reminderRevision,
    priority = Priority.fromValue(priority),
    recurrence = Recurrence.fromCode(recurrence),
    recurrenceAnchor = recurrenceAnchor?.let(LocalDate::ofEpochDay),
    estimatedMinutes = estimatedMinutes,
    listName = listName,
    done = done,
    completedAt = completedAt?.let(Instant::ofEpochMilli),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

internal fun Task.toEntity() = TaskEntity(
    id = id,
    title = title,
    notes = notes,
    dueDate = dueDate?.toEpochDay(),
    dueTime = dueTime?.let { it.hour * 60 + it.minute },
    reminderEnabled = reminderEnabled,
    reminderFiredAt = reminderFiredAt?.toEpochMilli(),
    reminderSnoozeUntil = reminderSnoozeUntil?.toEpochMilli(),
    reminderRevision = reminderRevision,
    priority = priority?.value,
    recurrence = recurrence.code,
    recurrenceAnchor = recurrenceAnchor?.toEpochDay(),
    estimatedMinutes = estimatedMinutes,
    listName = listName,
    done = done,
    completedAt = completedAt?.toEpochMilli(),
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
)
