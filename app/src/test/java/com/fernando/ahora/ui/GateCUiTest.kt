package com.fernando.ahora.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fernando.ahora.R
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.domain.model.CompleteResult
import com.fernando.ahora.domain.model.PostponeResult
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.testing.UiEnv
import com.fernando.ahora.testing.nextMessage
import com.fernando.ahora.ui.common.TaskActions
import com.fernando.ahora.ui.common.UndoToken
import com.fernando.ahora.ui.components.AhoraSwitch
import com.fernando.ahora.ui.theme.AhoraTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Codex Gate C regression tests: GC-08 (a switch has a name) and GC-05 (nothing escapes the application scope). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class GateCUiTest {
    @get:Rule val compose = createComposeRule()

    // ---- GC-08 -----------------------------------------------------------------------------------------------------

    @Test
    fun aSwitch_isFoundByItsName_andExposesItsState() {
        var on = false
        compose.setContent {
            AhoraTheme(dark = false) {
                AhoraSwitch(
                    checked = on,
                    onCheckedChange = { on = it },
                    label = "Mantener pantalla encendida",
                    stateOn = "Activado",
                    stateOff = "Desactivado",
                )
            }
        }
        val node = compose.onNodeWithContentDescription("Mantener pantalla encendida")
        node.assertHasClickAction().assertIsOff()
        node.performClick()
        assertTrue(on)
    }

    @Test
    fun aSwitchThatIsOn_reportsOn() {
        compose.setContent {
            AhoraTheme(dark = false) {
                AhoraSwitch(
                    checked = true,
                    onCheckedChange = {},
                    label = "Avisarme a esa hora",
                    stateOn = "Activado",
                    stateOff = "Desactivado",
                )
            }
        }
        compose.onNodeWithContentDescription("Avisarme a esa hora").assertIsOn()
    }

    // ---- GC-05 -----------------------------------------------------------------------------------------------------

    /** A store whose writes fail (disk full, I/O error): the application scope has no handler, so nothing may escape. */
    private class ExplodingRepository(inner: TaskRepository) : TaskRepository by inner {
        override suspend fun complete(id: Long, expectedRevision: Int?): CompleteResult = throw IllegalStateException("disk")
        override suspend fun reopen(id: Long): Boolean = throw IllegalStateException("disk")
        override suspend fun postpone(id: Long): PostponeResult? = throw IllegalStateException("disk")
        override suspend fun delete(id: Long): Task? = throw IllegalStateException("disk")
        override suspend fun undoComplete(result: CompleteResult): Boolean = throw IllegalStateException("disk")
    }

    @Test
    fun aFailingWrite_becomesAMessage_notAnUncaughtException() = runBlocking {
        val env = UiEnv()
        try {
            val actions = TaskActions(ExplodingRepository(env.repo), env.messenger, env.appScope)
            val calls: List<() -> Job> = listOf(
                { actions.complete(1) },
                { actions.reopen(1) },
                { actions.postpone(1) },
                { actions.delete(1) },
                { actions.undo(UndoToken.Complete(CompleteResult(1, completed = true, successorId = null, revisionAfter = 1))) },
            )
            for (call in calls) {
                call().join() // would have thrown out of the scope (and ended the process) before the fix
                assertEquals(R.string.snack_save_failed, env.nextMessage().textRes)
            }
        } finally {
            env.close()
        }
    }
}
