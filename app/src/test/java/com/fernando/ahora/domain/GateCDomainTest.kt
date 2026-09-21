package com.fernando.ahora.domain

import com.fernando.ahora.domain.backup.BackupCodec
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.domain.rules.TaskRules
import com.fernando.ahora.testing.aTask
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codex Gate C regression tests for the pure rules and the backup file (GC-04, GC-09). */
class GateCDomainTest {
    private val now = Instant.parse("2026-09-20T10:00:00Z")

    private fun rejectedReason(text: String) = (BackupCodec.decode(text) as BackupCodec.DecodeResult.Rejected).reason

    // ---- GC-04: a date the engine cannot follow never gets in --------------------------------------------------

    @Test
    fun import_rejectsADueDateBeyondTheSupportedRange() {
        // Encoded through the codec, then the date is rewritten: exactly what a corrupt or crafted file looks like.
        val json = BackupCodec.encode(listOf(aTask(1, "x", LocalDate.of(2026, 9, 21))), now)
            .replace("2026-09-21", "+999999999-12-31")
        assertEquals(BackupCodec.Reason.INVALID_DATA, rejectedReason(json))
    }

    @Test
    fun import_rejectsARecurrenceAnchorBeyondTheSupportedRange() {
        val json = BackupCodec.encode(
            listOf(aTask(1, "x", LocalDate.of(2026, 9, 21), recurrence = Recurrence.DAILY)), now,
        ).replace("\"recurrenceAnchor\": \"2026-09-21\"", "\"recurrenceAnchor\": \"-999999999-01-01\"")
        assertEquals(BackupCodec.Reason.INVALID_DATA, rejectedReason(json))
    }

    @Test
    fun import_acceptsTheWholeSupportedRange_soARealBackupNeverFails() {
        val edges = listOf(
            aTask(1, "min", TaskRules.MIN_DATE),
            aTask(2, "max", TaskRules.MAX_DATE, recurrence = Recurrence.DAILY),
        )
        val result = BackupCodec.decode(BackupCodec.encode(edges, now)) as BackupCodec.DecodeResult.Ok
        assertEquals(edges.map { it.dueDate }, result.tasks.map { it.dueDate })
    }

    @Test
    fun theSupportedRangeContainsWhatTheDatePickerCanProduce() {
        // Material's DatePicker default year range is 1900..2100.
        assertTrue(TaskRules.isSupportedDate(LocalDate.of(1900, 1, 1)))
        assertTrue(TaskRules.isSupportedDate(LocalDate.of(2100, 12, 31)))
        assertFalse(TaskRules.isSupportedDate(LocalDate.of(1899, 12, 31)))
        assertFalse(TaskRules.isSupportedDate(LocalDate.MAX))
    }

    @Test
    fun normalize_dropsAnUnsupportedDateTogetherWithEverythingThatDependsOnIt() {
        val f = TaskRules.normalize(
            TaskFields(
                "x", dueDate = LocalDate.MAX, dueTime = LocalTime.NOON, reminderEnabled = true, recurrence = Recurrence.DAILY,
            ),
        )!!
        assertNull(f.dueDate)
        assertNull(f.dueTime)
        assertFalse(f.reminderEnabled)
        assertEquals(Recurrence.NONE, f.recurrence)
    }

    // ---- GC-09: whatever the app lets you save, its own export re-imports --------------------------------------

    @Test
    fun normalize_capsTextAtTheBackupLimits() {
        val f = TaskRules.normalize(
            TaskFields(
                "t".repeat(TaskRules.MAX_TITLE + 50),
                notes = "n".repeat(TaskRules.MAX_NOTES + 50),
                listName = "l".repeat(TaskRules.MAX_LIST + 50),
            ),
        )!!
        assertEquals(TaskRules.MAX_TITLE, f.title.length)
        assertEquals(TaskRules.MAX_NOTES, f.notes.length)
        assertEquals(TaskRules.MAX_LIST, f.listName!!.length)
    }

    @Test
    fun aTaskSavedAtTheLimits_survivesExportAndImport() {
        val f = TaskRules.normalize(
            TaskFields(
                "t".repeat(TaskRules.MAX_TITLE + 1),
                notes = "n".repeat(TaskRules.MAX_NOTES + 1),
                listName = "l".repeat(TaskRules.MAX_LIST + 1),
            ),
        )!!
        val saved = aTask(1, f.title, notes = f.notes).copy(listName = f.listName)

        val result = BackupCodec.decode(BackupCodec.encode(listOf(saved), now))

        assertTrue("own export must re-import, got $result", result is BackupCodec.DecodeResult.Ok)
    }

    @Test
    fun normalize_neverLeavesATrailingSpaceAfterCuttingTheTitle() {
        val f = TaskRules.normalize(TaskFields("a".repeat(TaskRules.MAX_TITLE - 1) + " b"))!!
        assertEquals(f.title, f.title.trim())
    }
}
