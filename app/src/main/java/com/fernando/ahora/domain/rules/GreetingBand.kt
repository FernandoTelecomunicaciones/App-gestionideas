package com.fernando.ahora.domain.rules

import java.time.LocalTime

/** PRODUCT_SPEC §6.7: 05:00–11:59 morning, 12:00–19:59 afternoon, 20:00–04:59 night. */
enum class GreetingBand {
    MORNING, AFTERNOON, NIGHT;

    companion object {
        fun of(time: LocalTime): GreetingBand = when (time.hour) {
            in 5..11 -> MORNING
            in 12..19 -> AFTERNOON
            else -> NIGHT
        }
    }
}
