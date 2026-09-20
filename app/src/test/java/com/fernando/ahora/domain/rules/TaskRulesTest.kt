package com.fernando.ahora.domain.rules

import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.TaskFields
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRulesTest {
    private val date = LocalDate.of(2026, 9, 21)

    @Test
    fun quickCapture_titleOnlyIsValid() {
        val f = TaskRules.normalize(TaskFields("  Llamar al dentista  "))!!
        assertEquals("Llamar al dentista", f.title)
        assertNull(f.dueDate)
        assertNull(f.priority)
        assertFalse(f.reminderEnabled)
    }

    @Test
    fun blankTitleIsRejected_everythingElseIsOptional() {
        assertNull(TaskRules.normalize(TaskFields("   ", priority = Priority.P1, dueDate = date)))
        assertNull(TaskRules.normalize(TaskFields("")))
    }

    @Test
    fun timeWithoutDate_isCleared_andTakesReminderAndRecurrenceWithIt() {
        val f = TaskRules.normalize(
            TaskFields("x", dueTime = LocalTime.NOON, reminderEnabled = true, recurrence = Recurrence.DAILY),
        )!!
        assertNull(f.dueTime)
        assertFalse(f.reminderEnabled)
        assertEquals(Recurrence.NONE, f.recurrence)
    }

    @Test
    fun reminderWithoutTime_isCleared() {
        val f = TaskRules.normalize(TaskFields("x", dueDate = date, reminderEnabled = true))!!
        assertFalse(f.reminderEnabled)
    }

    @Test
    fun fullyDefinedTask_isKeptAsIs() {
        val f = TaskRules.normalize(
            TaskFields("x", dueDate = date, dueTime = LocalTime.of(9, 30), reminderEnabled = true, recurrence = Recurrence.WEEKLY),
        )!!
        assertTrue(f.reminderEnabled)
        assertEquals(Recurrence.WEEKLY, f.recurrence)
    }

    @Test
    fun blankListAndNonPositiveEstimate_becomeNull() {
        val f = TaskRules.normalize(TaskFields("x", listName = "   ", estimatedMinutes = 0))!!
        assertNull(f.listName)
        assertNull(f.estimatedMinutes)
    }
}
