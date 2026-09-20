package com.fernando.ahora.ui.settings

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.R
import com.fernando.ahora.data.prefs.DataStoreAppPreferences
import com.fernando.ahora.domain.AppPreferences
import com.fernando.ahora.domain.ReminderPermissionState
import com.fernando.ahora.domain.backup.BackupCodec
import com.fernando.ahora.domain.model.Priority
import com.fernando.ahora.domain.model.TaskFields
import com.fernando.ahora.domain.model.ThemeMode
import com.fernando.ahora.domain.rules.TaskFilter
import com.fernando.ahora.testing.UiEnv
import com.fernando.ahora.testing.eventually
import com.fernando.ahora.testing.nextMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** DataStore persistence, permission representation and the two-step import (PRODUCT_SPEC 5.7). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PreferencesAndSettingsTest {
    private lateinit var env: UiEnv
    private lateinit var file: File
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        env = UiEnv()
        file = File.createTempFile("ahora_prefs", ".preferences_pb").also { it.delete() }
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        env.close()
        file.delete()
        Dispatchers.resetMain()
    }

    private fun realPrefs(): DataStoreAppPreferences {
        val store = PreferenceDataStoreFactory.create(scope = storeScope) { file }
        return DataStoreAppPreferences(store)
    }

    // ---- theme persistence -------------------------------------------------------------------------------

    @Test
    fun theme_defaultsToSystem() = runBlocking {
        assertEquals(ThemeMode.SYSTEM, realPrefs().themeMode.first())
    }

    @Test
    fun theme_persistsAcrossProcesses() = runBlocking {
        val firstProcess = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = DataStoreAppPreferences(PreferenceDataStoreFactory.create(scope = firstProcess) { file })
        first.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, first.themeMode.first())
        firstProcess.cancel() // the process dies; only the file remains

        assertEquals(ThemeMode.DARK, realPrefs().themeMode.first())
    }

    @Test
    fun theSelectedTasksFilter_andFocusPreset_persist() = runBlocking {
        val prefs = realPrefs()
        prefs.setTasksFilter(TaskFilter.P1)
        prefs.setFocusPreset(45)
        prefs.setKeepScreenOnInFocus(false)
        assertEquals(TaskFilter.P1, prefs.tasksFilter.first())
        assertEquals(45, prefs.focusPreset.first())
        assertFalse(prefs.keepScreenOnInFocus.first())
    }

    @Test
    fun defaults_areTheDocumentedOnes() = runBlocking {
        val prefs = realPrefs()
        assertEquals(TaskFilter.PENDING, prefs.tasksFilter.first())
        assertEquals(25, prefs.focusPreset.first())
        assertTrue(prefs.keepScreenOnInFocus.first())
    }

    @Test
    fun aPresetOutsideTheFixedThree_isNeverStored() = runBlocking {
        val prefs = realPrefs()
        prefs.setFocusPreset(7)
        assertEquals(AppPreferences.DEFAULT_FOCUS_PRESET, prefs.focusPreset.first())
    }

    @Test
    fun unknownStoredValues_fallBackToDefaults_insteadOfCrashing() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = storeScope) { file }
        store.edit {
            it[stringPreferencesKey("theme")] = "neon"
            it[stringPreferencesKey("tasksFilter")] = "EVERYTHING"
        }
        val prefs = DataStoreAppPreferences(store)
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode.first())
        assertEquals(TaskFilter.PENDING, prefs.tasksFilter.first())
    }

    // ---- settings view model ------------------------------------------------------------------------------

    private fun vm() = SettingsViewModel(
        SavedStateHandle(), env.prefs, env.repo, env.permissions, env.sync, env.actions, env.time, context, env.appScope,
    )

    private suspend fun SettingsViewModel.current(predicate: (SettingsUiState) -> Boolean = { true }) =
        withTimeout(5_000) { state.first(predicate) }

    @Test
    fun settingTheTheme_reachesPreferences_andTheState() = runBlocking {
        val vm = vm()
        vm.setTheme(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, vm.current { it.theme == ThemeMode.LIGHT }.theme)
        assertEquals(ThemeMode.LIGHT, env.prefs.theme.value)
    }

    @Test
    fun permissionState_isRepresentedFaithfully_andRefreshesOnResume() = runBlocking {
        env.permissions.state = ReminderPermissionState(
            notificationsGranted = false, canAskNotifications = true,
            exactAlarmsAllowed = false, exactAlarmsNeedSpecialAccess = true,
        )
        val vm = vm()
        val denied = vm.current().permissions
        assertFalse(denied.notificationsGranted)
        assertTrue(denied.canAskNotifications)
        assertFalse(denied.exactAlarmsAllowed)
        assertFalse(denied.allGood)

        // The user grants both in system settings and returns.
        env.permissions.state = env.permissions.state.copy(notificationsGranted = true, exactAlarmsAllowed = true)
        vm.refreshPermissions()
        assertTrue(vm.current { it.permissions.allGood }.permissions.allGood)
    }

    @Test
    fun exactAlarms_onOldAndroid_areNotNeeded() = runBlocking {
        env.permissions.state = ReminderPermissionState(true, false, exactAlarmsAllowed = true, exactAlarmsNeedSpecialAccess = false)
        val p = vm().current().permissions
        assertFalse(p.exactAlarmsNeedSpecialAccess)
        assertTrue(p.allGood)
    }

    // ---- import: validate, confirm, replace, re-plan ----------------------------------------------------------

    private fun file(text: String): Uri {
        val uri = Uri.parse("content://ahora.test/${System.nanoTime()}.json")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
        return uri
    }

    @Test
    fun import_showsTheCountsFirst_andChangesNothingUntilConfirmed() = runBlocking {
        env.repo.create(TaskFields("actual 1"))
        env.repo.create(TaskFields("actual 2"))
        val backup = BackupCodec.encode(
            listOf(com.fernando.ahora.testing.aTask(10, "de la copia"), com.fernando.ahora.testing.aTask(11, "otra")),
            env.time.now(),
        )
        val vm = vm()

        vm.readImport(file(backup))

        val pending = vm.current { it.pendingImport != null }.pendingImport!!
        assertEquals(2, pending.currentCount)
        assertEquals(2, pending.incomingCount)
        assertEquals(listOf("actual 1", "actual 2"), env.repo.exportAll().map { it.title })
        assertTrue("nothing has been re-planned yet", env.sync.resets.isEmpty())
    }

    @Test
    fun confirming_replacesEverything_andResetsTheReminderEngine() = runBlocking {
        env.repo.create(TaskFields("viejo"))
        val backup = BackupCodec.encode(
            listOf(com.fernando.ahora.testing.aTask(10, "nuevo", LocalDate.of(2026, 9, 25), priority = Priority.P2)),
            env.time.now(),
        )
        val vm = vm()
        vm.readImport(file(backup))
        vm.current { it.pendingImport != null }

        vm.confirmImport()

        assertEquals(R.string.snack_imported, env.nextMessage().textRes)
        assertEquals(listOf("nuevo"), env.repo.exportAll().map { it.title })
        assertEquals(listOf("import"), env.sync.resets)
        assertNull(vm.current().pendingImport)
    }

    @Test
    fun cancelling_leavesTheDataUntouched() = runBlocking {
        env.repo.create(TaskFields("intacto"))
        val vm = vm()
        vm.readImport(file(BackupCodec.encode(listOf(com.fernando.ahora.testing.aTask(10, "x")), env.time.now())))
        vm.current { it.pendingImport != null }

        vm.cancelImport()

        assertNull(vm.current().pendingImport)
        assertEquals(listOf("intacto"), env.repo.exportAll().map { it.title })
        assertTrue(env.sync.resets.isEmpty())
    }

    @Test
    fun aNewerVersion_aForeignFile_andBadData_areRejectedWithAMessage_andNothingChanges() = runBlocking {
        env.repo.create(TaskFields("intacto"))
        val vm = vm()

        vm.readImport(file("""{"schemaVersion": 99, "exportedAt": "x", "tasks": []}"""))
        assertEquals(R.string.snack_import_too_new, env.nextMessage().textRes)

        vm.readImport(file("esto no es una copia"))
        assertEquals(R.string.snack_import_not_backup, env.nextMessage().textRes)

        val bad = BackupCodec.encode(listOf(com.fernando.ahora.testing.aTask(1, "a"), com.fernando.ahora.testing.aTask(1, "b")), env.time.now())
        vm.readImport(file(bad))
        assertEquals(R.string.snack_import_invalid, env.nextMessage().textRes)

        assertNull(vm.current().pendingImport)
        assertEquals(listOf("intacto"), env.repo.exportAll().map { it.title })
    }

    @Test
    fun export_writesAValidBackupOfTheCurrentTasks() = runBlocking {
        env.repo.create(TaskFields("para exportar", priority = Priority.P3))
        val out = ByteArrayOutputStream()
        val uri = Uri.parse("content://ahora.test/out.json")
        shadowOf(context.contentResolver).registerOutputStream(uri, out)

        vm().export(uri)

        assertEquals(R.string.snack_exported, env.nextMessage().textRes)
        eventually { out.size() > 0 }
        val decoded = BackupCodec.decode(out.toString(Charsets.UTF_8)) as BackupCodec.DecodeResult.Ok
        assertEquals(listOf("para exportar"), decoded.tasks.map { it.title })
        assertNotNull(vm().suggestedExportName().takeIf { it.startsWith("ahora-copia-") && it.endsWith(".json") })
    }
}
