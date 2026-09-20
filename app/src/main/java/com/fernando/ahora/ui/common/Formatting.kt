package com.fernando.ahora.ui.common

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.fernando.ahora.R
import com.fernando.ahora.core.format.TimeFormat
import com.fernando.ahora.domain.model.Priority
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** PRODUCT_SPEC §6.6 date labels. Pure so the rule is unit-tested without Android. */
sealed interface DateLabel {
    data object Today : DateLabel
    data object Tomorrow : DateLabel
    data object None : DateLabel
    data class Other(val text: String) : DateLabel
}

object DateLabels {
    private val es = Locale.forLanguageTag("es-ES")
    private val sameYear = DateTimeFormatter.ofPattern("d MMM", es)
    private val otherYear = DateTimeFormatter.ofPattern("d MMM yyyy", es)

    /** "Hoy" · "Mañana" · `d MMM` (with the year when it is not the current one) · "Sin fecha". */
    fun of(date: LocalDate?, today: LocalDate): DateLabel = when {
        date == null -> DateLabel.None
        date == today -> DateLabel.Today
        date == today.plusDays(1) -> DateLabel.Tomorrow
        else -> DateLabel.Other(
            date.format(if (date.year == today.year) sameYear else otherYear).replace(".", ""),
        )
    }
}

@Composable
@ReadOnlyComposable
fun DateLabel.asText(): String = when (this) {
    DateLabel.Today -> stringResource(R.string.date_today)
    DateLabel.Tomorrow -> stringResource(R.string.date_tomorrow)
    DateLabel.None -> stringResource(R.string.date_none)
    is DateLabel.Other -> text
}

/** Device 12/24 h preference (PRODUCT_SPEC §6.6). */
@Composable
fun timeText(time: LocalTime): String = TimeFormat.format(LocalContext.current, time)

@Composable
fun is24Hour(): Boolean = DateFormat.is24HourFormat(LocalContext.current)

@Composable
@ReadOnlyComposable
fun Priority.longName(): String = when (this) {
    Priority.P1 -> stringResource(R.string.priority_p1_name)
    Priority.P2 -> stringResource(R.string.priority_p2_name)
    Priority.P3 -> stringResource(R.string.priority_p3_name)
}

/**
 * The M3 `DatePicker` speaks UTC-midnight milliseconds. Converting with the device zone is off by one day west of
 * UTC, so these two helpers are the ONLY place the conversion happens (ARCHITECTURE §5).
 */
object PickerDates {
    fun toPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun fromPickerMillis(millis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
}
