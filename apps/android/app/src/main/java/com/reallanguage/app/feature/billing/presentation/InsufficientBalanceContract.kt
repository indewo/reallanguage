package com.reallanguage.app.feature.billing.presentation

import com.reallanguage.app.feature.billing.domain.BalanceInsufficientContext
import com.reallanguage.app.feature.billing.domain.AiChargeRecord

data class InsufficientBalanceUiState(
    val context: BalanceInsufficientContext,
    val balanceAmountFen: Long? = null,
    val recentCharges: List<AiChargeRecord> = emptyList(),
    val applyingExitPolicy: Boolean = false,
    val exitFailed: Boolean = false,
)

sealed interface InsufficientBalanceAction {
    data object RechargeRequested : InsufficientBalanceAction
    data object LeaveRequested : InsufficientBalanceAction
}

sealed interface InsufficientBalanceEffect {
    data class OpenRecharge(val originId: String) : InsufficientBalanceEffect
    data class ReturnAfterExit(val originId: String) : InsufficientBalanceEffect
}
