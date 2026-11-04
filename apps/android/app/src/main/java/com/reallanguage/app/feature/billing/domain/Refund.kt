package com.reallanguage.app.feature.billing.domain

enum class RefundRequestStatus {
    REQUESTED,
    REVIEWING,
    APPROVED,
    REFUNDED,
    REJECTED,
    FAILED,
}

data class RefundPageSnapshot(
    val refundableAmountFen: Long,
    val refundMethodLabelZh: String,
    val requestId: String? = null,
    val requestStatus: RefundRequestStatus? = null,
)

sealed interface RefundPageResult {
    data class Ready(val snapshot: RefundPageSnapshot) : RefundPageResult
    data object Failed : RefundPageResult
}

sealed interface RefundSubmitResult {
    data class Accepted(val requestId: String, val status: RefundRequestStatus) : RefundSubmitResult
    data object Failed : RefundSubmitResult
}
sealed interface MinorRechargeSupportResult {
    data class Accepted(val supportCaseId: String) : MinorRechargeSupportResult
    data object Failed : MinorRechargeSupportResult
}

interface RefundPageRepository {
    suspend fun loadPage(): RefundPageResult
    suspend fun submitRefund(amountFen: Long, commandId: String): RefundSubmitResult
    suspend fun submitMinorRechargeSupport(commandId: String): MinorRechargeSupportResult
}
