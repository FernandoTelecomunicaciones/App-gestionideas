package com.fernando.ahora.testing

import com.fernando.ahora.domain.ReminderStore
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.reminders.ActiveCard
import com.fernando.ahora.reminders.ReminderNotifier
import com.fernando.ahora.reminders.ReminderScheduler
import java.time.Instant

/** A shared, ordered event log so tests can assert "armed BEFORE delivered". */
class EventLog {
    val events = mutableListOf<String>()
    operator fun plusAssign(e: String) { events += e }
    fun indexOfFirst(prefix: String) = events.indexOfFirst { it.startsWith(prefix) }
}

class FakeScheduler(private val log: EventLog = EventLog()) : ReminderScheduler {
    var armed: Instant? = null
    val arms = mutableListOf<Instant>()
    var cancels = 0

    /** The next N arm calls throw (AlarmManager binder failure), then behave normally. */
    var armFailures = 0

    override fun arm(at: Instant) {
        if (armFailures > 0) {
            armFailures--
            log += "arm-failed"
            throw IllegalStateException("AlarmManager unavailable")
        }
        armed = at
        arms += at
        log += "arm:$at"
    }

    override fun cancel() {
        armed = null
        cancels++
        log += "cancel-cursor"
    }
}

class FakeNotifier(private val log: EventLog = EventLog()) : ReminderNotifier {
    var notificationsEnabled = true
    val cards = mutableListOf<ActiveCard>()
    val posted = mutableListOf<Long>()
    val failFor = mutableMapOf<Long, Exception>()
    var onPost: (Task) -> Unit = {}

    override fun canNotify() = notificationsEnabled

    override fun post(task: Task) {
        log += "post:${task.id}"
        failFor[task.id]?.let { throw it }
        onPost(task)
        cards.removeAll { it.taskId == task.id }
        cards += ActiveCard(task.id, task.reminderRevision)
        posted += task.id
    }

    override fun cancel(taskId: Long) {
        cards.removeAll { it.taskId == taskId }
        log += "cancel:$taskId"
    }

    override fun cancelAll() {
        cards.clear()
        log += "cancel-all"
    }

    var failActiveCards: Throwable? = null

    override fun activeCards(): List<ActiveCard> {
        failActiveCards?.let { throw it }
        return cards.toList()
    }
}

/** Same semantics as the SQL in TaskDao (revision-guarded), for pure-JVM reconciler tests. */
class FakeReminderStore(private val log: EventLog = EventLog()) : ReminderStore {
    val tasks = LinkedHashMap<Long, Task>()

    fun put(task: Task) { tasks[task.id] = task }

    var candidateReads = 0
    var failCandidates: Exception? = null

    override suspend fun candidates(): List<Task> {
        candidateReads++
        failCandidates?.let { throw it }
        return readCandidates()
    }

    private fun readCandidates() = tasks.values.filter {
        !it.done && it.reminderEnabled && it.dueDate != null && it.dueTime != null
    }

    override suspend fun get(taskId: Long) = tasks[taskId]

    override suspend fun markDelivered(taskId: Long, revision: Int, now: Instant): Boolean {
        val t = tasks[taskId] ?: return false
        if (t.reminderRevision != revision || t.done) return false
        tasks[taskId] = t.copy(reminderFiredAt = now, reminderSnoozeUntil = null)
        log += "mark:$taskId"
        return true
    }

    override suspend fun snoozeIfDelivered(taskId: Long, revision: Int, until: Instant): Boolean {
        val t = tasks[taskId] ?: return false
        if (t.reminderRevision != revision || t.done || !t.reminderEnabled || t.reminderFiredAt == null ||
            t.reminderSnoozeUntil != null
        ) return false
        tasks[taskId] = t.copy(reminderSnoozeUntil = until)
        return true
    }
}
