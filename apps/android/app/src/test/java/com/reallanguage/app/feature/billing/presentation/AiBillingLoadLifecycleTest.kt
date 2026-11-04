package com.reallanguage.app.feature.billing.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.billing.domain.AiBillingRepository
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshot
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshotResult
import com.reallanguage.app.feature.billing.domain.AiChargeRecord
import com.reallanguage.app.feature.billing.domain.AiChargeType
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Controlled in-memory reads only. No balance mutation, recharge, refund or network call. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiBillingLoadLifecycleTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val models = ViewModelStore()
    private var modelIndex = 0

    @After fun clearModels() = models.clear()

    @Test fun repeatedRetryBeforeAndDuringInitialLoadStartsOnlyOneRequest() = runTest {
        val response = CompletableDeferred<AiBillingSnapshotResult>()
        var calls = 0
        val model = model { calls++; response.await() }
        try {
            repeat(3) { model.onAction(AiBillingAction.RetryRequested) }
            runCurrent()
            assertEquals("The loading gate must be held before its coroutine starts", 1, calls)
            repeat(3) { model.onAction(AiBillingAction.RetryRequested) }
            runCurrent()
            assertEquals(1, calls)
            assertTrue(model.uiState.value.loading)
        } finally {
            response.complete(AiBillingSnapshotResult.Ready(snapshot))
            advanceUntilIdle()
        }
        assertReady(model)
    }

    @Test fun failedLoadAllowsExactlyOneControlledRetryAndReleasesTheGate() = runTest {
        val retry = CompletableDeferred<AiBillingSnapshotResult>()
        var calls = 0
        val model = model {
            calls++
            if (calls == 1) AiBillingSnapshotResult.Failed else retry.await()
        }
        runCurrent()
        assertTrue(model.uiState.value.loadFailed)
        assertFalse(model.uiState.value.loading)
        assertEquals(null, model.uiState.value.balanceAmountFen)
        try {
            repeat(3) { model.onAction(AiBillingAction.RetryRequested) }
            runCurrent()
            assertEquals("One initial request and one retry, not competing retries", 2, calls)
            assertTrue(model.uiState.value.loading)
            assertFalse(model.uiState.value.loadFailed)
        } finally {
            retry.complete(AiBillingSnapshotResult.Ready(snapshot))
            advanceUntilIdle()
        }
        assertReady(model)
    }

    @Test fun returnCancelsPendingLoadWithoutPublishingFailureOrStartingAnotherRead() = runTest {
        var calls = 0
        var cancelled = 0
        val model = model {
            calls++
            try { awaitCancellation() } finally { cancelled++ }
        }
        val effects = mutableListOf<AiBillingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
        try {
            runCurrent()
            assertEquals(1, calls)
            model.onAction(AiBillingAction.ReturnAccountRequested)
            runCurrent()
            assertEquals("Returning must cancel the suspended read", 1, cancelled)
            assertFalse(model.uiState.value.loadFailed)
            repeat(2) {
                model.onAction(AiBillingAction.RetryRequested)
                model.onAction(AiBillingAction.ReturnAccountRequested)
            }
            runCurrent()
            assertEquals(1, calls)
            assertEquals(listOf(AiBillingEffect.ReturnAccount), effects)
        } finally {
            models.clear()
            runCurrent()
        }
    }

    @Test fun lateReadyOrFailedAfterReturnCannotMutateTheDepartedEntry() = runTest {
        for (result in listOf(AiBillingSnapshotResult.Ready(snapshot), AiBillingSnapshotResult.Failed)) {
            var calls = 0
            var continuation: Continuation<AiBillingSnapshotResult>? = null
            var released = false
            val model = model {
                calls++
                // A deliberately non-cooperating callback makes the post-cancellation guard observable.
                suspendCoroutine { continuation = it }
            }
            val effects = mutableListOf<AiBillingEffect>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
            try {
                runCurrent()
                assertEquals(1, calls)
                model.onAction(AiBillingAction.ReturnAccountRequested)
                runCurrent()
                val departed = model.uiState.value
                checkNotNull(continuation).resume(result)
                released = true
                runCurrent()
                assertEquals("A late callback cannot publish either balance or failure after return", departed, model.uiState.value)
                model.onAction(AiBillingAction.ReturnAccountRequested)
                runCurrent()
                assertEquals(1, calls)
                assertEquals(listOf(AiBillingEffect.ReturnAccount), effects)
            } finally {
                if (!released) continuation?.resume(result)
                models.clear()
                runCurrent()
                collector.cancel()
            }
        }
    }

    private fun model(load: suspend () -> AiBillingSnapshotResult) = AiBillingViewModel(object : AiBillingRepository {
        override suspend fun loadSnapshot() = load()
    }).also { models.put("billing-${modelIndex++}", it) }

    private fun assertReady(model: AiBillingViewModel) {
        assertFalse(model.uiState.value.loading)
        assertFalse(model.uiState.value.loadFailed)
        assertEquals(snapshot.balanceAmountFen, model.uiState.value.balanceAmountFen)
        assertEquals(snapshot.charges, model.uiState.value.charges)
    }

    private val snapshot = AiBillingSnapshot(800, listOf(
        AiChargeRecord("isolated-charge", 25, 10, "isolated-provider/model", AiChargeType.INITIAL_CALL, "隔离账单记录"),
    ))
}
