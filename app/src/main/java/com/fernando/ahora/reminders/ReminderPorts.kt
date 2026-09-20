package com.fernando.ahora.reminders

import com.fernando.ahora.domain.model.Task
import java.time.Instant

/** The single AlarmManager cursor (ARCHITECTURE §10.1 rule 2). Arming replaces any previous alarm. */
interface ReminderScheduler {
    fun arm(at: Instant)
    fun cancel()
}

/** A reminder notification currently visible in the tray. */
data class ActiveCard(val taskId: Long, val revision: Int)

/** Everything the reconciler needs from the notification system. */
interface ReminderNotifier {
    /** False when notifications cannot be shown (permission denied / disabled channel). */
    fun canNotify(): Boolean

    /** Posts (or replaces) the reminder card for [task]. Throws on failure. */
    fun post(task: Task)

    fun cancel(taskId: Long)
    fun cancelAll()

    /** The reminder cards currently in the tray, with the schedule revision each was posted for. */
    fun activeCards(): List<ActiveCard>
}

/** Thrown by a notifier for failures that will never succeed on retry (e.g. missing permission). */
class PermanentNotificationFailure(message: String, cause: Throwable? = null) : Exception(message, cause)
