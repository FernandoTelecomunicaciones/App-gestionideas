package com.fernando.ahora.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of "now" and "where". The zone is evaluated on EVERY call: never cache
 * `Clock.systemDefaultZone()` (it freezes the zone at construction, breaking timezone changes — D-05).
 */
interface TimeProvider {
    fun now(): Instant
    fun zone(): ZoneId

    fun nowLocal(): LocalDateTime = LocalDateTime.ofInstant(now(), zone())
    fun today(): LocalDate = nowLocal().toLocalDate()
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
