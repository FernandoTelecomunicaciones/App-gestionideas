package com.fernando.ahora.domain.rules

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingBandTest {
    @Test
    fun boundaries() {
        assertEquals(GreetingBand.NIGHT, GreetingBand.of(LocalTime.of(4, 59)))
        assertEquals(GreetingBand.MORNING, GreetingBand.of(LocalTime.of(5, 0)))
        assertEquals(GreetingBand.MORNING, GreetingBand.of(LocalTime.of(11, 59)))
        assertEquals(GreetingBand.AFTERNOON, GreetingBand.of(LocalTime.of(12, 0)))
        assertEquals(GreetingBand.AFTERNOON, GreetingBand.of(LocalTime.of(19, 59)))
        assertEquals(GreetingBand.NIGHT, GreetingBand.of(LocalTime.of(20, 0)))
        assertEquals(GreetingBand.NIGHT, GreetingBand.of(LocalTime.MIDNIGHT))
    }
}
