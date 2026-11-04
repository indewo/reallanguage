package com.reallanguage.app.feature.billing.presentation

import com.reallanguage.app.feature.billing.domain.RefundRequestStatus

data class RefundUiState(
    val refundableAmountFen: Long? = null,
    val refundMethodLabelZh: String? = null,
    val requestId: String? = null,
    val requestStatus: RefundRequestStatus? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val submitting: Boolean = false,
    val submitFailed: Boolean = false,
    val supportSubmitting: Boolean = false,
    val supportCaseId: String? = null,
    val supportFailed: Boolean = false,
) {
    val submitEnabled: Boolean
        get() = refundableAmountFen != null && refundableAmountFen > 0 && requestStatus == null && !submitting
}

sealed interface RefundAction {
    data object RetryLoadRequested : RefundAction
    data object SubmitRequested : RefundAction
    data class StatusChanged(val status: RefundRequestStatus) : RefundAction
    data object MinorRechargeSupportRequested : RefundAction
    data object ReturnBillingRequested : RefundAction
}

sealed interface RefundEffect {
    data object ReturnBilling : RefundEffect
}
