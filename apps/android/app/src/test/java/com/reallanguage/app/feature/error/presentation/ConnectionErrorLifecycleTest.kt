package com.reallanguage.app.feature.error.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.error.domain.ConnectionRecoveryRepository
import com.reallanguage.app.feature.error.domain.ConnectionRecoveryResult
import com.reallanguage.app.feature.error.domain.ConnectionResumePoint
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** In-memory account checks only; no transport, credentials, database or AI operations. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionErrorLifecycleTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun repeatedReconnectBeforeDispatchAndDuringTheRequestChecksAccountOnlyOnce() = runTest {
        val response = CompletableDeferred<ConnectionRecoveryResult>()
        var calls = 0
        withModel(check = { calls++; response.await() }) { model ->
            val effects = collectEffects(model)
            repeat(4) { model.onAction(ConnectionErrorAction.ReconnectRequested) }
            runCurrent()
            assertEquals("The account-check gate must be held before dispatch", 1, calls)
            assertTrue(model.uiState.value.reconnecting)
            repeat(4) { model.onAction(ConnectionErrorAction.ReconnectRequested) }
            runCurrent()
            assertEquals("Pending checks must not be duplicated", 1, calls)
            response.complete(ConnectionRecoveryResult.Restored)
            runCurrent()
            assertEquals(listOf(ConnectionErrorEffect.Restore(resumePoint)), effects)
            assertFalse(model.uiState.value.reconnecting)
            assertFalse(model.uiState.value.operationSubmitted)
            assertEquals(resumePoint, model.uiState.value.resumePoint)
        }
    }

    @Test fun thrownCheckFailureStopsTheSpinnerAndAllowsOneExplicitRetry() = runTest {
        val retry = CompletableDeferred<ConnectionRecoveryResult>()
        var calls = 0
        withModel(check = {
            calls++
            if (calls == 1) throw IllegalStateException("isolated account check failed")
            retry.await()
        }) { model ->
            val effects = collectEffects(model)
            model.onAction(ConnectionErrorAction.ReconnectRequested)
            runCurrent()
            assertFalse("An exceptional check must not leave an endless spinner", model.uiState.value.reconnecting)
            assertTrue(model.uiState.value.reconnectFailed)
            assertTrue(model.uiState.value.overlayVisible)
            assertFalse(model.uiState.value.operationSubmitted)
            assertEquals(resumePoint, model.uiState.value.resumePoint)
            assertTrue(effects.isEmpty())
            repeat(3) { model.onAction(ConnectionErrorAction.ReconnectRequested) }
            runCurrent()
            assertEquals(2, calls)
            assertTrue(model.uiState.value.reconnecting)
            assertFalse(model.uiState.value.reconnectFailed)
            retry.complete(ConnectionRecoveryResult.Restored)
            runCurrent()
            assertEquals(listOf(ConnectionErrorEffect.Restore(resumePoint)), effects)
            assertFalse(model.uiState.value.reconnecting)
        }
    }

    @Test fun repositoryCancellationRemainsCancellationWithoutFailureOrStuckLoading() = runTest {
        val enteredJob = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val cancellation = CancellationException("isolated reconnect cancellation")
        withModel(check = {
            enteredJob.complete(checkNotNull(currentCoroutineContext()[Job]))
            release.await()
            throw cancellation
        }) { model ->
            val effects = collectEffects(model)
            model.onAction(ConnectionErrorAction.ReconnectRequested)
            runCurrent()
            assertTrue(enteredJob.isCompleted)
            val requestJob = enteredJob.await()
            var completed = false
            var completionCause: Throwable? = null
            requestJob.invokeOnCompletion { cause -> completed = true; completionCause = cause }
            release.complete(Unit)
            runCurrent()
            assertTrue("The operation must complete with cancellation, not swallow it", completed && requestJob.isCancelled)
            assertTrue(completionCause is CancellationException)
            assertTrue(
                "Coroutine stack recovery may copy cancellation, but must preserve its original message/cause",
                generateSequence(completionCause) { it.cause }.take(16).any {
                    it is CancellationException && it.message == cancellation.message
                },
            )
            assertFalse(model.uiState.value.reconnecting)
            assertFalse(model.uiState.value.reconnectFailed)
            assertTrue(model.uiState.value.overlayVisible)
            assertFalse(model.uiState.value.operationSubmitted)
            assertEquals(resumePoint, model.uiState.value.resumePoint)
            assertTrue(effects.isEmpty())
        }
    }

    @Test fun returnCancelsPendingCheckAndDelegatesToTheExistingFlowOnlyOnce() = runTest {
        var calls = 0
        var cancelled = 0
        withModel(check = {
            calls++
            try { awaitCancellation() } finally { cancelled++ }
        }) { model ->
            val effects = collectEffects(model)
            model.onAction(ConnectionErrorAction.ReconnectRequested)
            runCurrent()
            assertEquals(1, calls)
            model.onAction(ConnectionErrorAction.ReturnRequested)
            runCurrent()
            assertEquals("Return must cancel the actual suspended account check", 1, cancelled)
            assertFalse(model.uiState.value.reconnecting)
            assertFalse(model.uiState.value.reconnectFailed)
            val departed = model.uiState.value
            repeat(3) {
                model.onAction(ConnectionErrorAction.ReturnRequested)
                model.onAction(ConnectionErrorAction.ReconnectRequested)
            }
            runCurrent()
            assertEquals(1, calls)
            assertEquals(departed, model.uiState.value)
            assertEquals(listOf(ConnectionErrorEffect.ReturnUsingCurrentFlowRule(resumePoint.pageId)), effects)
            assertFalse(model.uiState.value.operationSubmitted)
        }
    }

    @Test fun returnBeforeDispatchCancelsTheQueuedCheckWithoutEverVerifyingAccount() = runTest {
        var calls = 0
        withModel(check = { calls++; ConnectionRecoveryResult.Restored }) { model ->
            val effects = collectEffects(model)
            model.onAction(ConnectionErrorAction.ReconnectRequested)
            model.onAction(ConnectionErrorAction.ReturnRequested)
            model.onAction(ConnectionErrorAction.ReturnRequested)
            runCurrent()
            assertEquals("A returned entry must not start its queued account request", 0, calls)
            assertFalse(model.uiState.value.reconnecting)
            assertFalse(model.uiState.value.reconnectFailed)
            assertFalse(model.uiState.value.operationSubmitted)
            assertEquals(listOf(ConnectionErrorEffect.ReturnUsingCurrentFlowRule(resumePoint.pageId)), effects)
        }
    }

    @Test fun nonCancellableLateRestoredCannotRestoreOrChangeTheDepartedEntry() = runTest {
        assertNonCancellableLateResultIgnored(ConnectionRecoveryResult.Restored)
    }

    @Test fun nonCancellableLateUnavailableCannotFailOrChangeTheDepartedEntry() = runTest {
        assertNonCancellableLateResultIgnored(ConnectionRecoveryResult.Unavailable)
    }

    @Test fun successfulHandoffRejectsOldReconnectAndReturnActions() = runTest {
        var calls = 0
        withModel(check = { calls++; ConnectionRecoveryResult.Restored }) { model ->
            val effects = collectEffects(model)
            model.onAction(ConnectionErrorAction.ReconnectRequested)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(listOf(ConnectionErrorEffect.Restore(resumePoint)), effects)
            assertFalse(model.uiState.value.overlayVisible)
            val handedOff = model.uiState.value
            repeat(3) {
                model.onAction(ConnectionErrorAction.ReconnectRequested)
                model.onAction(ConnectionErrorAction.ReturnRequested)
            }
            runCurrent()
            assertEquals("An already handed-off entry cannot recheck the account", 1, calls)
            assertEquals(handedOff, model.uiState.value)
            assertEquals(listOf(ConnectionErrorEffect.Restore(resumePoint)), effects)
            assertFalse(model.uiState.value.operationSubmitted)
        }
    }

    private suspend fun TestScope.assertNonCancellableLateResultIgnored(result: ConnectionRecoveryResult) {
        var continuation: Continuation<ConnectionRecoveryResult>? = null
        var released = false
        var calls = 0
        withModel(check = {
            calls++
            // Unlike CompletableDeferred.await, this callback does not cooperate with Job cancellation.
            suspendCoroutine { continuation = it }
        }) { model ->
            val effects = collectEffects(model)
            try {
                model.onAction(ConnectionErrorAction.ReconnectRequested)
                runCurrent()
                assertEquals(1, calls)
                assertTrue(continuation != null)
                model.onAction(ConnectionErrorAction.ReturnRequested)
                runCurrent()
                val departed = model.uiState.value
                checkNotNull(continuation).resume(result)
                released = true
                runCurrent()
                assertEquals("A real late callback cannot republish either restoration or failure", departed, model.uiState.value)
                model.onAction(ConnectionErrorAction.ReturnRequested)
                runCurrent()
                assertEquals(1, calls)
                assertEquals(listOf(ConnectionErrorEffect.ReturnUsingCurrentFlowRule(resumePoint.pageId)), effects)
                assertFalse(model.uiState.value.operationSubmitted)
            } finally {
                if (!released) continuation?.resume(result)
                runCurrent()
            }
        }
    }

    private fun TestScope.collectEffects(model: ConnectionErrorViewModel): MutableList<ConnectionErrorEffect> {
        val effects = mutableListOf<ConnectionErrorEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
        return effects
    }

    private suspend fun TestScope.withModel(
        check: suspend () -> ConnectionRecoveryResult,
        block: suspend (ConnectionErrorViewModel) -> Unit,
    ) {
        val models = ViewModelStore()
        val model = ConnectionErrorViewModel(resumePoint, object : ConnectionRecoveryRepository {
            override suspend fun verifyNetworkAndAccount() = check()
        })
        models.put("isolated-connection-entry", model)
        try { block(model) } finally { models.clear(); runCurrent() }
    }

    private val resumePoint = ConnectionResumePoint("diary-input", "field-2-cursor-18", "隔离且尚未提交的文字")
}
