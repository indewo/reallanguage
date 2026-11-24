package com.reallanguage.app.feature.billing.domain

enum class AiChargeType {
    INITIAL_CALL,
    AUTOMATIC_RETRY,
    USER_RETRY,
    PROVIDER_SWITCH,
}

data class AiChargeRecord(
    val chargeId: String,
    val amountFen: Long,
    val occurredAtEpochMillis: Long,
    val modelSource: String,
    val callType: AiChargeType,
    val operationLabelZh: String,
)

data class AiBillingSnapshot(
    val balanceAmountFen: Long,
    val charges: List<AiChargeRecord>,
    val dailyCharges: List<DailyAiCharge> = emptyList(),
)

data class DailyAiCharge(val date: String, val operationLabelZh: String, val count: Int, val amountFen: Long)

sealed interface AiBillingSnapshotResult {
    data class Ready(val snapshot: AiBillingSnapshot) : AiBillingSnapshotResult
    data object Failed : AiBillingSnapshotResult
}

interface AiBillingRepository {
    suspend fun loadSnapshot(): AiBillingSnapshotResult
}
