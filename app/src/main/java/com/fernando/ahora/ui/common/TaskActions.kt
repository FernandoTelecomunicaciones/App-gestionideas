package com.fernando.ahora.ui.common

import androidx.annotation.StringRes
import com.fernando.ahora.R
import com.fernando.ahora.di.ApplicationScope
import com.fernando.ahora.domain.TaskRepository
import com.fernando.ahora.domain.model.CompleteResult
import com.fernando.ahora.domain.model.PostponeResult
import com.fernando.ahora.domain.model.Task
import com.fernando.ahora.domain.model.TaskFields
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * What "Deshacer" needs to reverse an action. A token, never a captured lambda (ARCHITECTURE §9.2): the repository
 * decides whether the undo still applies (revision guards, GA-04 / R2-5 / GA-05).
 */
sealed interface UndoToken {
    data class Complete(val result: CompleteResult) : UndoToken
    data class Postpone(val result: PostponeResult) : UndoToken
    data class Delete(val task: Task) : UndoToken
}

data class UiMessage(@StringRes val textRes: Int, val undo: UndoToken? = null)

/**
 * One-shot messages for the root Snackbar. Conflated: a lost Snackbar is acceptable, a lost data write is not, so
 * writes never depend on anyone collecting this (ARCHITECTURE §9.2).
 */
@Singleton
class UiMessenger @Inject constructor() {
    private val channel = Channel<UiMessage>(Channel.CONFLATED)
    val messages: Flow<UiMessage> = channel.receiveAsFlow()

    fun post(message: UiMessage) {
        channel.trySend(message)
    }
}

/**
 * The user-facing task actions shared by Hoy, Tareas, Foco and the editor: run the repository operation, then tell the
 * user with the right Snackbar. Runs in the application scope so a write is never cancelled because a screen or its
 * ViewModel went away first (Terminar leaves Foco immediately). No business rule lives here — every decision
 * (idempotence, recurrence, revision guards) is the repository's.
 */
@Singleton
class TaskActions @Inject constructor(
    private val repository: TaskRepository,
    private val messenger: UiMessenger,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /**
     * Quick capture and the editor's "new" path. "Guardado en Bandeja" only when the task really is an inbox item
     * (no date and no priority); otherwise plain "Guardado" (INB-05).
     */
    fun create(fields: TaskFields): Job = scope.launch {
        try {
            if (repository.create(fields) != null) {
                val inbox = fields.dueDate == null && fields.priority == null
                messenger.post(UiMessage(if (inbox) R.string.snack_saved_inbox else R.string.snack_saved))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            messenger.post(UiMessage(R.string.snack_save_failed))
        }
    }

    fun complete(id: Long): Job = guarded {
        val result = repository.complete(id)
        if (result.completed) messenger.post(UiMessage(R.string.snack_completed, UndoToken.Complete(result)))
    }

    /** Completadas → checkbox. Refused (false) for recurring occurrences; the UI does not offer it there. */
    fun reopen(id: Long): Job = guarded { repository.reopen(id) }

    fun postpone(id: Long): Job = guarded {
        val result = repository.postpone(id)
        if (result != null) messenger.post(UiMessage(R.string.snack_postponed, UndoToken.Postpone(result)))
    }

    fun delete(id: Long): Job = guarded {
        val removed = repository.delete(id)
        if (removed != null) messenger.post(UiMessage(R.string.snack_deleted, UndoToken.Delete(removed)))
    }

    fun undo(token: UndoToken): Job = guarded {
        when (token) {
            is UndoToken.Complete -> repository.undoComplete(token.result)
            is UndoToken.Postpone -> repository.undoPostpone(token.result)
            is UndoToken.Delete -> repository.undoDelete(token.task)
        }
    }

    /**
     * These run in the application scope, which has no handler: an exception escaping here (a Room I/O failure) would
     * end the process. Report it like a failed save instead (GC-05).
     */
    private fun guarded(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            messenger.post(UiMessage(R.string.snack_save_failed))
        }
    }

    fun say(@StringRes textRes: Int) = messenger.post(UiMessage(textRes))
}
