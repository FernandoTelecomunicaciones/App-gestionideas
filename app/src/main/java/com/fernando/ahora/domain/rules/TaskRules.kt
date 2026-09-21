package com.fernando.ahora.domain.rules

import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.model.TaskFields
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** PRODUCT_SPEC §4.1 invariants, enforced in exactly one place. */
object TaskRules {
    /** The only estimates the editor offers (PRODUCT_SPEC §4). */
    val ALLOWED_ESTIMATES: Set<Int> = setOf(15, 30, 60, 120)

    /**
     * Upper bound for an imported schedule revision. Revisions grow by one per schedule edit, so a real export
     * never comes near this; the bound keeps `revision + 1` (import, GA-02) from ever wrapping to a value a
     * stale notification action could match (GB-03).
     */
    const val MAX_IMPORT_REVISION: Int = 1_000_000_000

    /**
     * Text limits shared by every write path AND the backup file (GC-09): whatever the app lets the user save, its own
     * export can carry and its own import accepts. The editor stops typing at these; [normalize] is the backstop.
     */
    const val MAX_TITLE = 1_000
    const val MAX_NOTES = 20_000
    const val MAX_LIST = 200

    /**
     * The supported calendar range for `dueDate` / `recurrenceAnchor` (GC-04). It contains the editor's date picker
     * (1900–2100). Beyond it `java.time` still parses but the engine cannot follow: `Instant.toEpochMilli()` overflows
     * when arming an alarm and `plusDays` overflows when a recurring task is completed.
     */
    val MIN_DATE: LocalDate = LocalDate.of(1900, 1, 1)
    val MAX_DATE: LocalDate = LocalDate.of(2200, 12, 31)

    fun isSupportedDate(date: LocalDate): Boolean = date >= MIN_DATE && date <= MAX_DATE

    /**
     * Returns the normalised fields, or null when the title is blank (the only hard requirement).
     * Dependent fields are cleared rather than rejected: time requires a date, a reminder requires a
     * time, recurrence requires a date. Optional fields never produce errors.
     * The time is canonicalised to minute precision BEFORE any reminder maths (GA-06).
     */
    fun normalize(fields: TaskFields): TaskFields? {
        val title = fields.title.trim().take(MAX_TITLE).trimEnd()
        if (title.isEmpty()) return null

        // An unsupported date cannot be represented by the engine; dropping it also drops its dependents below.
        val date = fields.dueDate?.takeIf(::isSupportedDate)
        val time = if (date == null) null else fields.dueTime?.truncatedTo(ChronoUnit.MINUTES)
        val reminder = time != null && fields.reminderEnabled
        val recurrence = if (date == null) Recurrence.NONE else fields.recurrence

        return fields.copy(
            title = title,
            notes = fields.notes.take(MAX_NOTES),
            dueDate = date,
            dueTime = time,
            reminderEnabled = reminder,
            recurrence = recurrence,
            estimatedMinutes = fields.estimatedMinutes?.takeIf { it in ALLOWED_ESTIMATES },
            listName = fields.listName?.trim()?.take(MAX_LIST)?.trimEnd()?.ifEmpty { null },
        )
    }

    /**
     * Strict, all-or-nothing validation for imported data (ARCHITECTURE R2-8, GA-03). Unlike [normalize] it
     * never repairs anything: an invalid import is rejected as a whole so it can never corrupt current data.
     */
    fun validateForImport(tasks: List<Task>): List<String> {
        val problems = mutableListOf<String>()
        val seen = HashSet<Long>()
        for (t in tasks) {
            val who = "task ${t.id}"
            if (t.id <= 0 || t.id > Int.MAX_VALUE) problems += "$who: id out of range"
            if (!seen.add(t.id)) problems += "$who: duplicate id"
            if (t.title.isBlank() || t.title != t.title.trim()) problems += "$who: title blank or untrimmed"
            if (t.dueDate != null && !isSupportedDate(t.dueDate)) problems += "$who: due date out of range"
            if (t.recurrenceAnchor != null && !isSupportedDate(t.recurrenceAnchor)) problems += "$who: anchor out of range"
            if (t.title.length > MAX_TITLE) problems += "$who: title too long"
            if (t.notes.length > MAX_NOTES) problems += "$who: notes too long"
            if ((t.listName?.length ?: 0) > MAX_LIST) problems += "$who: list too long"
            if (t.dueTime != null && t.dueDate == null) problems += "$who: time without date"
            if (t.dueTime != null && t.dueTime.truncatedTo(ChronoUnit.MINUTES) != t.dueTime) problems += "$who: time not minute precision"
            if (t.reminderEnabled && t.dueTime == null) problems += "$who: reminder without time"
            if (t.recurrence != Recurrence.NONE && t.dueDate == null) problems += "$who: recurrence without date"
            if ((t.recurrence == Recurrence.NONE) != (t.recurrenceAnchor == null)) problems += "$who: recurrence/anchor mismatch"
            if (t.estimatedMinutes != null && t.estimatedMinutes !in ALLOWED_ESTIMATES) problems += "$who: estimate not allowed"
            if (t.done != (t.completedAt != null)) problems += "$who: done/completedAt mismatch"
            if (t.reminderRevision < 0) problems += "$who: negative revision"
            if (t.reminderRevision > MAX_IMPORT_REVISION) problems += "$who: revision out of range"
            if (t.listName != null && t.listName.isBlank()) problems += "$who: blank list"
        }
        return problems
    }
}
