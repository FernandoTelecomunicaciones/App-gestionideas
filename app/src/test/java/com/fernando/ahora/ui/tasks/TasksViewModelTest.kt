package com.fernando.ahora.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.domain.rules.TaskFilter
import com.fernando.ahora.testing.UiEnv
import com.fernando.ahora.testing.eventually
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Tareas (PRODUCT_SPEC 5.3): filtering and search are TaskFilters; this covers the wiring and the D-31 restriction. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TasksViewModelTest {
    private lateinit var env: UiEnv
    private val today = LocalDate.of(2026, 9, 20)

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        env = UiEnv()
    }

    @After
    fun tearDown() {
        env.close()
        Dispatchers.resetMain()
    }

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) =
        TasksViewModel(handle, env.repo, env.prefs, env.ticker, env.actions)

    private suspend fun TasksViewModel.loaded(predicate: (TasksUiState.Loaded) -> Boolean = { true }): TasksUiState.Loaded =
        withTimeout(5_000) { state.first { it is TasksUiState.Loaded && predicate(it) } as TasksUiState.Loaded }

    private suspend fun add(title: String, date: LocalDate? = null, priority: Priority? = null, notes: String = "") =
        env.repo.create(TaskFields(title, notes = notes, dueDate = date, priority = priority))!!

    @Test
    fun filters_selectTheRightTasks() = runBlocking {
        val dated = add("con fecha", today, Priority.P2)
        val urgent = add("urgente", null, Priority.P1)
        val undated = add("sin fecha")
        val vm = vm()

        assertEquals(setOf(dated, urgent, undated), vm.loaded { it.items.size == 3 }.items.map { it.id }.toSet())

        vm.onFilter(TaskFilter.UPCOMING)
        assertEquals(listOf(dated), vm.loaded { it.filter == TaskFilter.UPCOMING }.items.map { it.id })

        vm.onFilter(TaskFilter.NO_DATE)
        assertEquals(setOf(urgent, undated), vm.loaded { it.filter == TaskFilter.NO_DATE }.items.map { it.id }.toSet())

        vm.onFilter(TaskFilter.P1)
        assertEquals(listOf(urgent), vm.loaded { it.filter == TaskFilter.P1 }.items.map { it.id })
    }

    @Test
    fun theSelectedFilter_isPersisted_andRestored() = runBlocking {
        val vm = vm()
        vm.onFilter(TaskFilter.P1)
        eventually { env.prefs.filter.value == TaskFilter.P1 }

        assertEquals(TaskFilter.P1, vm().loaded().filter)
    }

    @Test
    fun search_isCaseAndAccentInsensitive_overTitleAndNotes() = runBlocking {
        val truck = add("Alquilar el Camión")
        val other = add("otra cosa", notes = "recoger el camion del taller")
        add("nada que ver")
        val vm = vm()

        vm.onQuery("CAMION")

        val found = vm.loaded { it.query == "CAMION" && it.items.size == 2 }.items.map { it.id }.toSet()
        assertEquals(setOf(truck, other), found)
    }

    @Test
    fun search_appliesWithinTheActiveFilter() = runBlocking {
        add("comprar pan", today)
        val undated = add("comprar leche")
        val vm = vm()
        vm.onFilter(TaskFilter.NO_DATE)

        vm.onQuery("comprar")

        assertEquals(listOf(undated), vm.loaded { it.query == "comprar" && it.filter == TaskFilter.NO_DATE }.items.map { it.id })
    }

    @Test
    fun aQueryThatMatchesNothing_isAnEmptyList() = runBlocking {
        add("algo")
        val vm = vm()
        vm.onQuery("zzz")
        assertTrue(vm.loaded { it.query == "zzz" }.items.isEmpty())
    }

    @Test
    fun theQuery_survivesRecreation() {
        val handle = SavedStateHandle()
        vm(handle).onQuery("dentista")
        assertEquals("dentista", vm(handle).query)
    }

    @Test
    fun completing_movesTheTaskToTheCompletedView_andReopeningBringsItBack() = runBlocking {
        val id = add("terminar informe")
        val vm = vm()
        vm.loaded { it.items.size == 1 }

        vm.complete(id)
        assertTrue(vm.loaded { it.items.isEmpty() }.items.isEmpty())

        vm.setShowCompleted(true)
        assertEquals(listOf(id), vm.loaded { it.showCompleted && it.items.isNotEmpty() }.items.map { it.id })

        vm.reopen(id)
        assertTrue(vm.loaded { it.showCompleted && it.items.isEmpty() }.items.isEmpty())
        vm.setShowCompleted(false)
        assertEquals(listOf(id), vm.loaded { !it.showCompleted && it.items.isNotEmpty() }.items.map { it.id })
    }

    @Test
    fun aCompletedRecurringOccurrence_cannotBeReopened_soTheSeriesNeverForks() = runBlocking {
        val id = env.repo.create(TaskFields("regar", dueDate = today, recurrence = Recurrence.DAILY))!!
        val vm = vm()
        vm.loaded { it.items.size == 1 }

        vm.complete(id) // creates exactly one successor
        vm.loaded { it.items.size == 1 && it.items.single().id != id }

        vm.setShowCompleted(true)
        val completed = vm.loaded { it.showCompleted && it.items.size == 1 }.items.single()
        assertEquals(id, completed.id)
        assertTrue("the UI shows this checkbox read-only", completed.recurrence != Recurrence.NONE)

        vm.reopen(id) // what a (hypothetical) tap would do: the repository refuses (D-31)
        eventually(timeoutMs = 1_000, message = "the refused reopen to settle") { true }
        kotlinx.coroutines.delay(300)

        assertTrue(env.repo.get(id)!!.done)
        assertEquals("still exactly one open occurrence", 1, env.repo.observePending().first().size)
    }

    @Test
    fun theCompletedView_isNotShownByDefault() = runBlocking {
        val id = add("hecha")
        env.repo.complete(id)
        add("pendiente")
        val s = vm().loaded()
        assertFalse(s.showCompleted)
        assertEquals(listOf("pendiente"), s.items.map { it.title })
    }
}
