package com.fernando.ahora.reminders

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.data.TaskRepositoryImpl
import com.fernando.ahora.data.local.AhoraDatabase
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.testing.FakeNotifier
import com.fernando.ahora.testing.FakeTimeProvider
import com.fernando.ahora.testing.RecordingReminderSync
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What an intent that reaches the exported activity may do (cold and warm start share this path).
 * The activity itself only forwards `intent.data`; the navigation host arrives with M3.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationLinkHandlerTest {
    private val time = FakeTimeProvider(Instant.parse("2026-09-20T10:00:00Z"))
    private val notifier = FakeNotifier()
    private lateinit var db: AhoraDatabase
    private lateinit var repo: TaskRepositoryImpl
    private lateinit var handler: NotificationLinkHandler
    private val tomorrow = LocalDate.of(2026, 9, 21)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AhoraDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = TaskRepositoryImpl(db, db.taskDao(), time, RecordingReminderSync())
        handler = NotificationLinkHandler(repo, notifier)
    }

    @After
    fun tearDown() = db.close()

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private suspend fun openTask() =
        repo.create(TaskFields("x", dueDate = tomorrow, dueTime = LocalTime.of(9, 0), reminderEnabled = true))!!

    @Test
    fun bodyTap_resolvesToHome() = run {
        assertEquals(LinkDestination.Home, handler.resolve(AppLink.Home))
    }

    @Test
    fun anUnrecognisedLink_resolvesToNothing_soAnyAppSendingGarbageIsIgnored() = run {
        assertNull(handler.resolve(null))
    }

    @Test
    fun abrir_forAnOpenTask_resolvesToFocus_andDismissesItsOwnCard() = run {
        val id = openTask()
        notifier.cards += ActiveCard(id, 0)
        assertEquals(LinkDestination.Focus(id), handler.resolve(AppLink.Focus(id, revision = 0)))
        assertTrue("action buttons do not auto-cancel, so ABRIR must", notifier.cards.isEmpty())
    }

    @Test
    fun abrir_fromAnOldCard_neverDismissesANewerOne() = run {
        val id = openTask()
        notifier.cards += ActiveCard(id, 1) // the newer card
        assertEquals(LinkDestination.Focus(id), handler.resolve(AppLink.Focus(id, revision = 0)))
        assertEquals(listOf(ActiveCard(id, 1)), notifier.cards)
    }

    @Test
    fun aFocusLinkWithoutARevision_opensFocus_butNeverTouchesTheTray() = run {
        val id = openTask()
        notifier.cards += ActiveCard(id, 0)
        assertEquals(LinkDestination.Focus(id), handler.resolve(AppLink.Focus(id)))
        assertEquals(1, notifier.cards.size)
    }

    @Test
    fun aFocusLinkForAMissingTask_fallsBackToHome() = run {
        assertEquals(LinkDestination.Home, handler.resolve(AppLink.Focus(999, revision = 0)))
    }

    @Test
    fun aFocusLinkForACompletedTask_fallsBackToHome() = run {
        val id = openTask()
        repo.complete(id)
        assertEquals(LinkDestination.Home, handler.resolve(AppLink.Focus(id, revision = 0)))
    }
}
