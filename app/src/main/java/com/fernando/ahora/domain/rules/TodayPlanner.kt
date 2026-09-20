package com.fernando.ahora.domain.rules

import com.fernando.ahora.domain.model.Task
import java.time.LocalDate
import java.time.LocalTime

/** What Hoy shows. [restCount] = tasks behind "También pendiente · N". */
data class HoyPlan(val ahora: Task?, val today: List<Task>, val rest: List<Task>) {
    val restCount: Int get() = rest.size
}

/** PRODUCT_SPEC §6.2. Deterministic, computed, never stored (D-15). */
object TodayPlanner {
    /** priority (P1 < P2 < P3 < none), then date, then time, then creation order. */
    val rank: Comparator<Task> = compareBy<Task>(
        { it.priority?.value ?: 4 },
        { it.dueDate ?: LocalDate.MAX },
        { it.dueTime ?: LocalTime.MAX },
        { it.createdAt },
        { it.id },
    )

    fun plan(tasks: List<Task>, today: LocalDate): HoyPlan {
        val pending = tasks.filter { !it.done }
        val decided = pending.filter { !it.isInbox }
        val duePool = decided.filter { it.dueDate != null && !it.dueDate.isAfter(today) }
        val ahoraPool = duePool.ifEmpty { decided }
        val ahora = ahoraPool.minWithOrNull(rank)
        val todayRows = duePool.filter { it.id != ahora?.id }.sortedWith(rank).take(3)
        val shown = buildSet {
            ahora?.let { add(it.id) }
            todayRows.forEach { add(it.id) }
        }
        val rest = pending.filter { it.id !in shown }.sortedWith(rank)
        return HoyPlan(ahora, todayRows, rest)
    }
}
