package com.fernando.ahora.domain.rules

import com.fernando.ahora.domain.model.Priority.P1
import com.fernando.ahora.domain.model.Priority.P2
import com.fernando.ahora.domain.model.Priority.P3
import com.fernando.ahora.testing.aTask
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayPlannerTest {
    private val today = LocalDate.of(2026, 9, 20)
    private val yesterday = today.minusDays(1)
    private val tomorrow = today.plusDays(1)

    private fun ids(tasks: List<com.fernando.ahora.domain.model.Task>) = tasks.map { it.id }

    @Test
    fun prototypeScenario_matchesApprovedDesign() {
        val tasks = listOf(
            aTask(1, dueDate = today, dueTime = LocalTime.of(18, 0), priority = P1), // Ahora
            aTask(2, dueDate = today, priority = P2),
            aTask(3, dueDate = today, priority = P3),
            aTask(4, dueDate = tomorrow, priority = P2),
            aTask(5, priority = P1), // decided (priority) but undated
            aTask(6), // inbox
            aTask(7), // inbox
        )
        val plan = TodayPlanner.plan(tasks, today)
        assertEquals(1L, plan.ahora?.id)
        assertEquals(listOf(2L, 3L), ids(plan.today))
        // rank: P1 undated, P2 tomorrow, then undecided by creation order
        assertEquals(listOf(5L, 4L, 6L, 7L), ids(plan.rest))
        assertEquals(4, plan.restCount)
    }

    @Test
    fun priorityBeatsDate_overdueLowPriorityDoesNotBeatTodayHighPriority() {
        val tasks = listOf(
            aTask(1, dueDate = yesterday, priority = P3),
            aTask(2, dueDate = today, priority = P1),
        )
        val plan = TodayPlanner.plan(tasks, today)
        assertEquals(2L, plan.ahora?.id)
        assertEquals(listOf(1L), ids(plan.today)) // overdue looks like any other due task
    }

    @Test
    fun overdueCountsAsDuePool() {
        val plan = TodayPlanner.plan(listOf(aTask(1, dueDate = today.minusDays(5), priority = P2)), today)
        assertEquals(1L, plan.ahora?.id)
        assertEquals(0, plan.restCount)
    }

    @Test
    fun nothingDue_fallsBackToBestDecidedTask_notInbox() {
        val tasks = listOf(
            aTask(1, dueDate = tomorrow, priority = P3),
            aTask(2, priority = P1),
            aTask(3), // inbox
        )
        val plan = TodayPlanner.plan(tasks, today)
        assertEquals(2L, plan.ahora?.id)
        assertEquals(emptyList<Long>(), ids(plan.today))
        assertEquals(listOf(1L, 3L), ids(plan.rest))
    }

    @Test
    fun onlyInboxItems_noAhora_butStillReachable() {
        val plan = TodayPlanner.plan(listOf(aTask(1), aTask(2)), today)
        assertNull(plan.ahora)
        assertEquals(2, plan.restCount)
    }

    @Test
    fun emptyAndDoneTasksAreIgnored() {
        assertNull(TodayPlanner.plan(emptyList(), today).ahora)
        val plan = TodayPlanner.plan(listOf(aTask(1, dueDate = today, done = true)), today)
        assertNull(plan.ahora)
        assertEquals(0, plan.restCount)
    }

    @Test
    fun capsTodayRowsAtThree_restGetsTheRest() {
        val tasks = (1L..6L).map { aTask(it, dueDate = today, priority = P2) }
        val plan = TodayPlanner.plan(tasks, today)
        assertEquals(1L, plan.ahora?.id)
        assertEquals(3, plan.today.size)
        assertEquals(2, plan.restCount)
    }

    @Test
    fun timeBreaksTiesWithinSamePriorityAndDate() {
        val tasks = listOf(
            aTask(1, dueDate = today, dueTime = LocalTime.of(17, 0), priority = P1),
            aTask(2, dueDate = today, dueTime = LocalTime.of(9, 0), priority = P1),
            aTask(3, dueDate = today, priority = P1), // no time sorts last
        )
        val plan = TodayPlanner.plan(tasks, today)
        assertEquals(2L, plan.ahora?.id)
        assertEquals(listOf(1L, 3L), ids(plan.today))
    }
}
