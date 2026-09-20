package com.fernando.ahora.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single-alarm cursor. One PendingIntent identity (explicit component + fixed action, no extras), so
 * arming replaces the previous alarm — duplicates and PendingIntent collisions are impossible by construction.
 */
@Singleton
class AlarmManagerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderScheduler {

    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun arm(at: Instant) {
        val pi = pendingIntent()
        val triggerAt = at.toEpochMilli()
        if (canScheduleExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                return
            } catch (e: SecurityException) {
                // Permission revoked between the capability check and the call: degrade, never crash.
                Log.w(TAG, "exact alarm refused, falling back to inexact", e)
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    override fun cancel() {
        alarmManager.cancel(pendingIntent())
    }

    companion object {
        const val ACTION_FIRE = "com.fernando.ahora.action.FIRE_REMINDERS"
        private const val REQUEST_CODE = 1
        private const val TAG = "AhoraReminders"
    }
}
