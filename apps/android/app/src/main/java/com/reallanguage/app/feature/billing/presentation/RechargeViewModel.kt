package com.reallanguage.app.feature.billing.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.billing.domain.RechargeOrderStatus
import com.reallanguage.app.feature.billing.domain.RechargePageRepository
import com.reallanguage.app.feature.billing.domain.RechargePageResult
import com.reallanguage.app.feature.billing.domain.RechargeSubmitResult
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RechargeViewModel(
    private val originId: String,
    private val commandId: String,
    private val repository: RechargePageRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(RechargeUiState(originId))
    val uiState: StateFlow<RechargeUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<RechargeEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init { load() }

    fun onAction(action: RechargeAction) {
        when (action) {
            is RechargeAction.RecommendedAmountSelected -> mutableUiState.update {
                it.copy(selectedAmountFen = action.amountFen.takeIf { amount -> amount > 0 }, customAmountText = "", submitFailed = false)
            }
            is RechargeAction.CustomAmountChanged -> mutableUiState.update {
                it.copy(customAmountText = action.value, selectedAmountFen = action.value.toFen(), submitFailed = false)
            }
            is RechargeAction.PaymentMethodSelected -> mutableUiState.update { it.copy(selectedMethod = action.method, submitFailed = false) }
            RechargeAction.SubmitRequested -> submit()
            RechargeAction.RefreshOrderRequested -> refreshOrder()
            is RechargeAction.OrderStatusChanged -> updateStatus(action.status)
            RechargeAction.RetryLoadRequested -> load()
            RechargeAction.ReturnRequested -> send(RechargeEffect.ReturnToOrigin(originId))
        }
    }

    private fun load() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(loading = true, loadFailed = false) }
            when (val result = repository.loadPage()) {
                is RechargePageResult.Ready -> mutableUiState.update {
                    it.copy(recommendedOptions = result.snapshot.recommendedOptions, loading = false)
                }
                RechargePageResult.Failed -> mutableUiState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    private fun submit() {
        val state = mutableUiState.value
        val amount = state.selectedAmountFen ?: return
        val method = state.selectedMethod ?: return
        if (!state.submitEnabled) return
        viewModelScope.launch {
            mutableUiState.update { it.copy(submitting = true, submitFailed = false) }
            when (val result = repository.createOrder(amount, method, commandId)) {
                is RechargeSubmitResult.Accepted -> mutableUiState.update {
                    it.copy(orderId = result.orderId, orderStatus = result.status, submitting = false)
                }.also { result.paymentLaunch?.let { launch -> send(RechargeEffect.OpenPayment(launch)) } }
                RechargeSubmitResult.Failed -> mutableUiState.update { it.copy(submitting = false, submitFailed = true) }
            }
        }
    }

    private fun updateStatus(status: RechargeOrderStatus) {
        mutableUiState.update { it.copy(orderStatus = status, submitting = false) }
        if (status == RechargeOrderStatus.PAID) send(RechargeEffect.ReturnToOrigin(originId))
    }

    private fun refreshOrder() {
        val orderId = mutableUiState.value.orderId ?: return
        viewModelScope.launch {
            when (val result = repository.loadOrder(orderId)) {
                is RechargeSubmitResult.Accepted -> updateStatus(result.status)
                RechargeSubmitResult.Failed -> Unit
            }
        }
    }

    private fun send(effect: RechargeEffect) { viewModelScope.launch { effectChannel.send(effect) } }
}

private fun String.toFen(): Long? = try {
    BigDecimal(trim()).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact().takeIf { it > 0 }
} catch (_: ArithmeticException) {
    null
} catch (_: NumberFormatException) {
    null
}
