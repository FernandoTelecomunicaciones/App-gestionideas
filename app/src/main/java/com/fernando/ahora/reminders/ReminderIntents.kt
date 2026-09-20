package com.fernando.ahora.reminders

import android.net.Uri

/** Deep links (explicit intents only; NO manifest intent-filter for this scheme — D-13). */
sealed interface AppLink {
    data object Home : AppLink
    data class Focus(val taskId: Long) : AppLink
}

object AppLinks {
    const val SCHEME = "ahora"
    private const val HOST_HOME = "home"
    private const val HOST_FOCUS = "focus"

    fun homeUri(): Uri = Uri.Builder().scheme(SCHEME).authority(HOST_HOME).build()
    fun focusUri(taskId: Long): Uri = Uri.Builder().scheme(SCHEME).authority(HOST_FOCUS).appendPath(taskId.toString()).build()

    fun parse(uri: Uri?): AppLink? = uri?.let { parse(it.scheme, it.host, it.pathSegments.orEmpty()) }

    /** Pure and total: anything unrecognised or malformed is null (validated before navigation). */
    fun parse(scheme: String?, host: String?, pathSegments: List<String>): AppLink? {
        if (scheme != SCHEME) return null
        return when (host) {
            HOST_HOME -> if (pathSegments.isEmpty()) AppLink.Home else null
            HOST_FOCUS -> pathSegments.singleOrNull()?.toLongOrNull()?.takeIf { it > 0 }?.let(AppLink::Focus)
            else -> null
        }
    }
}

/** Identity of the notification action PendingIntents: `ahora://reminder/{id}/{revision}/{action}`. */
object ReminderIntents {
    const val ACTION_DONE = "com.fernando.ahora.action.REMINDER_DONE"
    const val ACTION_SNOOZE = "com.fernando.ahora.action.REMINDER_SNOOZE"
    private const val HOST = "reminder"

    fun actionUri(taskId: Long, revision: Int, action: String): Uri =
        Uri.Builder().scheme(AppLinks.SCHEME).authority(HOST).appendPath(taskId.toString())
            .appendPath(revision.toString()).appendPath(action).build()

    data class ActionTarget(val taskId: Long, val revision: Int)

    fun parseTarget(uri: Uri?): ActionTarget? {
        uri ?: return null
        if (uri.scheme != AppLinks.SCHEME || uri.host != HOST) return null
        val seg = uri.pathSegments.orEmpty()
        if (seg.size != 3) return null
        val id = seg[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val rev = seg[1].toIntOrNull() ?: return null
        return ActionTarget(id, rev)
    }
}
