package com.fernando.ahora.reminders

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.fernando.ahora.domain.ReminderPermissionSource
import com.fernando.ahora.domain.ReminderPermissionState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the two permissions that shape reminder quality (PRODUCT_SPEC §8). Never assumes either exists. */
@Singleton
class ReminderPermissions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: AlarmManagerScheduler,
) : ReminderPermissionSource {
    override fun snapshot() = ReminderPermissionState(
        notificationsGranted = notificationsGranted(),
        canAskNotifications = needsRuntimeNotificationPermission(),
        exactAlarmsAllowed = exactAlarmsAllowed(),
        exactAlarmsNeedSpecialAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    )

    /** POST_NOTIFICATIONS (API 33+), the app-level toggle and the reminders channel (GC-03). */
    fun notificationsGranted(): Boolean = AndroidReminderNotifier.canPostReminders(context)

    /** True when the runtime permission can still be requested (API 33+ and not yet granted). */
    fun needsRuntimeNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    fun exactAlarmsAllowed(): Boolean = scheduler.canScheduleExact()

    /** Intent to the system screen where exact alarms can be granted (API 31+), else null. */
    fun exactAlarmSettingsIntent(): Intent? = exactAlarmSettingsIntent(context.packageName)

    fun notificationSettingsIntent(): Intent = notificationSettingsIntent(context.packageName)

    fun channelSettingsIntent(): Intent = channelSettingsIntent(context.packageName)

    /** Pure intent builders, so the UI (which only has a Context) can launch the same system screens. */
    companion object {
        fun exactAlarmSettingsIntent(packageName: String): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
            } else {
                null
            }

        fun notificationSettingsIntent(packageName: String): Intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

        fun channelSettingsIntent(packageName: String): Intent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, AndroidReminderNotifier.CHANNEL_ID)
    }
}
