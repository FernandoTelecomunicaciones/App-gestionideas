package com.fernando.ahora.ui.editor

import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sheet's UI-coherence rules (PRODUCT_SPEC §4.1 "UI consequence"); the repository stays the source of truth. */
class EditorDraftTest {
    private val today = LocalDate.of(2026, 9, 20)
    private val now = LocalTime.of(12, 0)

    @Test
    fun titleAlone_isEnoughToSave() {
        assertFalse(EditorDraft().canSave)
        assertFalse(EditorDraft(title = "   ").canSave)
        assertTrue(EditorDraft(title = "x").canSave)
    }

    @Test
    fun pickingATimeWithoutADate_selectsHoy_whenTheTimeIsStillAhead() {
        val d = EditorDraft(title = "x").withTime(LocalTime.of(18, 0), today, now)
        assertEquals(today, d.dueDate)
        assertEquals(LocalTime.of(18, 0), d.dueTime)
    }

    @Test
    fun pickingATimeWithoutADate_selectsMañana_whenTheTimeHasPassed() {
        val d = EditorDraft(title = "x").withTime(LocalTime.of(9, 0), today, now)
        assertEquals(today.plusDays(1), d.dueDate)
    }

    @Test
    fun pickingATime_keepsAnExistingDate() {
        val d = EditorDraft(title = "x").withDate(today.plusDays(5)).withTime(LocalTime.of(9, 0), today, now)
        assertEquals(today.plusDays(5), d.dueDate)
    }

    @Test
    fun clearingTheDate_clearsTimeReminderAndRecurrence() {
        val d = EditorDraft(title = "x")
            .withDate(today)
            .withTime(LocalTime.of(18, 0), today, now)
            .withReminder(true)
            .withRecurrence(Recurrence.WEEKLY)
            .withDate(null)
        assertNull(d.dueDate)
        assertNull(d.dueTime)
        assertFalse(d.reminder)
        assertEquals(Recurrence.NONE, d.recurrenceValue)
    }

    @Test
    fun clearingTheTime_clearsTheReminder() {
        val d = EditorDraft(title = "x").withDate(today).withTime(LocalTime.of(18, 0), today, now).withReminder(true)
            .withTime(null, today, now)
        assertNull(d.dueTime)
        assertFalse(d.reminder)
        assertEquals(today, d.dueDate) // the date stays
    }

    @Test
    fun reminder_needsATime() {
        assertFalse(EditorDraft(title = "x").withDate(today).withReminder(true).reminder)
    }

    @Test
    fun repeat_isRefusedUntilThereIsADate() {
        val d = EditorDraft(title = "x").withRecurrence(Recurrence.DAILY)
        assertEquals(Recurrence.NONE, d.recurrenceValue)
        assertEquals(Recurrence.DAILY, d.withDate(today).withRecurrence(Recurrence.DAILY).recurrenceValue)
    }

    @Test
    fun priority_canBeSetAndCleared() {
        val d = EditorDraft(title = "x").withPriority(Priority.P1)
        assertEquals(Priority.P1, d.priorityValue)
        assertNull(d.withPriority(null).priorityValue)
    }

    @Test
    fun reminderInThePast_isDetected_onlyWhenTheReminderIsOn() {
        val zone = ZoneId.of("Europe/Madrid")
        val nowInstant = Instant.parse("2026-09-20T10:00:00Z") // 12:00 Madrid
        val past = EditorDraft(title = "x").withDate(today).withTime(LocalTime.of(9, 0), today, now).withReminder(true)
        val future = past.withTime(LocalTime.of(18, 0), today, now)
        assertTrue(past.reminderIsInPast(nowInstant, zone))
        assertFalse(future.reminderIsInPast(nowInstant, zone))
        assertFalse(past.withReminder(false).reminderIsInPast(nowInstant, zone))
    }

    @Test
    fun toFields_blankListBecomesNull() {
        assertNull(EditorDraft(title = "x", list = "  ").toFields().listName)
        assertEquals("Casa", EditorDraft(title = "x", list = "Casa").toFields().listName)
    }
}
