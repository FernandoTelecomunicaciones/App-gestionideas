package com.fernando.ahora.data.local

import com.fernando.ahora.domain.ReminderStore
import com.fernando.ahora.domain.model.Task
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Trigger-free reminder bookkeeping (ARCHITECTURE §10.3). Never calls the repository or the reconciler. */
@Singleton
internal class RoomReminderStore @Inject constructor(private val dao: TaskDao) : ReminderStore {
    override suspend fun candidates(): List<Task> = dao.reminderCandidates().map { it.toDomain() }

    override suspend fun get(taskId: Long): Task? = dao.getById(taskId)?.toDomain()

    override suspend fun markDelivered(taskId: Long, revision: Int, now: Instant): Boolean =
        dao.markDelivered(taskId, revision, now.toEpochMilli()) > 0

    override suspend fun snoozeIfDelivered(taskId: Long, revision: Int, until: Instant): Boolean =
        dao.snoozeIfDelivered(taskId, revision, until.toEpochMilli()) > 0
}
