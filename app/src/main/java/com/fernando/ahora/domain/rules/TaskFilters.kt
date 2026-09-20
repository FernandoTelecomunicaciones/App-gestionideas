package com.fernando.ahora.domain.rules

import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Task
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

enum class TaskFilter { PENDING, UPCOMING, NO_DATE, P1 }

/** Accent- and case-insensitive text matching (SQLite LIKE only folds ASCII — D-17). */
object TextNormalizer {
    private val combiningMarks = Regex("\\p{Mn}+")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase(Locale.ROOT)
}

/** PRODUCT_SPEC §5.3 filter predicates and sort orders. All inputs are pending tasks. */
object TaskFilters {
    private val byDateThenPriorityThenNewest: Comparator<Task> = compareBy<Task>(
        { it.dueDate ?: LocalDate.MAX },
        { it.priority?.value ?: 4 },
    ).thenByDescending { it.createdAt }.thenByDescending { it.id }

    private val byDateThenTime: Comparator<Task> = compareBy<Task>(
        { it.dueDate ?: LocalDate.MAX },
        { it.dueTime ?: LocalTime.MAX },
    ).thenBy { it.createdAt }.thenBy { it.id }

    private val newestFirst: Comparator<Task> = compareByDescending<Task> { it.createdAt }.thenByDescending { it.id }

    private val byDateThenNewest: Comparator<Task> = compareBy<Task> { it.dueDate ?: LocalDate.MAX }
        .thenByDescending { it.createdAt }.thenByDescending { it.id }

    fun apply(pending: List<Task>, filter: TaskFilter, query: String = ""): List<Task> {
        val q = TextNormalizer.normalize(query.trim())
        val base = pending.filter { !it.done }
        val filtered = when (filter) {
            TaskFilter.PENDING -> base.sortedWith(byDateThenPriorityThenNewest)
            TaskFilter.UPCOMING -> base.filter { it.dueDate != null }.sortedWith(byDateThenTime)
            TaskFilter.NO_DATE -> base.filter { it.dueDate == null }.sortedWith(newestFirst)
            TaskFilter.P1 -> base.filter { it.priority == Priority.P1 }.sortedWith(byDateThenNewest)
        }
        if (q.isEmpty()) return filtered
        return filtered.filter { matches(it, q) }
    }

    /** [normalizedQuery] must already be normalised. Matches title and notes. */
    fun matches(task: Task, normalizedQuery: String): Boolean =
        TextNormalizer.normalize(task.title).contains(normalizedQuery) ||
            TextNormalizer.normalize(task.notes).contains(normalizedQuery)
}
