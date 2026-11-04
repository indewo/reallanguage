package com.reallanguage.app.feature.billing.domain

data class RechargeOption(
    val amountFen: Long,
    val visionZh: String,
)

enum class PaymentMethod { WECHAT_PAY, ALIPAY }

enum class RechargeOrderStatus {
    CREATED,
    PENDING_PAYMENT,
    IN_PROGRESS,
    PAID,
    FAILED,
    CANCELLED,
}

data class RechargePageSnapshot(val recommendedOptions: List<RechargeOption>)

sealed interface RechargePageResult {
    data class Ready(val snapshot: RechargePageSnapshot) : RechargePageResult
    data object Failed : RechargePageResult
}

sealed interface RechargeSubmitResult {
    data class Accepted(
        val orderId: String,
        val status: RechargeOrderStatus,
        val paymentLaunch: String? = null,
    ) : RechargeSubmitResult
    data object Failed : RechargeSubmitResult
}

interface RechargePageRepository {
    suspend fun loadPage(): RechargePageResult
    suspend fun createOrder(amountFen: Long, method: PaymentMethod, commandId: String): RechargeSubmitResult
    suspend fun loadOrder(orderId: String): RechargeSubmitResult = RechargeSubmitResult.Failed
}
