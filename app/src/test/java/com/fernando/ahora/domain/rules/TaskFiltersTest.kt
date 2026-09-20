package com.fernando.ahora.domain.rules

import com.fernando.ahora.domain.model.Priority.P1
import com.fernando.ahora.domain.model.Priority.P2
import com.fernando.ahora.testing.aTask
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskFiltersTest {
    private val d = LocalDate.of(2026, 9, 20)

    private val tasks = listOf(
        aTask(1, "Preparar máster", dueDate = d, priority = P1),
        aTask(2, "Llamar al banco", dueDate = d.plusDays(2), priority = P2),
        aTask(3, "Renovar el pasaporte", priority = P1),
        aTask(4, "Pensar nombre"),
        aTask(5, "Hecha", dueDate = d, done = true),
        aTask(6, "Atrasada", dueDate = d.minusDays(3)),
    )

    private fun ids(list: List<com.fernando.ahora.domain.model.Task>) = list.map { it.id }

    @Test
    fun pending_excludesDone_datedFirstThenUndatedNewestFirst() {
        // date asc (none last) → priority asc → newest first
        assertEquals(listOf(6L, 1L, 2L, 3L, 4L), ids(TaskFilters.apply(tasks, TaskFilter.PENDING)))
    }

    @Test
    fun upcoming_includesOverdueAndToday_soNothingDatedEverHides() {
        assertEquals(listOf(6L, 1L, 2L), ids(TaskFilters.apply(tasks, TaskFilter.UPCOMING)))
    }

    @Test
    fun noDate_isUndatedNewestFirst() {
        assertEquals(listOf(4L, 3L), ids(TaskFilters.apply(tasks, TaskFilter.NO_DATE)))
    }

    @Test
    fun p1_onlyP1() {
        assertEquals(listOf(1L, 3L), ids(TaskFilters.apply(tasks, TaskFilter.P1)))
    }

    @Test
    fun search_isAccentInsensitive_bothDirections() {
        assertEquals(listOf(1L), ids(TaskFilters.apply(tasks, TaskFilter.PENDING, "master")))
        assertEquals(listOf(1L), ids(TaskFilters.apply(tasks, TaskFilter.PENDING, "MÁSTER")))
    }

    @Test
    fun search_isCaseInsensitiveForNonAsciiUppercase() {
        val t = listOf(aTask(1, "Órdenes de compra"))
        assertEquals(listOf(1L), ids(TaskFilters.apply(t, TaskFilter.PENDING, "órdenes")))
        assertEquals(listOf(1L), ids(TaskFilters.apply(t, TaskFilter.PENDING, "ÓRDENES")))
    }

    @Test
    fun search_matchesNotes_andAppliesWithinTheActiveFilter() {
        val t = listOf(
            aTask(1, "A", notes = "llamar a Ana", priority = P1),
            aTask(2, "B", notes = "llamar a Luis"),
        )
        assertEquals(listOf(1L), ids(TaskFilters.apply(t, TaskFilter.P1, "llamar")))
    }

    @Test
    fun blankQuery_returnsFilterResult() {
        assertEquals(5, TaskFilters.apply(tasks, TaskFilter.PENDING, "   ").size)
    }
}
