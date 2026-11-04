package com.reallanguage.app.feature.billing.presentation

import com.reallanguage.app.feature.billing.domain.BalanceExitDisposition
import com.reallanguage.app.feature.billing.domain.BalanceExitResult
import com.reallanguage.app.feature.billing.domain.BalanceInsufficientContext
import com.reallanguage.app.feature.billing.domain.BalanceInsufficientRepository
import com.reallanguage.app.feature.billing.domain.BalanceOriginState
import com.reallanguage.app.feature.billing.domain.AiBillingRepository
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshot
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshotResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InsufficientBalanceViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun everyOriginHasItsConfirmedExitDisposition() {
        val expected = mapOf(
            BalanceOriginState.NORMAL_LEARNING to BalanceExitDisposition.SAVE_CURRENT_STEP,
            BalanceOriginState.UNIFIED_REVIEW to BalanceExitDisposition.KEEP_COMPLETED_RESTART_CURRENT,
            BalanceOriginState.UNFIXED_DIARY_OR_AI_SCENE to BalanceExitDisposition.DISCARD_DRAFT,
            BalanceOriginState.RECORDING_AWAITING_TRANSCRIPTION to BalanceExitDisposition.KEEP_LOCAL_RECORDING,
            BalanceOriginState.FIXED_UNSTARTED_CHAPTER to BalanceExitDisposition.KEEP_FIXED_CHAPTER,
        )
        expected.forEach { (origin, disposition) ->
            assertEquals(disposition, BalanceInsufficientContext("origin", origin).exitDisposition)
        }
    }

    @Test fun rechargeOpensRechargeForExactPausedOperationWithoutApplyingExitPolicy() = runTest(dispatcher) {
        val repository = FakeRepository()
        val context = BalanceInsufficientContext("ai-operation-7", BalanceOriginState.NORMAL_LEARNING)
        val viewModel = InsufficientBalanceViewModel(context, "leave-command", repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(InsufficientBalanceAction.RechargeRequested)
        advanceUntilIdle()
        assertEquals(InsufficientBalanceEffect.OpenRecharge("ai-operation-7"), effect.await())
        assertTrue(repository.calls.isEmpty())
    }

    @Test fun leaveAppliesSourceSpecificPolicyIdempotentlyBeforeReturning() = runTest(dispatcher) {
        val repository = FakeRepository()
        val context = BalanceInsufficientContext("review-2", BalanceOriginState.UNIFIED_REVIEW)
        val viewModel = InsufficientBalanceViewModel(context, "leave-command", repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(InsufficientBalanceAction.LeaveRequested)
        advanceUntilIdle()
        assertEquals(listOf(Triple("review-2", BalanceExitDisposition.KEEP_COMPLETED_RESTART_CURRENT, "leave-command")), repository.calls)
        assertEquals(InsufficientBalanceEffect.ReturnAfterExit("review-2"), effect.await())
    }

    @Test fun alreadyAppliedExitIsSafeAndReturns() = runTest(dispatcher) {
        val repository = FakeRepository(BalanceExitResult.AlreadyApplied)
        val viewModel = InsufficientBalanceViewModel(
            BalanceInsufficientContext("recording-1", BalanceOriginState.RECORDING_AWAITING_TRANSCRIPTION),
            "leave-command",
            repository,
        )
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(InsufficientBalanceAction.LeaveRequested)
        advanceUntilIdle()
        assertEquals(InsufficientBalanceEffect.ReturnAfterExit("recording-1"), effect.await())
    }

    @Test fun failedExitKeepsPageAndAllowsRetry() = runTest(dispatcher) {
        val repository = FakeRepository(BalanceExitResult.Failed)
        val viewModel = InsufficientBalanceViewModel(
            BalanceInsufficientContext("draft-1", BalanceOriginState.UNFIXED_DIARY_OR_AI_SCENE),
            "leave-command",
            repository,
        )
        viewModel.onAction(InsufficientBalanceAction.LeaveRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.exitFailed)
        assertFalse(viewModel.uiState.value.applyingExitPolicy)
        repository.result = BalanceExitResult.Applied
        viewModel.onAction(InsufficientBalanceAction.LeaveRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.exitFailed)
        assertEquals(2, repository.calls.size)
    }

    @Test fun loadsRealBalanceForThePausedOperationPage() = runTest(dispatcher) {
        val viewModel = InsufficientBalanceViewModel(
            BalanceInsufficientContext("scene-1", BalanceOriginState.UNFIXED_DIARY_OR_AI_SCENE),
            "leave-command",
            FakeRepository(),
            billingRepository = object : AiBillingRepository {
                override suspend fun loadSnapshot() =
                    AiBillingSnapshotResult.Ready(AiBillingSnapshot(18, emptyList()))
            },
        )

        advanceUntilIdle()

        assertEquals(18L, viewModel.uiState.value.balanceAmountFen)
    }

    private class FakeRepository(var result: BalanceExitResult = BalanceExitResult.Applied) : BalanceInsufficientRepository {
        val calls = mutableListOf<Triple<String, BalanceExitDisposition, String>>()
        override suspend fun applyExitPolicy(originId: String, disposition: BalanceExitDisposition, commandId: String): BalanceExitResult {
            calls += Triple(originId, disposition, commandId)
            return result
        }
    }
}
