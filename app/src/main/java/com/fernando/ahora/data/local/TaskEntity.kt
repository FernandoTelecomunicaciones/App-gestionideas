package com.fernando.ahora.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Table `tasks` (ARCHITECTURE §6.1). Dates/times are FLOATING local values (epoch-day, minute-of-day);
 * only `reminderFiredAt`/`reminderSnoozeUntil`/timestamps are absolute instants (D-05).
 * Invariants are enforced by the repository, not by SQL. SQL defaults exist so migrations, restores and
 * raw inserts that omit a column still satisfy NOT NULL (GA-07).
 */
@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["done", "dueDate"]),
        Index(value = ["done", "priority"]),
    ],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    @ColumnInfo(defaultValue = "''") val notes: String,
    val dueDate: Long?,
    val dueTime: Int?,
    @ColumnInfo(defaultValue = "0") val reminderEnabled: Boolean,
    val reminderFiredAt: Long?,
    val reminderSnoozeUntil: Long?,
    @ColumnInfo(defaultValue = "0") val reminderRevision: Int,
    val priority: Int?,
    @ColumnInfo(defaultValue = "'none'") val recurrence: String,
    val recurrenceAnchor: Long?,
    val estimatedMinutes: Int?,
    val listName: String?,
    @ColumnInfo(defaultValue = "0") val done: Boolean,
    val completedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)
