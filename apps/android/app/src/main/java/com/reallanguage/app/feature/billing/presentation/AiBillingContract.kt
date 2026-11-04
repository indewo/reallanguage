package com.reallanguage.app.feature.billing.presentation

import com.reallanguage.app.feature.billing.domain.AiChargeRecord

data class AiBillingUiState(
    val balanceAmountFen: Long? = null,
    val charges: List<AiChargeRecord> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
)

sealed interface AiBillingAction {
    data object RetryRequested : AiBillingAction
    data object RechargeRequested : AiBillingAction
    data object RefundRequested : AiBillingAction
    data object ReturnAccountRequested : AiBillingAction
}

sealed interface AiBillingEffect {
    data object OpenRecharge : AiBillingEffect
    data object OpenRefund : AiBillingEffect
    data object ReturnAccount : AiBillingEffect
}
