package com.fernando.ahora.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.MainActivity
import com.fernando.ahora.testing.aTask
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** The Android edges: AlarmManager cursor, notification content, PendingIntent identity and deep links. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidRemindersRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scheduler: AlarmManagerScheduler
    private lateinit var notifier: AndroidReminderNotifier
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val shadowAlarms get() = shadowOf(alarmManager)

    private fun reminderTask(id: Long, rev: Int = 0) = aTask(
        id, "Preparar documentación", dueDate = LocalDate.of(2026, 9, 20), dueTime = LocalTime.of(18, 0),
        reminderEnabled = true,
    ).copy(reminderRevision = rev)

    @Before
    fun setUp() {
        // API 33+: the runtime permission is not granted by default. Our guard correctly refuses to post without it.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        scheduler = AlarmManagerScheduler(context)
        notifier = AndroidReminderNotifier(context)
        notifier.ensureChannel()
    }

    // ---- GC-03: a blocked reminders channel is "cannot notify", not "notified" -----------------

    private fun blockChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(AndroidReminderNotifier.CHANNEL_ID)
        nm.createNotificationChannel(
            android.app.NotificationChannel(AndroidReminderNotifier.CHANNEL_ID, "Recordatorios", NotificationManager.IMPORTANCE_NONE),
        )
    }

    @Test
    fun aBlockedRemindersChannel_meansCannotNotify_andSettingsSaysSo() {
        assertTrue(notifier.canNotify())
        blockChannel()
        assertFalse(notifier.canNotify())
        assertFalse(ReminderPermissions(context, scheduler).notificationsGranted())
        assertFalse(ReminderPermissions(context, scheduler).snapshot().notificationsGranted)
    }

    @Test
    fun postingIntoABlockedChannel_isAPermanentFailure_soNothingPretendsToBeDelivered() {
        blockChannel()
        try {
            notifier.post(reminderTask(7))
            org.junit.Assert.fail("must not silently post into a blocked channel")
        } catch (_: PermanentNotificationFailure) {
            // expected: the reconciler consumes the reminder deliberately and Ajustes shows the hint
        }
    }

    // ---- single alarm cursor: no duplicates, no PendingIntent collisions -------------------

    @Test
    fun arming_repeatedly_leavesExactlyOneAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.arm(Instant.parse("2026-09-20T16:00:00Z"))
        scheduler.arm(Instant.parse("2026-09-20T17:00:00Z"))
        scheduler.arm(Instant.parse("2026-09-21T08:00:00Z"))
        assertEquals(1, shadowAlarms.scheduledAlarms.size)
        assertEquals(Instant.parse("2026-09-21T08:00:00Z").toEpochMilli(), shadowAlarms.nextScheduledAlarm!!.triggerAtMs)
    }

    @Test
    fun cancel_removesTheCursor() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.arm(Instant.parse("2026-09-20T16:00:00Z"))
        scheduler.cancel()
        assertNull(shadowAlarms.nextScheduledAlarm)
    }

    @Test
    fun exactAlarmsDenied_fallsBackToAnInexactAllowWhileIdleAlarm_andDoesNotCrash() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(scheduler.canScheduleExact())
        scheduler.arm(Instant.parse("2026-09-20T16:00:00Z"))
        assertEquals(1, shadowAlarms.scheduledAlarms.size) // still scheduled, just not exact
    }

    @Test
    fun exactAlarmsAllowed_isReportedAndUsed() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(scheduler.canScheduleExact())
        scheduler.arm(Instant.parse("2026-09-20T16:00:00Z"))
        assertEquals(1, shadowAlarms.scheduledAlarms.size)
    }

    // ---- notification content (PRODUCT_SPEC §5.6) ------------------------------------------

    @Test
    fun theNotification_hasTheApprovedContentAndThreeActions() {
        val n = notifier.build(reminderTask(7))
        assertEquals("Preparar documentación", n.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(AndroidReminderNotifier.CHANNEL_ID, n.channelId)
        assertEquals(NotificationCompat.CATEGORY_REMINDER, n.category)
        assertEquals(listOf("HECHO", "+10 MIN", "ABRIR"), n.actions.map { it.title.toString() })
        val body = n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue("body says when it is due, with no urgency or blame: $body", body.startsWith("Vence a las"))
        assertEquals(7L, n.extras.getLong(AndroidReminderNotifier.EXTRA_TASK_ID))
    }

    @Test
    fun theChannel_isHighImportance_soItHeadsUp() {
        val ch = context.getSystemService(NotificationManager::class.java).getNotificationChannel(AndroidReminderNotifier.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
    }

    // ---- PendingIntent identity & the trampoline rule --------------------------------------

    @Test
    fun hechoAndSnooze_areImmutableBroadcasts_neverActivities() {
        val n = notifier.build(reminderTask(7))
        val (done, snooze, open) = n.actions.map { it.actionIntent }
        for (pi in listOf(done, snooze)) {
            val s = shadowOf(pi)
            assertTrue("HECHO / +10 MIN must be broadcasts", s.isBroadcastIntent)
            assertFalse("they must never start an activity (Android 12+ trampoline rule)", s.isActivityIntent)
            assertTrue("immutable", s.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        }
        // ABRIR is a DIRECT activity PendingIntent, not routed through a receiver
        val o = shadowOf(open)
        assertTrue(o.isActivityIntent)
        assertFalse(o.isBroadcastIntent)
        assertTrue(o.flags and PendingIntent.FLAG_IMMUTABLE != 0)
    }

    @Test
    fun abrir_targetsTheFocusDeepLink_withAnExplicitComponent() {
        val n = notifier.build(reminderTask(42))
        val intent = shadowOf(n.actions[2].actionIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component!!.className) // explicit, never implicit
        assertEquals("ahora://focus/42?rev=0", intent.data.toString()) // revision-aware identity (GB-07)
    }

    @Test
    fun abrir_isUniquePerRevision_soAnOldCardCannotDismissANewerOne() {
        fun abrirOf(rev: Int) = shadowOf(notifier.build(reminderTask(42, rev)).actions[2].actionIntent).savedIntent.data.toString()
        assertNotEquals(abrirOf(0), abrirOf(1))
    }

    @Test
    fun theBodyTap_deepLinksToHome_withAnExplicitComponent() {
        val n = notifier.build(reminderTask(42))
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component!!.className)
        assertEquals("ahora://home", intent.data.toString())
    }

    @Test
    fun actionIntents_areUniquePerTaskAndRevision_soTheyCannotCollide() {
        fun dataOf(pi: PendingIntent) = shadowOf(pi).savedIntent.data.toString()
        val a = notifier.build(reminderTask(1, rev = 0)).actions
        val b = notifier.build(reminderTask(2, rev = 0)).actions
        val c = notifier.build(reminderTask(1, rev = 1)).actions
        val all = listOf(a, b, c).flatMap { acts -> acts.take(2).map { dataOf(it.actionIntent) } }
        assertEquals("every task+revision+action has its own identity", all.size, all.toSet().size)
        assertEquals("ahora://reminder/1/0/done", dataOf(a[0].actionIntent))
        assertEquals("ahora://reminder/1/0/snooze", dataOf(a[1].actionIntent))
        assertNotEquals(dataOf(a[0].actionIntent), dataOf(c[0].actionIntent))
    }

    // ---- posting, tray and the sweep's inputs ----------------------------------------------

    @Test
    fun posting_isVisibleInTheTray_withTheRevisionItWasPostedFor() {
        notifier.post(reminderTask(9, rev = 3))
        assertEquals(listOf(ActiveCard(9, 3)), notifier.activeCards())
        notifier.cancel(9)
        assertTrue(notifier.activeCards().isEmpty())
    }

    @Test
    fun cancelAll_clearsEveryReminderCard() {
        notifier.post(reminderTask(1))
        notifier.post(reminderTask(2))
        notifier.cancelAll()
        assertTrue(notifier.activeCards().isEmpty())
    }

    @Test
    fun repostingTheSameTask_replacesTheCard_neverDuplicatesIt() {
        notifier.post(reminderTask(5, rev = 0))
        notifier.post(reminderTask(5, rev = 1))
        assertEquals(listOf(ActiveCard(5, 1)), notifier.activeCards())
    }

    @Test
    fun whenNotificationsAreDisabled_postingFailsPermanently_notTransiently() {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertFalse(notifier.canNotify())
        try {
            notifier.post(reminderTask(1))
            error("expected a permanent failure")
        } catch (e: PermanentNotificationFailure) {
            // expected: the reconciler consumes such items instead of retrying forever
        }
    }

    // ---- deep-link parsing (validation of external input) ----------------------------------

    @Test
    fun appLinks_parseOnlyTheApprovedShapes() {
        assertEquals(AppLink.Home, AppLinks.parse(Uri.parse("ahora://home")))
        assertEquals(AppLink.Focus(42), AppLinks.parse(Uri.parse("ahora://focus/42")))
        assertEquals(AppLink.Focus(42, 3), AppLinks.parse(Uri.parse("ahora://focus/42?rev=3")))
        assertNull(AppLinks.parse(Uri.parse("ahora://focus/42?rev=abc")))
        assertNull(AppLinks.parse(Uri.parse("ahora://focus/42?rev=-1")))
        assertNull(AppLinks.parse(Uri.parse("ahora://home?rev=1")))
        assertNull(AppLinks.parse(Uri.parse("ahora://focus/0")))
        assertNull(AppLinks.parse(Uri.parse("ahora://focus/-3")))
        assertNull(AppLinks.parse(Uri.parse("ahora://focus/abc")))
        assertNull(AppLinks.parse(Uri.parse("ahora://focus")))
        assertNull(AppLinks.parse(Uri.parse("ahora://focus/1/2")))
        assertNull(AppLinks.parse(Uri.parse("ahora://home/extra")))
        assertNull(AppLinks.parse(Uri.parse("https://focus/42")))
        assertNull(AppLinks.parse(Uri.parse("ahora://other")))
        assertNull(AppLinks.parse(null))
    }

    @Test
    fun reminderActionUris_roundTrip_andRejectGarbage() {
        val uri = ReminderIntents.actionUri(12, 5, "done")
        assertEquals(ReminderIntents.ActionTarget(12, 5), ReminderIntents.parseTarget(uri))
        assertNull(ReminderIntents.parseTarget(Uri.parse("ahora://reminder/x/5/done")))
        assertNull(ReminderIntents.parseTarget(Uri.parse("ahora://reminder/12/done")))
        assertNull(ReminderIntents.parseTarget(Uri.parse("ahora://focus/12/5/done")))
        assertNull(ReminderIntents.parseTarget(null))
    }
}
