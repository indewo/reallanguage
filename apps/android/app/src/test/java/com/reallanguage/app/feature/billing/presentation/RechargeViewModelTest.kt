package com.reallanguage.app.feature.billing.presentation

import com.reallanguage.app.feature.billing.domain.PaymentMethod
import com.reallanguage.app.feature.billing.domain.RechargeOption
import com.reallanguage.app.feature.billing.domain.RechargeOrderStatus
import com.reallanguage.app.feature.billing.domain.RechargePageRepository
import com.reallanguage.app.feature.billing.domain.RechargePageResult
import com.reallanguage.app.feature.billing.domain.RechargePageSnapshot
import com.reallanguage.app.feature.billing.domain.RechargeSubmitResult
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RechargeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsServerConfiguredRecommendationsWithoutMakingThemProductConstants() = runTest(dispatcher) {
        val viewModel = RechargeViewModel("origin-ai-task", "command-1", FakeRepository())
        advanceUntilIdle()

        assertEquals(listOf(5_000L, 20_000L), viewModel.uiState.value.recommendedOptions.map { it.amountFen })
        assertNull(viewModel.uiState.value.selectedAmountFen)
    }

    @Test fun customAmountOverridesRecommendedAmountAndRequiresPaymentMethod() = runTest(dispatcher) {
        val viewModel = RechargeViewModel("origin", "command-1", FakeRepository())
        advanceUntilIdle()
        viewModel.onAction(RechargeAction.RecommendedAmountSelected(5_000))
        viewModel.onAction(RechargeAction.CustomAmountChanged("88.50"))
        assertEquals(8_850L, viewModel.uiState.value.selectedAmountFen)
        assertFalse(viewModel.uiState.value.submitEnabled)
        viewModel.onAction(RechargeAction.PaymentMethodSelected(PaymentMethod.ALIPAY))
        assertTrue(viewModel.uiState.value.submitEnabled)
    }

    @Test fun submitCreatesOneIdempotentOrderAndShowsPendingPayment() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = RechargeViewModel("origin", "command-1", repository)
        advanceUntilIdle()
        viewModel.onAction(RechargeAction.RecommendedAmountSelected(5_000))
        viewModel.onAction(RechargeAction.PaymentMethodSelected(PaymentMethod.WECHAT_PAY))
        viewModel.onAction(RechargeAction.SubmitRequested)
        advanceUntilIdle()

        assertEquals(listOf(Triple(5_000L, PaymentMethod.WECHAT_PAY, "command-1")), repository.submissions)
        assertEquals(RechargeOrderStatus.PENDING_PAYMENT, viewModel.uiState.value.orderStatus)
        assertEquals("order-1", viewModel.uiState.value.orderId)
    }

    @Test fun paidReturnsToExactOriginWhileProcessingStaysOnPage() = runTest(dispatcher) {
        val viewModel = RechargeViewModel("origin-ai-task", "command-1", FakeRepository())
        advanceUntilIdle()
        viewModel.onAction(RechargeAction.OrderStatusChanged(RechargeOrderStatus.IN_PROGRESS))
        assertEquals(RechargeOrderStatus.IN_PROGRESS, viewModel.uiState.value.orderStatus)

        val effect = async { viewModel.effects.first() }
        viewModel.onAction(RechargeAction.OrderStatusChanged(RechargeOrderStatus.PAID))
        advanceUntilIdle()
        assertEquals(RechargeEffect.ReturnToOrigin("origin-ai-task"), effect.await())
    }

    @Test fun refreshingAfterReturningFromPaymentUsesServerOrderStatus() = runTest(dispatcher) {
        val repository = FakeRepository().apply { loadedOrderStatus = RechargeOrderStatus.PAID }
        val viewModel = RechargeViewModel("origin-fees", "command-1", repository)
        advanceUntilIdle()
        viewModel.onAction(RechargeAction.RecommendedAmountSelected(5_000))
        viewModel.onAction(RechargeAction.PaymentMethodSelected(PaymentMethod.ALIPAY))
        viewModel.onAction(RechargeAction.SubmitRequested)
        advanceUntilIdle()
        val effect = async { viewModel.effects.first { it is RechargeEffect.ReturnToOrigin } }
        viewModel.onAction(RechargeAction.RefreshOrderRequested)
        advanceUntilIdle()
        assertEquals(RechargeOrderStatus.PAID, viewModel.uiState.value.orderStatus)
        assertEquals(RechargeEffect.ReturnToOrigin("origin-fees"), effect.await())
    }

    @Test fun failedOrCancelledStaysOnPageAndExplicitReturnUsesOrigin() = runTest(dispatcher) {
        val viewModel = RechargeViewModel("origin-fees", "command-1", FakeRepository())
        advanceUntilIdle()
        viewModel.onAction(RechargeAction.OrderStatusChanged(RechargeOrderStatus.FAILED))
        assertEquals(RechargeOrderStatus.FAILED, viewModel.uiState.value.orderStatus)
        viewModel.onAction(RechargeAction.OrderStatusChanged(RechargeOrderStatus.CANCELLED))
        assertEquals(RechargeOrderStatus.CANCELLED, viewModel.uiState.value.orderStatus)

        val effect = async { viewModel.effects.first() }
        viewModel.onAction(RechargeAction.ReturnRequested)
        advanceUntilIdle()
        assertEquals(RechargeEffect.ReturnToOrigin("origin-fees"), effect.await())
    }

    @Test fun loadSubmitAndRefreshFailuresStayRecoverable() = runTest(dispatcher) {
        val repository = FakeRepository().apply { pageResult = RechargePageResult.Failed }
        val viewModel = RechargeViewModel("origin", "command-1", repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)

        repository.pageResult = repository.readyPage
        viewModel.onAction(RechargeAction.RetryLoadRequested)
        advanceUntilIdle()
        viewModel.onAction(RechargeAction.CustomAmountChanged("not-money"))
        assertNull(viewModel.uiState.value.selectedAmountFen)
        viewModel.onAction(RechargeAction.RecommendedAmountSelected(5_000))
        viewModel.onAction(RechargeAction.PaymentMethodSelected(PaymentMethod.ALIPAY))
        repository.submitResult = RechargeSubmitResult.Failed
        viewModel.onAction(RechargeAction.SubmitRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.submitFailed)

        repository.submitResult = RechargeSubmitResult.Accepted(
            "order-1",
            RechargeOrderStatus.PENDING_PAYMENT,
        )
        viewModel.onAction(RechargeAction.SubmitRequested)
        advanceUntilIdle()
        repository.orderResult = RechargeSubmitResult.Failed
        viewModel.onAction(RechargeAction.RefreshOrderRequested)
        advanceUntilIdle()
        assertEquals(RechargeOrderStatus.PENDING_PAYMENT, viewModel.uiState.value.orderStatus)
    }

    private class FakeRepository : RechargePageRepository {
        val submissions = mutableListOf<Triple<Long, PaymentMethod, String>>()
        var loadedOrderStatus = RechargeOrderStatus.PENDING_PAYMENT
        val readyPage: RechargePageResult = RechargePageResult.Ready(
            RechargePageSnapshot(
                listOf(
                    RechargeOption(5_000, "开始把真正需要的英语练熟"),
                    RechargeOption(20_000, "持续积累一批能够自信使用的表达"),
                ),
            ),
        )
        var pageResult: RechargePageResult = readyPage
        var submitResult: RechargeSubmitResult =
            RechargeSubmitResult.Accepted("order-1", RechargeOrderStatus.PENDING_PAYMENT)
        var orderResult: RechargeSubmitResult? = null
        override suspend fun loadPage(): RechargePageResult = pageResult

        override suspend fun createOrder(amountFen: Long, method: PaymentMethod, commandId: String): RechargeSubmitResult {
            submissions += Triple(amountFen, method, commandId)
            return submitResult
        }

        override suspend fun loadOrder(orderId: String) =
            orderResult ?: RechargeSubmitResult.Accepted(orderId, loadedOrderStatus)
    }
}
