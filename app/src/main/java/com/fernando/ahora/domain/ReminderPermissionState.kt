package com.fernando.ahora.domain

/**
 * The two permissions that shape reminder quality, as the UI needs to show them (PRODUCT_SPEC §8.1).
 * [notificationsGranted] false ⇒ nothing can be shown; [exactAlarmsAllowed] false ⇒ best-effort timing.
 */
data class ReminderPermissionState(
    val notificationsGranted: Boolean,
    /** True while the runtime dialog can still be shown (API 33+, not yet decided or denied once). */
    val canAskNotifications: Boolean,
    val exactAlarmsAllowed: Boolean,
    /** False below API 31, where exact alarms need no special access. */
    val exactAlarmsNeedSpecialAccess: Boolean,
) {
    /** Reminders are fully reliable: both permissions in order. */
    val allGood: Boolean get() = notificationsGranted && exactAlarmsAllowed

    companion object {
        val Unknown = ReminderPermissionState(
            notificationsGranted = true,
            canAskNotifications = false,
            exactAlarmsAllowed = true,
            exactAlarmsNeedSpecialAccess = false,
        )
    }
}

/** Reads the current permission state. Cheap and side-effect free; call it again on resume. */
interface ReminderPermissionSource {
    fun snapshot(): ReminderPermissionState
}
