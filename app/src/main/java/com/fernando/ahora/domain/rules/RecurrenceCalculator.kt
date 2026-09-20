package com.fernando.ahora.domain.rules

import com.fernando.ahora.domain.model.Recurrence
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object RecurrenceCalculator {
    /**
     * First date in the series **strictly after `max(today, dueDate)`** (PRODUCT_SPEC §6.5).
     * Monthly uses `anchor.plusMonths(k)` from the ORIGINAL anchor so month-end never drifts
     * (Jan 31 → Feb 28 → Mar 31). Returns null for [Recurrence.NONE].
     */
    fun next(anchor: LocalDate, dueDate: LocalDate, recurrence: Recurrence, today: LocalDate): LocalDate? {
        val after = maxOf(today, dueDate)
        return when (recurrence) {
            Recurrence.NONE -> null
            Recurrence.DAILY -> after.plusDays(1)
            Recurrence.WEEKLY -> {
                val days = ChronoUnit.DAYS.between(anchor, after)
                if (days < 0) anchor else anchor.plusDays((days / 7 + 1) * 7)
            }
            Recurrence.MONTHLY -> {
                var k = maxOf(0L, ChronoUnit.MONTHS.between(anchor, after))
                var candidate = anchor.plusMonths(k)
                while (!candidate.isAfter(after)) {
                    k++
                    candidate = anchor.plusMonths(k)
                }
                candidate
            }
        }
    }
}
