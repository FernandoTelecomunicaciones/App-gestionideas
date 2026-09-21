package com.fernando.ahora.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fernando.ahora.R
import com.fernando.ahora.core.time.TimeProvider
import com.fernando.ahora.di.ApplicationScope
import com.fernando.ahora.domain.AppPreferences
import com.fernando.ahora.domain.ReminderPermissionSource
import com.fernando.ahora.domain.ReminderPermissionState
import com.fernando.ahora.domain.ReminderSync
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.domain.backup.BackupCodec
import com.fernando.ahora.domain.model.ReplaceResult
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.model.ThemeMode
import com.fernando.ahora.ui.common.TaskActions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A validated file waiting for the user's explicit confirmation before it replaces anything. */
data class PendingImport(val tasks: List<Task>, val currentCount: Int) {
    val incomingCount: Int get() = tasks.size
}

data class SettingsUiState(
    val theme: ThemeMode,
    val keepScreenOnInFocus: Boolean,
    val permissions: ReminderPermissionState,
    val notificationsAsked: Boolean,
    val pendingImport: PendingImport?,
)

/**
 * Ajustes (PRODUCT_SPEC §5.7) — only the approved settings: permission state, theme, keep-screen-on in Foco, and
 * JSON export/import. Import is strictly two-step: read + validate ([BackupCodec], then the repository re-validates),
 * show the counts, and replace only after the user confirms. The replace is atomic in the repository and re-plans
 * every reminder (`resetAll`); nothing here schedules anything.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val preferences: AppPreferences,
    private val repository: TaskRepository,
    private val permissionSource: ReminderPermissionSource,
    private val reminderSync: ReminderSync,
    private val actions: TaskActions,
    private val time: TimeProvider,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val permissions = MutableStateFlow(permissionSource.snapshot())
    private val asked = MutableStateFlow(savedState[KEY_ASKED] ?: false)
    private val pendingImport = MutableStateFlow<PendingImport?>(null)

    val state: StateFlow<SettingsUiState> = combine(
        preferences.themeMode,
        preferences.keepScreenOnInFocus,
        permissions,
        combine(asked, pendingImport) { a, p -> a to p },
    ) { theme, keepOn, perms, (askedBefore, pending) ->
        SettingsUiState(theme, keepOn, perms, askedBefore, pending)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(ThemeMode.SYSTEM, true, permissionSource.snapshot(), false, null),
    )

    fun refreshPermissions() {
        permissions.value = permissionSource.snapshot()
    }

    fun onNotificationPermissionAnswered() {
        savedState[KEY_ASKED] = true
        asked.value = true
        refreshPermissions()
        appScope.launch { reminderSync.onTasksChanged(emptySet(), "notification-permission") }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setKeepScreenOn(keepOn: Boolean) {
        viewModelScope.launch { preferences.setKeepScreenOnInFocus(keepOn) }
    }

    // --- Datos ------------------------------------------------------------------------------------------------

    fun suggestedExportName(): String = "ahora-copia-${DateTimeFormatter.ISO_LOCAL_DATE.format(time.today())}.json"

    fun export(uri: Uri) {
        appScope.launch {
            try {
                val text = BackupCodec.encode(repository.exportAll(), time.now())
                // Never write a copy AHORA itself would refuse to import: fail visibly instead (GC-09).
                check(text.length <= BackupCodec.MAX_CHARS) { "backup larger than the import limit" }
                withContext(Dispatchers.IO) {
                    // "wt": truncate, so overwriting a longer file never leaves a corrupt tail.
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("no output stream")
                }
                actions.say(R.string.snack_exported)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                actions.say(R.string.snack_export_failed)
            }
        }
    }

    /** Step 1: read and fully validate. Nothing is written; a bad file only produces a message. */
    fun readImport(uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readAtMost(BackupCodec.MAX_CHARS + 1) }
                        ?: error("no input stream")
                }
                if (bytes.size > BackupCodec.MAX_CHARS) return@launch actions.say(R.string.snack_import_invalid)
                when (val result = BackupCodec.decode(bytes.toString(Charsets.UTF_8))) {
                    is BackupCodec.DecodeResult.Ok ->
                        pendingImport.value = PendingImport(result.tasks, repository.exportAll().size)
                    is BackupCodec.DecodeResult.Rejected -> actions.say(
                        when (result.reason) {
                            BackupCodec.Reason.NOT_A_BACKUP -> R.string.snack_import_not_backup
                            BackupCodec.Reason.UNSUPPORTED_VERSION -> R.string.snack_import_too_new
                            BackupCodec.Reason.TOO_LARGE, BackupCodec.Reason.INVALID_DATA -> R.string.snack_import_invalid
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                actions.say(R.string.snack_import_failed)
            }
        }
    }

    fun cancelImport() {
        pendingImport.value = null
    }

    /** Step 2, after the confirmation dialog: atomic replace, then the repository resets every reminder. */
    fun confirmImport() {
        val pending = pendingImport.value ?: return
        pendingImport.update { null }
        appScope.launch {
            try {
                when (repository.replaceAll(pending.tasks)) {
                    is ReplaceResult.Replaced -> actions.say(R.string.snack_imported)
                    is ReplaceResult.Invalid -> actions.say(R.string.snack_import_invalid)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                actions.say(R.string.snack_import_failed)
            }
        }
    }

    private companion object {
        const val KEY_ASKED = "notificationsAsked"
    }
}

/** `InputStream.readNBytes` needs API 33; this bounded read works on API 26 and never buffers more than [limit] bytes. */
private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val n = read(chunk, 0, minOf(chunk.size, limit - out.size()))
        if (n < 0) break
        out.write(chunk, 0, n)
    }
    return out.toByteArray()
}
