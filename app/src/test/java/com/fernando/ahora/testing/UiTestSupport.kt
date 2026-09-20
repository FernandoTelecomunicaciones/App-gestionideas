package com.fernando.ahora.testing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fernando.ahora.core.time.ClockTicker
import com.fernando.ahora.data.TaskRepositoryImpl
import com.fernando.ahora.data.local.AhoraDatabase
import com.fernando.ahora.domain.AppPreferences
import com.fernando.ahora.domain.ReminderPermissionSource
import com.fernando.ahora.domain.ReminderPermissionState
import com.fernando.ahora.domain.model.ThemeMode
import com.fernando.ahora.domain.rules.TaskFilter
import com.fernando.ahora.ui.common.TaskActions
import com.fernando.ahora.ui.common.UiMessage
import com.fernando.ahora.ui.common.UiMessenger
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/** In-memory [AppPreferences]: the DataStore-backed one has its own persistence test. */
class FakeAppPreferences : AppPreferences {
    val theme = MutableStateFlow(ThemeMode.SYSTEM)
    val filter = MutableStateFlow(TaskFilter.PENDING)
    val preset = MutableStateFlow(AppPreferences.DEFAULT_FOCUS_PRESET)
    val keepOn = MutableStateFlow(true)

    override val themeMode: Flow<ThemeMode> = theme
    override val tasksFilter: Flow<TaskFilter> = filter
    override val focusPreset: Flow<Int> = preset
    override val keepScreenOnInFocus: Flow<Boolean> = keepOn

    override suspend fun setThemeMode(mode: ThemeMode) { theme.value = mode }
    override suspend fun setTasksFilter(filter: TaskFilter) { this.filter.value = filter }
    override suspend fun setFocusPreset(minutes: Int) { preset.value = minutes }
    override suspend fun setKeepScreenOnInFocus(keepOn: Boolean) { this.keepOn.value = keepOn }
}

/** Emits the fake clock's local date-time on demand ([tick]) and once on collection. */
class FakeClockTicker(private val time: FakeTimeProvider) : ClockTicker {
    private val ticks = MutableSharedFlow<Unit>(replay = 1).also { it.tryEmit(Unit) }
    fun tick() { ticks.tryEmit(Unit) }
    override fun minutes(): Flow<LocalDateTime> = flow { ticks.collect { emit(time.nowLocal()) } }
}

class FakePermissionSource(
    var state: ReminderPermissionState = ReminderPermissionState(
        notificationsGranted = true,
        canAskNotifications = false,
        exactAlarmsAllowed = true,
        exactAlarmsNeedSpecialAccess = true,
    ),
) : ReminderPermissionSource {
    override fun snapshot() = state
}

/**
 * The real stack under a ViewModel: an in-memory Room database, the REAL [TaskRepositoryImpl] and [TaskActions],
 * with only the clock, the reminder engine (recorded) and preferences faked. Must be created inside a Robolectric test.
 */
internal class UiEnv(val time: FakeTimeProvider = FakeTimeProvider()) {
    val db: AhoraDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AhoraDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val sync = RecordingReminderSync()
    val repo = TaskRepositoryImpl(db, db.taskDao(), time, sync)
    val messenger = UiMessenger()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val actions = TaskActions(repo, messenger, appScope)
    val prefs = FakeAppPreferences()
    val ticker = FakeClockTicker(time)
    val permissions = FakePermissionSource()

    fun close() {
        appScope.cancel()
        db.close()
    }
}

/** Polls until [condition] holds (real Room threads are involved, so state settles asynchronously). */
suspend fun eventually(timeoutMs: Long = 5_000, message: String = "condition", condition: suspend () -> Boolean) {
    try {
        withTimeout(timeoutMs) { while (!condition()) delay(10) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out waiting for $message")
    }
}

internal suspend fun UiEnv.nextMessage(): UiMessage = withTimeout(5_000) { messenger.messages.first() }
