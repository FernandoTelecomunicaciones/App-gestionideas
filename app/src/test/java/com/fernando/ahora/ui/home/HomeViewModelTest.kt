package com.fernando.ahora.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.R
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.testing.UiEnv
import com.fernando.ahora.testing.nextMessage
import com.fernando.ahora.ui.common.UndoToken
import java.time.Instant
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Hoy (PRODUCT_SPEC 5.1 / 6.2): the ViewModel feeds TodayPlanner; it never decides Ahora itself. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HomeViewModelTest {
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

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = HomeViewModel(handle, env.repo, env.ticker, env.actions)

    private suspend fun HomeViewModel.loaded(predicate: (HomeUiState.Loaded) -> Boolean = { true }): HomeUiState.Loaded =
        withTimeout(5_000) { state.first { it is HomeUiState.Loaded && predicate(it) } as HomeUiState.Loaded }

    private suspend fun add(title: String, date: LocalDate? = null, priority: Priority? = null) =
        env.repo.create(TaskFields(title, dueDate = date, priority = priority))!!

    @Test
    fun beforeTheFirstEmission_nothingIsShown_soNoEmptyStateFlashes() {
        assertEquals(HomeUiState.Loading, vm().state.value)
    }

    @Test
    fun withNoTasks_itIsTheEmptyState() = runBlocking {
        val s = vm().loaded()
        assertNull(s.ahora)
        assertTrue(s.todayRows.isEmpty())
        assertTrue(s.isEmpty)
    }

    @Test
    fun ahora_isTheHighestPriorityDueTask() = runBlocking {
        add("normal", today, Priority.P2)
        val important = add("importante", today, Priority.P1)
        add("puede esperar", today, Priority.P3)

        val s = vm().loaded { it.ahora != null }

        assertEquals(important, s.ahora!!.id)
        assertEquals(2, s.todayRows.size)
    }

    @Test
    fun theProminentList_isCappedAtThree_andTheRestCollapsesBehindTheCounter() = runBlocking {
        repeat(6) { add("tarea $it", today, Priority.P2) }

        val s = vm().loaded { it.ahora != null }

        assertEquals(1, listOfNotNull(s.ahora).size)
        assertEquals(3, s.todayRows.size)
        assertEquals("the remaining 2 hide behind También pendiente", 2, s.rest.size)
        assertEquals(2, (s.rest).size)
    }

    @Test
    fun overdueTasks_areTreatedLikeToday_withNoSpecialCategory() = runBlocking {
        val overdue = add("de hace días", today.minusDays(5), Priority.P2)
        add("para mañana", today.plusDays(1), Priority.P1)

        val s = vm().loaded { it.ahora != null }

        // Overdue simply joins the due pool: it is Ahora, with no separate "vencidas" list and no counter (P-3, HOY-05).
        assertEquals(overdue, s.ahora!!.id)
        assertTrue(s.todayRows.isEmpty())
        assertEquals(1, s.rest.size)
    }

    @Test
    fun onlyInboxItems_meansNoAhora_butTheyStayReachableInTheRest() = runBlocking {
        add("idea 1")
        add("idea 2")

        val s = vm().loaded { it.rest.isNotEmpty() }

        assertNull(s.ahora)
        assertEquals(2, s.rest.size)
        assertFalse("only-inbox is not the empty state: the items are reachable", s.isEmpty)
    }

    @Test
    fun theRestList_isCollapsedByDefault_expandsOnTap_andSurvivesRecreation() = runBlocking {
        add("idea")
        val handle = SavedStateHandle()
        val vm = vm(handle)
        assertFalse(vm.loaded { it.rest.isNotEmpty() }.restExpanded)

        vm.toggleRest()
        assertTrue(vm.loaded { it.restExpanded }.restExpanded)

        assertTrue("survives rotation / process death", vm(handle).loaded { it.rest.isNotEmpty() }.restExpanded)
    }

    @Test
    fun leavingHoy_collapsesTheRestList() = runBlocking {
        add("idea")
        val vm = vm()
        vm.toggleRest()
        assertTrue(vm.loaded { it.restExpanded }.restExpanded)

        vm.collapseRest()

        assertFalse(vm.loaded { !it.restExpanded }.restExpanded)
    }

    @Test
    fun whenMidnightPasses_theClockTick_recomputesToday() = runBlocking {
        val a = add("a: vence hoy", today, Priority.P2)
        val b = add("b: vence mañana", today.plusDays(1), Priority.P1)
        val vm = vm()
        val before = vm.loaded { it.ahora != null }
        assertEquals(a, before.ahora!!.id)
        assertEquals(listOf(b), before.rest.map { it.id })

        env.time.instant = Instant.parse("2026-09-21T10:00:00Z")
        env.ticker.tick()

        val after = vm.loaded { it.today == today.plusDays(1) }
        assertEquals("both are due now and the P1 wins", b, after.ahora!!.id)
        assertEquals(listOf(a), after.todayRows.map { it.id })
    }

    @Test
    fun completingFromTheRow_removesIt_withUndo() = runBlocking {
        val id = add("hacer esto", today, Priority.P1)
        val vm = vm()
        vm.loaded { it.ahora?.id == id }

        vm.complete(id)

        val msg = env.nextMessage()
        assertEquals(R.string.snack_completed, msg.textRes)
        assertTrue(vm.loaded { it.ahora == null }.isEmpty)

        env.actions.undo(msg.undo as UndoToken.Complete).join()
        assertEquals(id, vm.loaded { it.ahora != null }.ahora!!.id)
    }
}
