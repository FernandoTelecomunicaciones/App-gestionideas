package com.fernando.ahora.domain.backup

import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.testing.aTask
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ajustes > Datos: the file is accepted whole or rejected whole (ARCHITECTURE R2-8). */
class BackupCodecTest {
    private val now = Instant.parse("2026-09-20T10:00:00Z")

    private fun valid(): String = BackupCodec.encode(
        listOf(
            aTask(1, "Con todo", LocalDate.of(2026, 9, 21), LocalTime.of(18, 30), Priority.P1, reminderEnabled = true, notes = "notas"),
            aTask(2, "Sin nada"),
            aTask(3, "Hecha", done = true),
            aTask(4, "Se repite", LocalDate.of(2026, 9, 22), recurrence = Recurrence.MONTHLY),
        ),
        now,
    )

    private fun rejected(text: String) = BackupCodec.decode(text) as BackupCodec.DecodeResult.Rejected

    @Test
    fun roundTrip_preservesUserData() {
        val original = listOf(
            aTask(1, "Con todo", LocalDate.of(2026, 9, 21), LocalTime.of(18, 30), Priority.P1, reminderEnabled = true, notes = "notas"),
            aTask(4, "Se repite", LocalDate.of(2026, 9, 22), recurrence = Recurrence.MONTHLY),
        )
        val result = BackupCodec.decode(BackupCodec.encode(original, now)) as BackupCodec.DecodeResult.Ok

        assertEquals(original.map { it.title }, result.tasks.map { it.title })
        val first = result.tasks.first()
        assertEquals(LocalTime.of(18, 30), first.dueTime)
        assertEquals(Priority.P1, first.priority)
        assertTrue(first.reminderEnabled)
        assertEquals(LocalDate.of(2026, 9, 22), result.tasks.last().recurrenceAnchor)
    }

    @Test
    fun reminderDeliveryState_isNotExported_soAnImportedReminderStartsFresh() {
        val delivered = aTask(1, "x", LocalDate.of(2026, 9, 20), LocalTime.of(9, 0), reminderEnabled = true)
            .copy(reminderFiredAt = now, reminderSnoozeUntil = now.plusSeconds(600), reminderRevision = 7)
        val back = (BackupCodec.decode(BackupCodec.encode(listOf(delivered), now)) as BackupCodec.DecodeResult.Ok).tasks.single()
        assertEquals(null, back.reminderFiredAt)
        assertEquals(null, back.reminderSnoozeUntil)
        assertEquals(0, back.reminderRevision)
    }

    @Test
    fun theFile_declaresItsSchemaVersion() {
        assertTrue(valid().contains("\"schemaVersion\": ${BackupCodec.SCHEMA_VERSION}"))
    }

    @Test
    fun garbage_isNotABackup() {
        assertEquals(BackupCodec.Reason.NOT_A_BACKUP, rejected("hola").reason)
        assertEquals(BackupCodec.Reason.NOT_A_BACKUP, rejected("[1,2,3]").reason)
        assertEquals(BackupCodec.Reason.NOT_A_BACKUP, rejected("""{"tasks": []}""").reason)
        assertEquals(BackupCodec.Reason.NOT_A_BACKUP, rejected("""{"schemaVersion": 1, "exportedAt": "x"}""").reason)
    }

    @Test
    fun aDifferentSchemaVersion_isRejected_beforeAnythingElseIsRead() {
        assertEquals(BackupCodec.Reason.UNSUPPORTED_VERSION, rejected(valid().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")).reason)
        assertEquals(BackupCodec.Reason.UNSUPPORTED_VERSION, rejected("""{"schemaVersion": 0, "tasks": "not even a list"}""").reason)
    }

    @Test
    fun anUnknownRecurrence_isRejected_notSilentlyRepaired() {
        val bad = valid().replace("\"monthly\"", "\"fortnightly\"")
        assertEquals(BackupCodec.Reason.INVALID_DATA, rejected(bad).reason)
    }

    @Test
    fun aPriorityOutOfRange_isRejected() {
        assertEquals(BackupCodec.Reason.INVALID_DATA, rejected(valid().replace("\"priority\": 1", "\"priority\": 9")).reason)
    }

    @Test
    fun anUnparseableDate_isRejected() {
        assertEquals(BackupCodec.Reason.INVALID_DATA, rejected(valid().replace("2026-09-21", "21/09/2026")).reason)
    }

    @Test
    fun aTimeWithSeconds_isRejected_byTheSharedImportRules() {
        assertEquals(BackupCodec.Reason.INVALID_DATA, rejected(valid().replace("\"18:30\"", "\"18:30:15\"")).reason)
    }

    @Test
    fun duplicateIds_areRejected() {
        val dup = BackupCodec.encode(listOf(aTask(1, "a"), aTask(1, "b")), now)
        assertEquals(BackupCodec.Reason.INVALID_DATA, rejected(dup).reason)
    }

    @Test
    fun aBlankOrUntrimmedTitle_isRejected() {
        assertEquals(BackupCodec.Reason.INVALID_DATA, rejected(valid().replace("\"Sin nada\"", "\"   \"")).reason)
        assertEquals(BackupCodec.Reason.INVALID_DATA, rejected(valid().replace("\"Sin nada\"", "\" Sin nada \"")).reason)
    }

    @Test
    fun absurdSizes_areRejected() {
        val hugeTitle = "x".repeat(BackupCodec.MAX_TITLE + 1)
        assertEquals(BackupCodec.Reason.INVALID_DATA, rejected(BackupCodec.encode(listOf(aTask(1, hugeTitle)), now)).reason)
        assertEquals(BackupCodec.Reason.TOO_LARGE, rejected("x".repeat(BackupCodec.MAX_CHARS + 1)).reason)
    }

    @Test
    fun anEmptyList_isAValidBackup() {
        assertTrue((BackupCodec.decode(BackupCodec.encode(emptyList(), now)) as BackupCodec.DecodeResult.Ok).tasks.isEmpty())
    }
}
