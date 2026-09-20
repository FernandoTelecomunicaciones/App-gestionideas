package com.fernando.ahora.domain.rules

import com.fernando.ahora.testing.aTask
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPlannerTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private val newYork = ZoneId.of("America/New_York")

    private fun reminderTask(date: LocalDate, time: LocalTime) =
        aTask(1, dueDate = date, dueTime = time, reminderEnabled = true)

    // ---- DST (PRODUCT_SPEC §6.1) ----------------------------------------------------------

    @Test
    fun dstGap_nonExistentLocalTimeFiresAtFirstValidInstantAfterTheGap() {
        // Madrid 2026-03-29: 02:00 -> 03:00. 02:30 does not exist -> 03:30 CEST = 01:30Z
        val fire = ReminderPlanner.dueInstant(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), madrid)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), fire)
    }

    @Test
    fun dstOverlap_ambiguousLocalTimeFiresAtTheEarlierOccurrence() {
        // Madrid 2026-10-25: 03:00 CEST -> 02:00 CET. 02:30 happens twice; earlier = +02:00 = 00:30Z
        val fire = ReminderPlanner.dueInstant(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30), madrid)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), fire)
    }

    @Test
    fun outsideDst_isPlainOffset() {
        assertEquals(
            Instant.parse("2026-01-15T17:00:00Z"),
            ReminderPlanner.dueInstant(LocalDate.of(2026, 1, 15), LocalTime.of(18, 0), madrid),
        )
        assertEquals(
            Instant.parse("2026-07-15T16:00:00Z"),
            ReminderPlanner.dueInstant(LocalDate.of(2026, 7, 15), LocalTime.of(18, 0), madrid),
        )
    }

    // ---- timezone change: floating local time, no data migration (D-05) -------------------

    @Test
    fun timezoneChange_sameStoredValuesGiveTheNewLocalInstant() {
        val task = reminderTask(LocalDate.of(2026, 9, 21), LocalTime.of(18, 0))
        assertEquals(Instant.parse("2026-09-21T16:00:00Z"), ReminderPlanner.nextFire(task, madrid))
        assertEquals(Instant.parse("2026-09-21T22:00:00Z"), ReminderPlanner.nextFire(task, newYork))
    }

    // ---- state machine (ARCHITECTURE §10.2) -----------------------------------------------

    @Test
    fun undefinedReminders_neverFire() {
        val d = LocalDate.of(2026, 9, 21)
        assertNull(ReminderPlanner.nextFire(aTask(1, dueDate = d, dueTime = LocalTime.NOON), madrid)) // toggle off
        assertNull(ReminderPlanner.nextFire(aTask(1, dueDate = d, reminderEnabled = true), madrid)) // no time
        assertNull(ReminderPlanner.nextFire(reminderTask(d, LocalTime.NOON).copy(done = true), madrid))
    }

    @Test
    fun consumedDueTimeReminder_yieldsNothing() {
        val t = reminderTask(LocalDate.of(2026, 9, 21), LocalTime.NOON).copy(reminderFiredAt = Instant.EPOCH)
        assertNull(ReminderPlanner.nextFire(t, madrid))
    }

    @Test
    fun snoozeOverridesDueTime_evenAfterTheReminderFired() {
        val snooze = Instant.parse("2026-09-21T10:10:00Z")
        val t = reminderTask(LocalDate.of(2026, 9, 21), LocalTime.NOON)
            .copy(reminderFiredAt = Instant.EPOCH, reminderSnoozeUntil = snooze)
        assertEquals(snooze, ReminderPlanner.nextFire(t, madrid))
    }

    // ---- missed-reminder policy (D-10) & due tolerance ------------------------------------

    @Test
    fun missedPolicy_deliverWithin12h_dropBeyond() {
        val fire = Instant.parse("2026-09-20T08:00:00Z")
        assertTrue(ReminderPlanner.shouldDeliver(fire, fire.plus(Duration.ofHours(12))))
        assertFalse(ReminderPlanner.shouldDeliver(fire, fire.plus(Duration.ofHours(12)).plusSeconds(1)))
    }

    @Test
    fun dueTolerance_twoSeconds() {
        val now = Instant.parse("2026-09-20T08:00:00Z")
        assertTrue(ReminderPlanner.isDue(now.plusSeconds(2), now))
        assertFalse(ReminderPlanner.isDue(now.plusSeconds(3), now))
    }

    @Test
    fun consumeIfPast_marksPastReminderConsumed_butLeavesFutureAlone() {
        val now = Instant.parse("2026-09-20T10:00:00Z") // 12:00 Madrid
        val past = reminderTask(LocalDate.of(2026, 9, 20), LocalTime.of(8, 0))
        val future = reminderTask(LocalDate.of(2026, 9, 20), LocalTime.of(18, 0))
        assertEquals(now, ReminderPlanner.consumeIfPast(past, madrid, now).reminderFiredAt)
        assertNull(ReminderPlanner.consumeIfPast(future, madrid, now).reminderFiredAt)
    }
}
