package com.fernando.ahora.ui.editor

import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.domain.rules.TaskRules
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * The sheet's working copy. `@Serializable` with primitive fields so it survives rotation and process death inside
 * a `SavedStateHandle` (EDT-11). The transitions below only keep the UI coherent (PRODUCT_SPEC §4.1 "UI
 * consequence"); the repository re-normalises everything on save, so they are not a second source of truth.
 */
@Serializable
data class EditorDraft(
    /** null ⇒ a new task. */
    val taskId: Long? = null,
    val title: String = "",
    val notes: String = "",
    val dueDateEpochDay: Long? = null,
    val dueTimeMinute: Int? = null,
    val reminder: Boolean = false,
    val priority: Int? = null,
    val recurrence: String = Recurrence.NONE.code,
    val estimatedMinutes: Int? = null,
    val list: String = "",
    val moreOpen: Boolean = false,
) {
    val isNew: Boolean get() = taskId == null
    val dueDate: LocalDate? get() = dueDateEpochDay?.let(LocalDate::ofEpochDay)
    val dueTime: LocalTime? get() = dueTimeMinute?.let { LocalTime.of(it / 60, it % 60) }
    val priorityValue: Priority? get() = Priority.fromValue(priority)
    val recurrenceValue: Recurrence get() = Recurrence.fromCode(recurrence)

    /** The only requirement to save (P-2): a title. Nothing optional can block it. */
    val canSave: Boolean get() = title.isNotBlank()

    // Typing stops at the same limits the backup file accepts (GC-09), so an export always re-imports.
    fun withTitle(value: String) = copy(title = value.take(TaskRules.MAX_TITLE))
    fun withNotes(value: String) = copy(notes = value.take(TaskRules.MAX_NOTES))
    fun withList(value: String) = copy(list = value.take(TaskRules.MAX_LIST))
    fun withPriority(value: Priority?) = copy(priority = value?.value)
    /** Repeat needs a date (§4.1); without one only "no repeat" is accepted. */
    fun withRecurrence(value: Recurrence) =
        if (dueDateEpochDay == null && value != Recurrence.NONE) this else copy(recurrence = value.code)
    fun withEstimate(minutes: Int?) = copy(estimatedMinutes = minutes)
    fun toggleMore() = copy(moreOpen = !moreOpen)
    fun withReminder(on: Boolean) = if (dueTimeMinute == null) this else copy(reminder = on)

    /** Clearing the date clears time, reminder and recurrence (§4.1). */
    fun withDate(date: LocalDate?): EditorDraft =
        if (date == null) {
            copy(dueDateEpochDay = null, dueTimeMinute = null, reminder = false, recurrence = Recurrence.NONE.code)
        } else {
            copy(dueDateEpochDay = date.toEpochDay())
        }

    /**
     * Picking a time with no date auto-selects the date: Hoy, or Mañana if that time has already passed today (§4.1).
     * Clearing the time clears the reminder.
     */
    fun withTime(time: LocalTime?, today: LocalDate, now: LocalTime): EditorDraft {
        if (time == null) return copy(dueTimeMinute = null, reminder = false)
        val minute = time.hour * 60 + time.minute
        val date = dueDate ?: if (time.isAfter(now.withSecond(0).withNano(0))) today else today.plusDays(1)
        return copy(dueDateEpochDay = date.toEpochDay(), dueTimeMinute = minute)
    }

    /** EDT-05 helper line: the chosen instant is not in the future, so no notification will come. */
    fun reminderIsInPast(now: Instant, zone: ZoneId): Boolean {
        if (!reminder) return false
        val date = dueDate ?: return false
        val time = dueTime ?: return false
        return LocalDateTime.of(date, time).atZone(zone).toInstant() <= now
    }

    fun toFields() = TaskFields(
        title = title,
        notes = notes,
        dueDate = dueDate,
        dueTime = dueTime,
        reminderEnabled = reminder,
        priority = priorityValue,
        recurrence = recurrenceValue,
        estimatedMinutes = estimatedMinutes,
        listName = list.ifBlank { null },
    )

    companion object {
        fun of(task: Task) = EditorDraft(
            taskId = task.id,
            title = task.title,
            notes = task.notes,
            dueDateEpochDay = task.dueDate?.toEpochDay(),
            dueTimeMinute = task.dueTime?.let { it.hour * 60 + it.minute },
            reminder = task.reminderEnabled,
            priority = task.priority?.value,
            recurrence = task.recurrence.code,
            estimatedMinutes = task.estimatedMinutes,
            list = task.listName.orEmpty(),
            // Advanced options that already hold a value are shown, so nothing is hidden from the user.
            moreOpen = task.recurrence != Recurrence.NONE || task.estimatedMinutes != null || !task.listName.isNullOrBlank(),
        )
    }
}
