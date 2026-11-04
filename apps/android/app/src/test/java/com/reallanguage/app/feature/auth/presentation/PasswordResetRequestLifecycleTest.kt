package com.reallanguage.app.feature.auth.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.auth.domain.PasswordResetRepository
import com.reallanguage.app.feature.auth.domain.PasswordResetRequestResult
import com.reallanguage.app.feature.auth.domain.PasswordResetResult
import com.reallanguage.app.feature.auth.domain.PasswordResetVerificationResult
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Existing actions only, with isolated in-memory credentials and controlled callback lifetimes. */
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordResetRequestLifecycleTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun returnCancelsEveryPendingOperationAndCannotRestartTheDepartedEntry() = runTest {
        for (operation in Operation.entries) {
            var cancellations = 0
            val repository = Repository { current ->
                if (current == operation) try { awaitCancellation() } finally { cancellations++ }
            }
            withModel(repository) { model, _ ->
                val effects = collectEffects(model)
                start(model, operation)
                assertEquals(1, repository.calls.count { it == operation })
                model.onAction(PasswordResetAction.ReturnToLoginRequested)
                runCurrent()
                assertEquals("Returning must cancel the pending $operation request", 1, cancellations)
                val departed = model.uiState.value
                trigger(model, operation)
                model.onAction(PasswordResetAction.CompleteRequested)
                runCurrent()
                assertEquals(departed, model.uiState.value)
                assertEquals(1, repository.calls.count { it == operation })
                assertEquals(listOf(PasswordResetEffect.OpenLogin), effects)
            }
        }
    }

    @Test fun returningBeforeDispatchDoesNotSendTheQueuedResetEmail() = runTest {
        val repository = Repository { awaitCancellation() }
        withModel(repository) { model, _ ->
            val effects = collectEffects(model)
            model.onAction(PasswordResetAction.EmailChanged(EMAIL))
            model.onAction(PasswordResetAction.SendRequested)
            model.onAction(PasswordResetAction.ReturnToLoginRequested)
            runCurrent()
            assertTrue("A departed entry cannot start its queued request", repository.calls.isEmpty())
            assertEquals(listOf(PasswordResetEffect.OpenLogin), effects)
        }
    }

    @Test fun disposalCancelsACooperativeResetWithoutPublishingSuccessOrFailure() = runTest {
        var cancellations = 0
        val repository = Repository { operation ->
            if (operation == Operation.RESET) try { awaitCancellation() } finally { cancellations++ }
        }
        withModel(repository) { model, store ->
            val effects = collectEffects(model)
            start(model, Operation.RESET)
            assertTrue(model.uiState.value.operationInProgress)
            store.clear()
            runCurrent()
            assertEquals(1, cancellations)
            assertTrue(effects.isEmpty())
            assertFalse(model.uiState.value.step == PasswordResetStep.SUCCESS)
            assertNull(model.uiState.value.error)
        }
    }

    @Test fun returnRejectsNonCooperatingLateResultsFromEveryResetStage() = assertLateResultsAreIgnored(dispose = false)

    @Test fun disposalRejectsNonCooperatingLateResultsFromEveryResetStage() = assertLateResultsAreIgnored(dispose = true)

    @Test fun oneLiveEntryKeepsExistingCommandsAndNeedsExplicitCompletionToNavigate() = runTest {
        val repository = Repository()
        withModel(repository) { model, _ ->
            val effects = collectEffects(model)
            model.onAction(PasswordResetAction.EmailChanged(EMAIL))
            repeat(3) { model.onAction(PasswordResetAction.SendRequested) }
            runCurrent()
            assertEquals(PasswordResetStep.WAITING_VERIFICATION, model.uiState.value.step)
            model.onAction(link)
            runCurrent()
            assertEquals(PasswordResetStep.NEW_PASSWORD, model.uiState.value.step)
            model.onAction(PasswordResetAction.PasswordChanged(PASSWORD))
            repeat(3) { model.onAction(PasswordResetAction.ResetRequested) }
            runCurrent()
            assertEquals(Operation.entries.toList(), repository.calls)
            assertEquals(PasswordResetStep.SUCCESS, model.uiState.value.step)
            assertEquals("", model.uiState.value.newPassword)
            assertNull(model.uiState.value.resetToken)
            assertFalse(model.uiState.value.operationInProgress)
            assertTrue(effects.isEmpty())
            model.onAction(PasswordResetAction.CompleteRequested)
            runCurrent()
            assertEquals(listOf(PasswordResetEffect.OpenHome), effects)
        }
    }

    private fun assertLateResultsAreIgnored(dispose: Boolean) = runTest {
        for (operation in Operation.entries) {
            var callback: Continuation<Unit>? = null
            val repository = Repository { current ->
                if (current == operation) suspendCoroutine<Unit> { callback = it }
            }
            try {
                withModel(repository) { model, store ->
                    val effects = collectEffects(model)
                    start(model, operation)
                    assertTrue("The $operation callback must actually be pending", callback != null)
                    if (dispose) store.clear() else model.onAction(PasswordResetAction.ReturnToLoginRequested)
                    runCurrent()
                    val departed = model.uiState.value
                    checkNotNull(callback).also { callback = null }.resume(Unit)
                    runCurrent()
                    if (!dispose) model.onAction(PasswordResetAction.CompleteRequested)
                    runCurrent()
                    assertEquals("Late $operation result cannot change a departed entry", departed, model.uiState.value)
                    assertEquals(if (dispose) emptyList() else listOf(PasswordResetEffect.OpenLogin), effects)
                    assertEquals(1, repository.calls.count { it == operation })
                }
            } finally {
                callback?.also { callback = null }?.resume(Unit)
                runCurrent()
            }
        }
    }

    private fun TestScope.start(model: PasswordResetViewModel, operation: Operation) {
        model.onAction(PasswordResetAction.EmailChanged(EMAIL))
        if (operation == Operation.RESET) {
            model.onAction(link)
            runCurrent()
            model.onAction(PasswordResetAction.PasswordChanged(PASSWORD))
        }
        trigger(model, operation)
        runCurrent()
    }

    private fun trigger(model: PasswordResetViewModel, operation: Operation) = model.onAction(when (operation) {
        Operation.REQUEST -> PasswordResetAction.SendRequested
        Operation.VERIFY -> link
        Operation.RESET -> PasswordResetAction.ResetRequested
    })

    private suspend fun TestScope.withModel(repository: Repository,
        check: suspend (PasswordResetViewModel, ViewModelStore) -> Unit) {
        val store = ViewModelStore()
        val model = PasswordResetViewModel(repository, { it.endsWith("@example.invalid") }, REQUEST_COMMAND, RESET_COMMAND)
        store.put("isolated-reset-entry", model)
        try { check(model, store) } finally { store.clear(); runCurrent() }
    }

    private fun TestScope.collectEffects(model: PasswordResetViewModel) = mutableListOf<PasswordResetEffect>().also { effects ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
    }

    private enum class Operation { REQUEST, VERIFY, RESET }
    private class Repository(private val before: suspend (Operation) -> Unit = {}) : PasswordResetRepository {
        val calls = mutableListOf<Operation>()
        override suspend fun request(email: String, commandId: String): PasswordResetRequestResult {
            assertEquals(EMAIL, email)
            assertEquals(REQUEST_COMMAND, commandId)
            calls += Operation.REQUEST
            before(Operation.REQUEST)
            return PasswordResetRequestResult.Sent(CHALLENGE)
        }
        override suspend fun verify(challengeId: String, token: String): PasswordResetVerificationResult {
            assertEquals(CHALLENGE, challengeId)
            assertEquals(BEARER, token)
            calls += Operation.VERIFY
            before(Operation.VERIFY)
            return PasswordResetVerificationResult.Verified(RESET_TOKEN)
        }
        override suspend fun reset(resetToken: String, newPassword: String, commandId: String): PasswordResetResult {
            assertEquals(RESET_TOKEN, resetToken)
            assertEquals(PASSWORD, newPassword)
            assertEquals(RESET_COMMAND, commandId)
            calls += Operation.RESET
            before(Operation.RESET)
            return PasswordResetResult.Completed(true, true)
        }
    }

    private companion object {
        const val EMAIL = "reset-lifecycle@example.invalid"
        const val CHALLENGE = "isolated-reset-challenge"
        const val BEARER = "ISOLATED_LINK_BEARER"
        const val RESET_TOKEN = "ISOLATED_RESET_TOKEN"
        const val PASSWORD = "ISOLATED_TEST_PASSWORD"
        const val REQUEST_COMMAND = "isolated-request-command"
        const val RESET_COMMAND = "isolated-reset-command"
        val link = PasswordResetAction.VerificationLinkOpened(BEARER, CHALLENGE)
    }
}
