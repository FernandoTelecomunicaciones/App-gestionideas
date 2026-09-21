package com.fernando.ahora.domain.backup

import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.rules.TaskRules
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int

/**
 * Ajustes › Datos: the versioned JSON copy the user exports and imports (PRODUCT_SPEC §5.7, ARCHITECTURE R2-8).
 * Pure Kotlin. [decode] never repairs anything: a file is accepted whole or rejected whole, and it is validated
 * with the same [TaskRules.validateForImport] the repository re-runs before touching Room.
 *
 * Only user data is written. Reminder delivery state (fired/snoozed/revision) is bookkeeping owned by the
 * reminder engine and is deliberately NOT exported: an imported reminder starts fresh, and the 12 h missed-reminder
 * rule keeps an old file from replaying stale notifications.
 */
object BackupCodec {
    const val SCHEMA_VERSION = 1

    /** A personal task list is hundreds of rows; anything beyond these is not a copy AHORA made. */
    const val MAX_CHARS = 8 * 1024 * 1024
    const val MAX_TASKS = 50_000
    const val MAX_TITLE = TaskRules.MAX_TITLE
    const val MAX_NOTES = TaskRules.MAX_NOTES
    const val MAX_LIST = TaskRules.MAX_LIST

    private const val MAX_EPOCH_MS = 32_503_680_000_000L // year 3000

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class BackupTask(
        val id: Long,
        val title: String,
        val notes: String = "",
        val dueDate: String? = null,
        val dueTime: String? = null,
        val reminderEnabled: Boolean = false,
        val priority: Int? = null,
        val recurrence: String = "none",
        val recurrenceAnchor: String? = null,
        val estimatedMinutes: Int? = null,
        val listName: String? = null,
        val done: Boolean = false,
        val completedAt: Long? = null,
        val createdAt: Long,
        val updatedAt: Long,
    )

    @Serializable
    private data class BackupFile(
        val schemaVersion: Int,
        val exportedAt: String,
        val tasks: List<BackupTask>,
    )

    sealed interface DecodeResult {
        data class Ok(val tasks: List<Task>) : DecodeResult
        data class Rejected(val reason: Reason, val details: List<String> = emptyList()) : DecodeResult
    }

    enum class Reason { TOO_LARGE, NOT_A_BACKUP, UNSUPPORTED_VERSION, INVALID_DATA }

    fun encode(tasks: List<Task>, exportedAt: Instant): String = json.encodeToString(
        BackupFile.serializer(),
        BackupFile(
            schemaVersion = SCHEMA_VERSION,
            exportedAt = exportedAt.toString(),
            tasks = tasks.sortedBy { it.id }.map { it.toBackup() },
        ),
    )

    fun decode(text: String): DecodeResult {
        if (text.length > MAX_CHARS) return DecodeResult.Rejected(Reason.TOO_LARGE)

        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return DecodeResult.Rejected(Reason.NOT_A_BACKUP)

        val version = (root["schemaVersion"] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() }
            ?: return DecodeResult.Rejected(Reason.NOT_A_BACKUP)
        if (version != SCHEMA_VERSION) {
            return DecodeResult.Rejected(Reason.UNSUPPORTED_VERSION, listOf("schemaVersion=$version"))
        }

        val file = try {
            json.decodeFromJsonElement(BackupFile.serializer(), root)
        } catch (_: Exception) {
            return DecodeResult.Rejected(Reason.NOT_A_BACKUP)
        }
        if (file.tasks.size > MAX_TASKS) return DecodeResult.Rejected(Reason.TOO_LARGE)

        val problems = mutableListOf<String>()
        val tasks = file.tasks.mapNotNull { row ->
            try {
                row.toTask(problems)
            } catch (e: RuntimeException) {
                problems += "task ${row.id}: unreadable value (${e.javaClass.simpleName})"
                null
            }
        }
        if (problems.isNotEmpty()) return DecodeResult.Rejected(Reason.INVALID_DATA, problems.take(20))

        val invalid = TaskRules.validateForImport(tasks)
        if (invalid.isNotEmpty()) return DecodeResult.Rejected(Reason.INVALID_DATA, invalid.take(20))
        return DecodeResult.Ok(tasks)
    }

    private fun Task.toBackup() = BackupTask(
        id = id,
        title = title,
        notes = notes,
        dueDate = dueDate?.toString(),
        dueTime = dueTime?.toString(),
        reminderEnabled = reminderEnabled,
        priority = priority?.value,
        recurrence = recurrence.code,
        recurrenceAnchor = recurrenceAnchor?.toString(),
        estimatedMinutes = estimatedMinutes,
        listName = listName,
        done = done,
        completedAt = completedAt?.toEpochMilli(),
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli(),
    )

    /** Strict: unknown enum codes, out-of-range priorities and absurd sizes are problems, never silently fixed. */
    private fun BackupTask.toTask(problems: MutableList<String>): Task? {
        val who = "task $id"
        val before = problems.size
        if (title.length > MAX_TITLE) problems += "$who: title too long"
        if (notes.length > MAX_NOTES) problems += "$who: notes too long"
        if ((listName?.length ?: 0) > MAX_LIST) problems += "$who: list too long"
        if (priority != null && Priority.fromValue(priority) == null) problems += "$who: priority out of range"
        val rec = Recurrence.entries.firstOrNull { it.code == recurrence }
        if (rec == null) problems += "$who: unknown recurrence"
        for (ms in listOfNotNull(completedAt, createdAt, updatedAt)) {
            if (ms < 0 || ms > MAX_EPOCH_MS) problems += "$who: timestamp out of range"
        }
        if (problems.size != before) return null

        return Task(
            id = id,
            title = title,
            notes = notes,
            dueDate = dueDate?.let(LocalDate::parse),
            dueTime = dueTime?.let(LocalTime::parse),
            reminderEnabled = reminderEnabled,
            reminderFiredAt = null,
            reminderSnoozeUntil = null,
            reminderRevision = 0,
            priority = Priority.fromValue(priority),
            recurrence = rec ?: Recurrence.NONE,
            recurrenceAnchor = recurrenceAnchor?.let(LocalDate::parse),
            estimatedMinutes = estimatedMinutes,
            listName = listName,
            done = done,
            completedAt = completedAt?.let(Instant::ofEpochMilli),
            createdAt = Instant.ofEpochMilli(createdAt),
            updatedAt = Instant.ofEpochMilli(updatedAt),
        )
    }
}
