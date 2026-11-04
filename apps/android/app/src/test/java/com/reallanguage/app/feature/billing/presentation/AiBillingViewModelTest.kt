package com.reallanguage.app.feature.billing.presentation

import com.reallanguage.app.feature.billing.domain.AiBillingRepository
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshot
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshotResult
import com.reallanguage.app.feature.billing.domain.AiChargeRecord
import com.reallanguage.app.feature.billing.domain.AiChargeType
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
class AiBillingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsServerBalanceAndRealProviderCallsNewestFirst() = runTest(dispatcher) {
        val viewModel = AiBillingViewModel(FakeRepository(AiBillingSnapshotResult.Ready(snapshot)))
        advanceUntilIdle()

        assertEquals(12_345L, viewModel.uiState.value.balanceAmountFen)
        assertEquals(listOf("charge-retry", "charge-first"), viewModel.uiState.value.charges.map { it.chargeId })
        assertEquals("qwen-plus", viewModel.uiState.value.charges.first().modelSource)
        assertEquals(AiChargeType.AUTOMATIC_RETRY, viewModel.uiState.value.charges.first().callType)
    }

    @Test fun keepsEachTokenConsumingRetryAsIndependentChargeLine() = runTest(dispatcher) {
        val viewModel = AiBillingViewModel(FakeRepository(AiBillingSnapshotResult.Ready(snapshot)))
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.charges.size)
        assertTrue(viewModel.uiState.value.charges.all { it.amountFen > 0 })
    }

    @Test fun failedLoadCanRetryWithoutInventingLocalBalance() = runTest(dispatcher) {
        val repository = FakeRepository(AiBillingSnapshotResult.Failed)
        val viewModel = AiBillingViewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)
        assertEquals(null, viewModel.uiState.value.balanceAmountFen)

        repository.result = AiBillingSnapshotResult.Ready(snapshot)
        viewModel.onAction(AiBillingAction.RetryRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loadFailed)
        assertEquals(12_345L, viewModel.uiState.value.balanceAmountFen)
    }

    @Test fun rechargeRefundAndReturnAreExplicitNavigationEffects() = runTest(dispatcher) {
        val viewModel = AiBillingViewModel(FakeRepository(AiBillingSnapshotResult.Ready(snapshot)))
        advanceUntilIdle()

        val recharge = async { viewModel.effects.first() }
        viewModel.onAction(AiBillingAction.RechargeRequested)
        advanceUntilIdle()
        assertEquals(AiBillingEffect.OpenRecharge, recharge.await())

        val refund = async { viewModel.effects.first() }
        viewModel.onAction(AiBillingAction.RefundRequested)
        advanceUntilIdle()
        assertEquals(AiBillingEffect.OpenRefund, refund.await())

        val account = async { viewModel.effects.first() }
        viewModel.onAction(AiBillingAction.ReturnAccountRequested)
        advanceUntilIdle()
        assertEquals(AiBillingEffect.ReturnAccount, account.await())
    }

    private class FakeRepository(var result: AiBillingSnapshotResult) : AiBillingRepository {
        override suspend fun loadSnapshot(): AiBillingSnapshotResult = result
    }

    companion object {
        private val snapshot = AiBillingSnapshot(
            balanceAmountFen = 12_345,
            charges = listOf(
                AiChargeRecord("charge-first", 120, 1_000, "qwen-plus", AiChargeType.INITIAL_CALL, "英语生成"),
                AiChargeRecord("charge-retry", 80, 2_000, "qwen-plus", AiChargeType.AUTOMATIC_RETRY, "英语生成"),
            ),
        )
    }
}
