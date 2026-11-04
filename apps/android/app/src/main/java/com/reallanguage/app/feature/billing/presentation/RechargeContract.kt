package com.reallanguage.app.feature.billing.presentation

import com.reallanguage.app.feature.billing.domain.PaymentMethod
import com.reallanguage.app.feature.billing.domain.RechargeOption
import com.reallanguage.app.feature.billing.domain.RechargeOrderStatus

data class RechargeUiState(
    val originId: String,
    val balanceAmountFen: Long? = null,
    val recommendedOptions: List<RechargeOption> = emptyList(),
    val selectedAmountFen: Long? = null,
    val customAmountText: String = "",
    val selectedMethod: PaymentMethod? = null,
    val orderId: String? = null,
    val orderStatus: RechargeOrderStatus? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val submitting: Boolean = false,
    val submitFailed: Boolean = false,
) {
    val submitEnabled: Boolean
        get() = selectedAmountFen != null && selectedAmountFen > 0 && selectedMethod != null && !submitting
}

sealed interface RechargeAction {
    data class RecommendedAmountSelected(val amountFen: Long) : RechargeAction
    data class CustomAmountChanged(val value: String) : RechargeAction
    data class PaymentMethodSelected(val method: PaymentMethod) : RechargeAction
    data object SubmitRequested : RechargeAction
    data object RefreshOrderRequested : RechargeAction
    data class OrderStatusChanged(val status: RechargeOrderStatus) : RechargeAction
    data object RetryLoadRequested : RechargeAction
    data object ReturnRequested : RechargeAction
}

sealed interface RechargeEffect {
    data class OpenPayment(val paymentLaunch: String) : RechargeEffect
    data class ReturnToOrigin(val originId: String) : RechargeEffect
}
