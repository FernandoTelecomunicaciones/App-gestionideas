package com.fernando.ahora.core.format

import android.content.Context
import android.text.format.DateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Device 12/24 h preference (PRODUCT_SPEC §6.6). */
object TimeFormat {
    private val es = Locale.forLanguageTag("es-ES")
    private val h24 = DateTimeFormatter.ofPattern("HH:mm", es)
    private val h12 = DateTimeFormatter.ofPattern("h:mm a", es)

    fun format(context: Context, time: LocalTime): String =
        format(DateFormat.is24HourFormat(context), time)

    fun format(is24Hour: Boolean, time: LocalTime): String = time.format(if (is24Hour) h24 else h12)
}
