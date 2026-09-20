package com.fernando.ahora.ui.navigation

import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.reminders.AppLink
import com.fernando.ahora.reminders.LinkDestination
import com.fernando.ahora.reminders.NotificationLinkHandler
import com.fernando.ahora.testing.FakeNotifier
import com.fernando.ahora.testing.UiEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Root navigation (ARCHITECTURE 8): bottom bar, notification body -> Hoy, ABRIR -> Foco with the `[Home, Focus]`
 * back stack, from every starting point. The destination is decided by NotificationLinkHandler (already tested for
 * validation and card dismissal); these tests cover what happens to the back stack.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NavigationTest {
    private lateinit var nav: TestNavHostController
    private lateinit var env: UiEnv

    @Before
    fun setUp() {
        env = UiEnv()
        nav = TestNavHostController(ApplicationProvider.getApplicationContext()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            setGraph(
                createGraph(startDestination = Home) {
                    composable<Home> { }
                    composable<Inbox> { }
                    composable<Tasks> { }
                    composable<Focus> { }
                    composable<Settings> { }
                },
                null,
            )
        }
    }

    @After
    fun tearDown() = env.close()

    private fun stack(): List<String> = nav.currentBackStack.value
        .filter { it.destination !is NavGraph }
        .map {
            val d = it.destination
            when {
                d.hasRoute<Home>() -> "Home"
                d.hasRoute<Inbox>() -> "Inbox"
                d.hasRoute<Tasks>() -> "Tasks"
                d.hasRoute<Settings>() -> "Settings"
                d.hasRoute<Focus>() -> "Focus(${it.toRoute<Focus>().taskId})"
                else -> "?"
            }
        }

    // ---- bottom bar ----------------------------------------------------------------------------------------

    @Test
    fun startsOnHoy() {
        assertEquals(listOf("Home"), stack())
    }

    @Test
    fun bottomBar_switchesBetweenTheThreeTabs() {
        nav.switchTab(Inbox)
        assertEquals(listOf("Home", "Inbox"), stack())
        nav.switchTab(Tasks)
        assertEquals("Bandeja and Tareas replace each other above Hoy", listOf("Home", "Tasks"), stack())
        nav.switchTab(Home)
        assertEquals(listOf("Home"), stack())
    }

    @Test
    fun systemBack_fromBandejaOrTareas_landsOnHoy() {
        nav.switchTab(Inbox)
        nav.popBackStack()
        assertEquals(listOf("Home"), stack())

        nav.switchTab(Tasks)
        nav.popBackStack()
        assertEquals(listOf("Home"), stack())
    }

    @Test
    fun chrome_isShownOnTheTabsOnly() {
        assertTrue(nav.currentDestination.isTab())
        nav.switchTab(Inbox); assertTrue(nav.currentDestination.isTab())
        nav.switchTab(Tasks); assertTrue(nav.currentDestination.isTab())
        nav.navigate(Settings); assertFalse("no bottom bar or FAB on Ajustes", nav.currentDestination.isTab())
        nav.popBackStack()
        nav.openFocus(1); assertFalse("no bottom bar or FAB on Foco", nav.currentDestination.isTab())
    }

    // ---- Empezar / Foco --------------------------------------------------------------------------------------

    @Test
    fun empezar_pushesFocus_aboveHoy() {
        nav.openFocus(7)
        assertEquals(listOf("Home", "Focus(7)"), stack())
    }

    @Test
    fun leavingFocus_returnsToHoy_andIsIdempotent() {
        nav.openFocus(7)
        nav.leaveFocusToHome()
        nav.leaveFocusToHome() // e.g. Terminar AND the "task is done" observer both fire
        assertEquals(listOf("Home"), stack())
    }

    @Test
    fun leavingFocus_doesNothing_whenNotInFocus() {
        nav.switchTab(Tasks)
        nav.leaveFocusToHome()
        assertEquals(listOf("Home", "Tasks"), stack())
    }

    // ---- notification body (-> Hoy) ---------------------------------------------------------------------------

    @Test
    fun bodyTap_fromAnyDestination_endsOnHoyAlone() {
        for (start in listOf<Any>(Inbox, Tasks, Settings)) {
            nav.applyLink(LinkDestination.Home)
            if (start == Settings) nav.navigate(Settings) else nav.switchTab(start)
            nav.applyLink(LinkDestination.Home)
            assertEquals("from $start", listOf("Home"), stack())
        }
    }

    @Test
    fun bodyTap_whileFocusIsOpen_returnsToHoy() {
        nav.openFocus(3)
        nav.applyLink(LinkDestination.Home)
        assertEquals(listOf("Home"), stack())
    }

    @Test
    fun bodyTap_whenAlreadyOnHoy_changesNothing() {
        nav.applyLink(LinkDestination.Home)
        assertEquals(listOf("Home"), stack())
    }

    // ---- ABRIR (-> Foco, back stack [Home, Focus]) --------------------------------------------------------------

    @Test
    fun abrir_coldStart_buildsHomeThenFocus_andBackLandsOnHoy() {
        nav.applyLink(LinkDestination.Focus(5))
        assertEquals(listOf("Home", "Focus(5)"), stack())

        nav.popBackStack()
        assertEquals(listOf("Home"), stack())
    }

    @Test
    fun abrir_warmStart_fromEachTab_andFromAjustes_buildsTheSameBackStack() {
        for (start in listOf<Any>(Inbox, Tasks, Settings)) {
            nav.applyLink(LinkDestination.Home)
            if (start == Settings) nav.navigate(Settings) else nav.switchTab(start)
            nav.applyLink(LinkDestination.Focus(5))
            assertEquals("from $start", listOf("Home", "Focus(5)"), stack())
        }
    }

    @Test
    fun abrir_forAnotherTask_whileInFocus_replacesIt() {
        nav.applyLink(LinkDestination.Focus(5))
        nav.applyLink(LinkDestination.Focus(6))
        assertEquals(listOf("Home", "Focus(6)"), stack())
    }

    @Test
    fun abrir_forTheFocusAlreadyOpen_doesNotRestartIt() {
        nav.applyLink(LinkDestination.Focus(5))
        val entry = nav.currentBackStackEntry

        nav.applyLink(LinkDestination.Focus(5))

        assertEquals(listOf("Home", "Focus(5)"), stack())
        assertSame("same entry, so the running timer keeps its ViewModel", entry, nav.currentBackStackEntry)
    }

    // ---- validation + navigation together (invalid / deleted task) ------------------------------------------------

    private fun handler() = NotificationLinkHandler(env.repo, FakeNotifier())

    @Test
    fun abrir_forADeletedOrCompletedTask_endsOnHoy() = runBlocking {
        val gone = env.repo.create(TaskFields("borrada"))!!
        env.repo.delete(gone)
        val done = env.repo.create(TaskFields("hecha"))!!
        env.repo.complete(done)

        for (id in listOf(gone, done, 999L)) {
            val destination = handler().resolve(AppLink.Focus(id, revision = 0))!!
            nav.switchTab(Tasks)
            nav.applyLink(destination)
            assertEquals("task $id", listOf("Home"), stack())
        }
    }

    @Test
    fun abrir_forALiveTask_endsOnFocus() = runBlocking {
        val id = env.repo.create(TaskFields("viva"))!!
        val destination = handler().resolve(AppLink.Focus(id, revision = 0))!!
        nav.applyLink(destination)
        assertEquals(listOf("Home", "Focus($id)"), stack())
    }
}
