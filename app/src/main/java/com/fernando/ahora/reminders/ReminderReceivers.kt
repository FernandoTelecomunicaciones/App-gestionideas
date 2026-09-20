package com.fernando.ahora.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fernando.ahora.di.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "AhoraReminders"

/**
 * Receivers resolve their collaborators through a Hilt entry point (Kotlin cannot call `super.onReceive` on an
 * `@AndroidEntryPoint` receiver). Same singletons as the rest of the app.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun reconciler(): ReminderReconciler
    fun actionHandler(): ReminderActionHandler
    @ApplicationScope fun applicationScope(): CoroutineScope
}

private fun Context.entryPoint(): ReminderEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, ReminderEntryPoint::class.java)

/** Receivers get ~10 s; stay well inside it, always finish, and never let an exception escape (R2-3). */
internal fun BroadcastReceiver.goAsyncBounded(
    scope: CoroutineScope,
    timeoutMs: Long = 8_000,
    block: suspend () -> Unit,
) {
    val pending = goAsync()
    scope.launch {
        try {
            withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "receiver work exceeded ${timeoutMs}ms; the cursor was armed first", e)
        } catch (e: Exception) {
            Log.e(TAG, "receiver work failed", e)
        } finally {
            pending.finish()
        }
    }
}

/** The single alarm cursor fired: deliver everything due, re-arm the next one. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ep = context.entryPoint()
        goAsyncBounded(ep.applicationScope()) { ep.reconciler().reconcileNow("alarm") }
    }
}

/** HECHO / +10 MIN from the notification. Never opens the app. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = ReminderIntents.parseTarget(intent.data) ?: return
        val action = intent.action
        val ep = context.entryPoint()
        goAsyncBounded(ep.applicationScope()) {
            when (action) {
                ReminderIntents.ACTION_DONE -> ep.actionHandler().done(target.taskId, target.revision)
                ReminderIntents.ACTION_SNOOZE -> ep.actionHandler().snooze(target.taskId, target.revision)
            }
        }
    }
}

/**
 * Recovery paths: reboot (after unlock — Room is credential-encrypted, so NOT LOCKED_BOOT_COMPLETED), app
 * update, clock/timezone changes, exact-alarm permission changes. All funnel into the same idempotent reconcile.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = intent.action ?: return
        val ep = context.entryPoint()
        goAsyncBounded(ep.applicationScope()) { ep.reconciler().reconcileNow(reason) }
    }
}
