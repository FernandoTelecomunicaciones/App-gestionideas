package com.fernando.ahora

import android.app.Application
import com.fernando.ahora.reminders.AndroidReminderNotifier
import com.fernando.ahora.reminders.ReminderReconciler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AhoraApp : Application() {
    @Inject lateinit var notifier: AndroidReminderNotifier
    @Inject lateinit var reconciler: ReminderReconciler

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannel()
        // Room is the truth; the alarm is a derived cache. Every process start heals it (ARCHITECTURE §10.4).
        reconciler.request("app-start")
    }
}
