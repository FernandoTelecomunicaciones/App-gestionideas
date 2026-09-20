package com.fernando.ahora.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** `internal`: the UI can never reach a DAO (ARCHITECTURE §4). */
@Dao
internal interface TaskDao {
    @Query("SELECT * FROM tasks WHERE done = 0")
    fun observePending(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE done = 1 ORDER BY completedAt DESC")
    fun observeCompleted(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeById(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks")
    suspend fun getAll(): List<TaskEntity>

    @Insert
    suspend fun insert(entity: TaskEntity): Long

    /** Returns -1 (and writes nothing) when the id already exists — never overwrites newer data (GA-05). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: TaskEntity): Long

    @Insert
    suspend fun insertAll(entities: List<TaskEntity>)

    @Update
    suspend fun update(entity: TaskEntity): Int

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM tasks")
    suspend fun deleteAll(): Int

    @Query(
        "SELECT * FROM tasks WHERE done = 0 AND reminderEnabled = 1 AND dueDate IS NOT NULL AND dueTime IS NOT NULL",
    )
    suspend fun reminderCandidates(): List<TaskEntity>

    /** Conditional on the schedule revision the candidate was read at (ARCHITECTURE R2-1). */
    @Query(
        "UPDATE tasks SET reminderFiredAt = :now, reminderSnoozeUntil = NULL " +
            "WHERE id = :id AND reminderRevision = :revision AND done = 0",
    )
    suspend fun markDelivered(id: Long, revision: Int, now: Long): Int

    /**
     * Snoozes only a reminder that actually fired for this exact schedule AND is not already snoozed, so a
     * duplicated or delayed +10 MIN can never push a pending snooze further out (GB-06).
     */
    @Query(
        "UPDATE tasks SET reminderSnoozeUntil = :until " +
            "WHERE id = :id AND reminderRevision = :revision AND done = 0 " +
            "AND reminderEnabled = 1 AND reminderFiredAt IS NOT NULL AND reminderSnoozeUntil IS NULL",
    )
    suspend fun snoozeIfDelivered(id: Long, revision: Int, until: Long): Int
}
