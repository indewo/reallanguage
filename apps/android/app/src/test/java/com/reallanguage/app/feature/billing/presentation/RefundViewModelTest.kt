package com.reallanguage.app.feature.billing.presentation

import com.reallanguage.app.feature.billing.domain.RefundPageRepository
import com.reallanguage.app.feature.billing.domain.RefundPageResult
import com.reallanguage.app.feature.billing.domain.RefundPageSnapshot
import com.reallanguage.app.feature.billing.domain.RefundRequestStatus
import com.reallanguage.app.feature.billing.domain.RefundSubmitResult
import com.reallanguage.app.feature.billing.domain.MinorRechargeSupportResult
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
class RefundViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsServerCalculatedRefundableBalanceAndExistingStatus() = runTest(dispatcher) {
        val viewModel = RefundViewModel("refund-command", FakeRepository())
        advanceUntilIdle()
        assertEquals(8_800L, viewModel.uiState.value.refundableAmountFen)
        assertEquals("原支付方式", viewModel.uiState.value.refundMethodLabelZh)
        assertNull(viewModel.uiState.value.requestStatus)
        assertTrue(viewModel.uiState.value.submitEnabled)
    }

    @Test fun zeroRefundableBalanceCannotCreateRequest() = runTest(dispatcher) {
        val repository = FakeRepository(snapshot = RefundPageSnapshot(0, "原支付方式"))
        val viewModel = RefundViewModel("refund-command", repository)
        advanceUntilIdle()
        viewModel.onAction(RefundAction.SubmitRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.submitEnabled)
        assertTrue(repository.submissions.isEmpty())
    }

    @Test fun submitRequestsFullRefundableBalanceIdempotentlyAndShowsReview() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = RefundViewModel("refund-command", repository)
        advanceUntilIdle()
        viewModel.onAction(RefundAction.SubmitRequested)
        advanceUntilIdle()
        assertEquals(listOf(8_800L to "refund-command"), repository.submissions)
        assertEquals("refund-1", viewModel.uiState.value.requestId)
        assertEquals(RefundRequestStatus.REVIEWING, viewModel.uiState.value.requestStatus)
        assertFalse(viewModel.uiState.value.submitEnabled)
    }

    @Test fun statusUpdatesStayVisibleAndReturnToBillingIsExplicit() = runTest(dispatcher) {
        val viewModel = RefundViewModel("refund-command", FakeRepository())
        advanceUntilIdle()
        viewModel.onAction(RefundAction.StatusChanged(RefundRequestStatus.APPROVED))
        assertEquals(RefundRequestStatus.APPROVED, viewModel.uiState.value.requestStatus)
        viewModel.onAction(RefundAction.StatusChanged(RefundRequestStatus.REFUNDED))
        assertEquals(RefundRequestStatus.REFUNDED, viewModel.uiState.value.requestStatus)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(RefundAction.ReturnBillingRequested)
        advanceUntilIdle()
        assertEquals(RefundEffect.ReturnBilling, effect.await())
    }

    @Test fun minorRechargeHasDedicatedHumanSupportRouteAndFailureCanRetry() = runTest(dispatcher) {
        val repository = FakeRepository(submitResult = RefundSubmitResult.Failed)
        val viewModel = RefundViewModel("refund-command", repository)
        advanceUntilIdle()
        viewModel.onAction(RefundAction.MinorRechargeSupportRequested)
        advanceUntilIdle()
        assertEquals("support-1", viewModel.uiState.value.supportCaseId)
        assertEquals(listOf("minor-refund-command"), repository.supportSubmissions)

        viewModel.onAction(RefundAction.SubmitRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.submitFailed)
        repository.submitResult = RefundSubmitResult.Accepted("refund-1", RefundRequestStatus.REQUESTED)
        viewModel.onAction(RefundAction.SubmitRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.submitFailed)
        assertEquals(2, repository.submissions.size)
    }

    private class FakeRepository(
        private val snapshot: RefundPageSnapshot = RefundPageSnapshot(8_800, "原支付方式"),
        var submitResult: RefundSubmitResult = RefundSubmitResult.Accepted("refund-1", RefundRequestStatus.REVIEWING),
    ) : RefundPageRepository {
        val submissions = mutableListOf<Pair<Long, String>>()
        val supportSubmissions = mutableListOf<String>()
        override suspend fun loadPage(): RefundPageResult = RefundPageResult.Ready(snapshot)
        override suspend fun submitRefund(amountFen: Long, commandId: String): RefundSubmitResult {
            submissions += amountFen to commandId
            return submitResult
        }
        override suspend fun submitMinorRechargeSupport(commandId: String): MinorRechargeSupportResult {
            supportSubmissions += commandId
            return MinorRechargeSupportResult.Accepted("support-1")
        }
    }
}
