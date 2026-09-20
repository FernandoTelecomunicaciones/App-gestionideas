package com.fernando.ahora.ui.common

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    private val today = LocalDate.of(2026, 9, 20)

    @Test
    fun dateLabels_followTheSpec() {
        assertEquals(DateLabel.None, DateLabels.of(null, today))
        assertEquals(DateLabel.Today, DateLabels.of(today, today))
        assertEquals(DateLabel.Tomorrow, DateLabels.of(today.plusDays(1), today))
        // Overdue dates use the same neutral label as any other date: no red, no "vencida" (P-3, HOY-05).
        assertEquals(DateLabel.Other("15 sept"), DateLabels.of(LocalDate.of(2026, 9, 15), today).normalizedForTest())
    }

    @Test
    fun dateLabel_addsTheYear_whenItIsNotTheCurrentOne() {
        val label = DateLabels.of(LocalDate.of(2027, 1, 3), today) as DateLabel.Other
        assertEquals(true, label.text.endsWith("2027"))
    }

    /** ICU/CLDR versions abbreviate September as "sep" or "sept"; both are valid es-ES. */
    private fun DateLabel.normalizedForTest(): DateLabel =
        if (this is DateLabel.Other) DateLabel.Other(text.replace("sep ", "sept ")) else this

    @Test
    fun datePicker_utcMidnight_convertsWithoutAnOffByOneDay() {
        // M3 DatePicker speaks UTC-midnight millis. Converting with a device zone west of UTC would give the day before.
        val date = LocalDate.of(2026, 9, 20)
        val millis = PickerDates.toPickerMillis(date)
        assertEquals(1_789_862_400_000L, millis)
        assertEquals(date, PickerDates.fromPickerMillis(millis))
        val westOfUtc = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.of("America/Los_Angeles")).toLocalDate()
        assertEquals("the naive conversion is wrong west of UTC; that is why the helper exists", date.minusDays(1), westOfUtc)
    }
}
