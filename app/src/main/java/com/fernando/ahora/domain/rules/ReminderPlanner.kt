package com.fernando.ahora.domain.rules

import com.fernando.ahora.domain.model.Task
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Pure reminder maths. The zone is always passed in so timezone changes need no data migration (D-05). */
object ReminderPlanner {
    /** A reminder at most this late is delivered once; older ones are dropped silently (D-10). */
    val MISSED_GRACE: Duration = Duration.ofHours(12)

    /** An item whose fire time is within this of "now" counts as due, so we never arm a 100 ms alarm. */
    val DUE_TOLERANCE: Duration = Duration.ofSeconds(2)

    val SNOOZE: Duration = Duration.ofMinutes(10)

    /**
     * Local date + time → instant in [zone]. `ZonedDateTime.of` resolves a spring-forward gap by moving
     * forward by the gap length and an autumn overlap to the EARLIER offset — exactly PRODUCT_SPEC §6.1.
     */
    fun dueInstant(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.of(date, time, zone).toInstant()

    /**
     * The next instant this task wants to notify, or null. A pending snooze overrides the due-time
     * reminder; a consumed due-time reminder (firedAt != null) yields nothing.
     */
    fun nextFire(task: Task, zone: ZoneId): Instant? {
        if (!task.hasDefinedReminder) return null
        task.reminderSnoozeUntil?.let { return it }
        if (task.reminderFiredAt != null) return null
        return dueInstant(task.dueDate!!, task.dueTime!!, zone)
    }

    fun isDue(fire: Instant, now: Instant): Boolean = !fire.isAfter(now.plus(DUE_TOLERANCE))

    /** For a due item: deliver (late but within grace) or drop silently. */
    fun shouldDeliver(fire: Instant, now: Instant): Boolean = Duration.between(fire, now) <= MISSED_GRACE

    /**
     * Setting/restoring a reminder for a moment already in the past must not notify retroactively:
     * the reminder is marked consumed at [now].
     */
    fun consumeIfPast(task: Task, zone: ZoneId, now: Instant): Task {
        if (!task.hasDefinedReminder || task.reminderSnoozeUntil != null || task.reminderFiredAt != null) return task
        val fire = dueInstant(task.dueDate!!, task.dueTime!!, zone)
        return if (!fire.isAfter(now)) task.copy(reminderFiredAt = now) else task
    }
}
