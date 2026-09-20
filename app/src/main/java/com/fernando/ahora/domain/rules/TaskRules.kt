package com.fernando.ahora.domain.rules

import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.model.TaskFields
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
     * Returns the normalised fields, or null when the title is blank (the only hard requirement).
     * Dependent fields are cleared rather than rejected: time requires a date, a reminder requires a
     * time, recurrence requires a date. Optional fields never produce errors.
     * The time is canonicalised to minute precision BEFORE any reminder maths (GA-06).
     */
    fun normalize(fields: TaskFields): TaskFields? {
        val title = fields.title.trim()
        if (title.isEmpty()) return null

        val date = fields.dueDate
        val time = if (date == null) null else fields.dueTime?.truncatedTo(ChronoUnit.MINUTES)
        val reminder = time != null && fields.reminderEnabled
        val recurrence = if (date == null) Recurrence.NONE else fields.recurrence

        return fields.copy(
            title = title,
            dueTime = time,
            reminderEnabled = reminder,
            recurrence = recurrence,
            estimatedMinutes = fields.estimatedMinutes?.takeIf { it in ALLOWED_ESTIMATES },
            listName = fields.listName?.trim()?.ifEmpty { null },
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
