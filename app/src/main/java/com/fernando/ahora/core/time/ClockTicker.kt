package com.fernando.ahora.core.time

import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Emits the current local date-time now and then at every minute boundary, only while collected.
 * Drives "today", the greeting and time-zone/clock changes for Hoy (ARCHITECTURE §9.2).
 */
interface ClockTicker {
    fun minutes(): Flow<LocalDateTime>
}

@Singleton
class SystemClockTicker @Inject constructor(private val time: TimeProvider) : ClockTicker {
    override fun minutes(): Flow<LocalDateTime> = flow {
        while (true) {
            val now = time.nowLocal()
            emit(now)
            val intoMinute = now.second * 1_000L + now.nano / 1_000_000L
            // +50 ms so we land safely after the boundary; a clock jump is healed on the next tick.
            delay((60_000L - intoMinute).coerceIn(1_000L, 60_000L) + 50L)
        }
    }
}
