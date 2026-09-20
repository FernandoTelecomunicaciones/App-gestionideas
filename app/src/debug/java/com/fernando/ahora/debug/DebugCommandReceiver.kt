package com.fernando.ahora.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fernando.ahora.core.time.TimeProvider
import com.fernando.ahora.di.ApplicationScope
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.Recurrence
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.reminders.ReminderPermissions
import com.fernando.ahora.reminders.ReminderReconciler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugEntryPoint {
    fun repository(): TaskRepository
    fun reconciler(): ReminderReconciler
    fun time(): TimeProvider
    fun permissions(): ReminderPermissions
    @ApplicationScope fun scope(): CoroutineScope
}

/**
 * adb-driven test harness (debug builds only):
 *   adb shell am broadcast -a com.fernando.ahora.debug.CMD -n com.fernando.ahora/.debug.DebugCommandReceiver \
 *       --es cmd create --es title "X" --ei in_minutes 2 --ez reminder true
 * Commands: caps | create | list | complete --el id N | delete --el id N | postpone --el id N | reconcile
 * Output goes to logcat, tag AhoraDebug.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ep = EntryPointAccessors.fromApplication(context.applicationContext, DebugEntryPoint::class.java)
        val pending = goAsync()
        ep.scope().launch {
            try {
                handle(ep, intent)
            } catch (e: Exception) {
                Log.e(TAG, "command failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(ep: DebugEntryPoint, intent: Intent) {
        val repo = ep.repository()
        when (val cmd = intent.getStringExtra("cmd")) {
            "create" -> {
                val time = ep.time()
                val minutes = intent.getIntExtra("in_minutes", 2)
                val explicitDate = intent.getStringExtra("date")
                val explicitTime = intent.getStringExtra("time")
                val due = if (explicitDate != null && explicitTime != null) {
                    java.time.LocalDateTime.of(java.time.LocalDate.parse(explicitDate), java.time.LocalTime.parse(explicitTime))
                } else {
                    time.nowLocal().plusMinutes(minutes.toLong()).truncatedTo(ChronoUnit.MINUTES)
                }
                val id = repo.create(
                    TaskFields(
                        title = intent.getStringExtra("title") ?: "Debug",
                        dueDate = due.toLocalDate(),
                        dueTime = due.toLocalTime(),
                        reminderEnabled = intent.getBooleanExtra("reminder", true),
                        priority = intent.getIntExtra("priority", 0).let(Priority::fromValue),
                        recurrence = Recurrence.fromCode(intent.getStringExtra("recurrence")),
                    ),
                )
                Log.i(TAG, "created id=$id due=$due zone=${time.zone()} now=${time.now()}")
            }
            "list" -> repo.exportAll().forEach {
                Log.i(
                    TAG,
                    "task id=${it.id} '${it.title}' due=${it.dueDate}T${it.dueTime} rev=${it.reminderRevision} " +
                        "rem=${it.reminderEnabled} fired=${it.reminderFiredAt} snooze=${it.reminderSnoozeUntil} " +
                        "done=${it.done} rec=${it.recurrence}",
                )
            }
            "complete" -> Log.i(TAG, "complete -> ${repo.complete(intent.getLongExtra("id", -1))}")
            "delete" -> Log.i(TAG, "delete -> ${repo.delete(intent.getLongExtra("id", -1))?.id}")
            "postpone" -> Log.i(TAG, "postpone -> ${repo.postpone(intent.getLongExtra("id", -1))}")
            "caps" -> {
                val p = ep.permissions()
                Log.i(
                    TAG,
                    "caps sdk=${android.os.Build.VERSION.SDK_INT} canScheduleExact=${p.exactAlarmsAllowed()} " +
                        "notificationsGranted=${p.notificationsGranted()} needsRuntimeNotif=${p.needsRuntimeNotificationPermission()} " +
                        "zone=${ep.time().zone()}",
                )
            }
            "reconcile" -> {
                ep.reconciler().reconcileNow("debug")
                Log.i(TAG, "reconciled")
            }
            else -> Log.w(TAG, "unknown cmd=$cmd")
        }
    }

    private companion object {
        const val TAG = "AhoraDebug"
    }
}
