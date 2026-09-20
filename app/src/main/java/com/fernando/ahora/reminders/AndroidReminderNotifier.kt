package com.fernando.ahora.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fernando.ahora.MainActivity
import com.fernando.ahora.R
import com.fernando.ahora.core.format.TimeFormat
import com.fernando.ahora.domain.model.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reminder notification (PRODUCT_SPEC §5.6, ARCHITECTURE §10.6).
 *  * HECHO / +10 MIN: immutable broadcast PendingIntents with a unique data URI per task+revision+action —
 *    they never start an activity (Android 12+ trampoline rule).
 *  * ABRIR: a DIRECT activity PendingIntent (never through a receiver).
 *  * The body opens Hoy via an explicit `ahora://home` intent, handled cold and warm.
 *  * Extras carry (taskId, revision) so the tray can be swept against Room.
 */
@Singleton
class AndroidReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderNotifier {

    private val manager: NotificationManagerCompat get() = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_reminders_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_reminders_description) }
        manager.createNotificationChannel(channel)
    }

    override fun canNotify(): Boolean = manager.areNotificationsEnabled()

    @SuppressLint("MissingPermission") // guarded explicitly below
    override fun post(task: Task) {
        if (!canNotify()) throw PermanentNotificationFailure("notifications are disabled")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw PermanentNotificationFailure("POST_NOTIFICATIONS not granted")
        }
        ensureChannel()
        manager.notify(TAG, task.id.toInt(), build(task))
    }

    override fun cancel(taskId: Long) = manager.cancel(TAG, taskId.toInt())

    override fun cancelAll() {
        activeCards().forEach { cancel(it.taskId) }
    }

    override fun activeCards(): List<ActiveCard> {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.activeNotifications.orEmpty()
            .filter { it.tag == TAG }
            .mapNotNull { sbn ->
                val extras = sbn.notification.extras
                if (!extras.containsKey(EXTRA_REVISION)) null
                else ActiveCard(extras.getLong(EXTRA_TASK_ID), extras.getInt(EXTRA_REVISION))
            }
    }

    internal fun build(task: Task): android.app.Notification {
        val dueTime = task.dueTime
        val body = if (dueTime != null) {
            context.getString(R.string.notif_body, TimeFormat.format(context, dueTime))
        } else {
            context.getString(R.string.notif_body_no_time)
        }
        val extras = Bundle().apply {
            putLong(EXTRA_TASK_ID, task.id)
            putInt(EXTRA_REVISION, task.reminderRevision)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ahora)
            .setContentTitle(task.title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addExtras(extras)
            .setContentIntent(activityIntent(AppLinks.homeUri()))
            .addAction(0, context.getString(R.string.notif_action_done), broadcast(task, ReminderIntents.ACTION_DONE))
            .addAction(0, context.getString(R.string.notif_action_snooze), broadcast(task, ReminderIntents.ACTION_SNOOZE))
            .addAction(0, context.getString(R.string.notif_action_open), activityIntent(AppLinks.focusUri(task.id, task.reminderRevision)))
            .build()
    }

    private fun broadcast(task: Task, action: String): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .setData(ReminderIntents.actionUri(task.id, task.reminderRevision, if (action == ReminderIntents.ACTION_DONE) "done" else "snooze"))
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Explicit component + explicit deep-link data. NEW_TASK is required for a PendingIntent launched from the shade. */
    private fun activityIntent(uri: android.net.Uri): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        const val TAG = "reminder"
        const val EXTRA_TASK_ID = "com.fernando.ahora.extra.TASK_ID"
        const val EXTRA_REVISION = "com.fernando.ahora.extra.REVISION"
    }
}
