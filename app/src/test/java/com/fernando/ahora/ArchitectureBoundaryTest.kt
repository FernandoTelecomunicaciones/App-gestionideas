package com.fernando.ahora

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces ARCHITECTURE §3/§4 (`ui -> domain <- data`) and GA-09: nothing in `ui` may reach a DAO, the Room
 * layer, the trigger-free ReminderStore, or the reconciler internals — the repository interface is the only
 * write path. A cheap source scan instead of a heavyweight architecture-test dependency.
 */
class ArchitectureBoundaryTest {
    private val root = File("src/main/java/com/fernando/ahora")

    private fun sources(dir: String) = File(root, dir).walkTopDown().filter { it.extension == "kt" }.toList()

    private fun importsOf(file: File) = file.readLines().filter { it.startsWith("import ") }

    @Test
    fun uiNeverTouchesDataLayerOrReminderInternals() {
        val forbidden = listOf(
            "com.fernando.ahora.data.",            // Room, DAO, repository impl
            "com.fernando.ahora.domain.ReminderStore",
            "com.fernando.ahora.reminders.ReminderReconciler",
            "com.fernando.ahora.reminders.ReminderActionHandler",
            "com.fernando.ahora.reminders.AlarmManagerScheduler",
            "androidx.room.",
        )
        val violations = sources("ui").flatMap { f ->
            importsOf(f).filter { line -> forbidden.any { line.contains(it) } }.map { "${f.name}: $it" }
        }
        assertTrue("ui must go through domain.TaskRepository only:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun domainHasNoAndroidOrDataDependencies() {
        val violations = sources("domain").flatMap { f ->
            importsOf(f).filter { it.contains("import android.") || it.contains("import androidx.") || it.contains("ahora.data.") || it.contains("ahora.ui.") }
                .map { "${f.name}: $it" }
        }
        assertTrue("domain must stay pure Kotlin:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun theManifestDeclaresNoInternetPermission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        // the comment block mentions INTERNET as "not declared"; only a real <uses-permission> counts
        val declared = Regex("<uses-permission[^>]*android.permission.INTERNET").containsMatchIn(manifest)
        assertTrue("V1 is offline: INTERNET must never be declared", !declared)
    }
}
