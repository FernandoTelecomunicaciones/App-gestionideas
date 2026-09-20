package com.fernando.ahora.domain.rules

import com.fernando.ahora.domain.model.Recurrence
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecurrenceCalculatorTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun none_hasNoNext() {
        assertNull(RecurrenceCalculator.next(d(2026, 9, 20), d(2026, 9, 20), Recurrence.NONE, d(2026, 9, 20)))
    }

    @Test
    fun daily_isTomorrow_evenWhenCompletedManyDaysLate() {
        assertEquals(d(2026, 9, 21), RecurrenceCalculator.next(d(2026, 9, 10), d(2026, 9, 10), Recurrence.DAILY, d(2026, 9, 20)))
    }

    @Test
    fun weekly_onTime() {
        assertEquals(d(2026, 9, 21), RecurrenceCalculator.next(d(2026, 9, 14), d(2026, 9, 14), Recurrence.WEEKLY, d(2026, 9, 14)))
    }

    @Test
    fun weekly_completedLate_skipsMissedOccurrencesWithoutBacklog() {
        // anchor Mon 09-14, due 09-14, completed on 09-30 -> next Monday strictly after 09-30 is 10-05
        assertEquals(d(2026, 10, 5), RecurrenceCalculator.next(d(2026, 9, 14), d(2026, 9, 14), Recurrence.WEEKLY, d(2026, 9, 30)))
    }

    @Test
    fun weekly_completedEarly_isStrictlyAfterDueDate() {
        // due 09-28, completed 09-20 -> must be after 09-28
        assertEquals(d(2026, 10, 5), RecurrenceCalculator.next(d(2026, 9, 14), d(2026, 9, 28), Recurrence.WEEKLY, d(2026, 9, 20)))
    }

    @Test
    fun monthly_noMonthEndDrift() {
        val anchor = d(2026, 1, 31)
        val feb = RecurrenceCalculator.next(anchor, d(2026, 1, 31), Recurrence.MONTHLY, d(2026, 1, 31))
        assertEquals(d(2026, 2, 28), feb)
        // the successor row keeps the SAME anchor, so March returns to the 31st
        assertEquals(d(2026, 3, 31), RecurrenceCalculator.next(anchor, feb!!, Recurrence.MONTHLY, feb))
    }

    @Test
    fun monthly_leapYear() {
        assertEquals(d(2028, 2, 29), RecurrenceCalculator.next(d(2028, 1, 31), d(2028, 1, 31), Recurrence.MONTHLY, d(2028, 1, 31)))
    }

    @Test
    fun monthly_completedMonthsLate_landsOnFirstFutureOccurrence() {
        assertEquals(d(2026, 6, 15), RecurrenceCalculator.next(d(2026, 1, 15), d(2026, 1, 15), Recurrence.MONTHLY, d(2026, 5, 20)))
    }
}
