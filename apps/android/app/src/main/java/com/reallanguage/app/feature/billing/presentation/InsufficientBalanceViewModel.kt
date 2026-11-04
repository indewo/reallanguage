package com.reallanguage.app.feature.billing.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.billing.domain.BalanceExitResult
import com.reallanguage.app.feature.billing.domain.AiBillingRepository
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshotResult
import com.reallanguage.app.feature.billing.domain.BalanceInsufficientContext
import com.reallanguage.app.feature.billing.domain.BalanceInsufficientRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class InsufficientBalanceViewModel(
    context: BalanceInsufficientContext,
    private val commandId: String,
    private val repository: BalanceInsufficientRepository,
    private val billingRepository: AiBillingRepository? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(InsufficientBalanceUiState(context))
    val uiState: StateFlow<InsufficientBalanceUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<InsufficientBalanceEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init {
        billingRepository?.let { billing ->
            viewModelScope.launch {
                when (val result = billing.loadSnapshot()) {
                    is AiBillingSnapshotResult.Ready -> mutableUiState.update {
                        it.copy(
                            balanceAmountFen = result.snapshot.balanceAmountFen,
                            recentCharges = result.snapshot.charges.take(4),
                        )
                    }
                    AiBillingSnapshotResult.Failed -> Unit
                }
            }
        }
    }

    fun onAction(action: InsufficientBalanceAction) {
        when (action) {
            InsufficientBalanceAction.RechargeRequested -> send(
                InsufficientBalanceEffect.OpenRecharge(mutableUiState.value.context.originId),
            )
            InsufficientBalanceAction.LeaveRequested -> applyExitPolicy()
        }
    }

    private fun applyExitPolicy() {
        if (mutableUiState.value.applyingExitPolicy) return
        viewModelScope.launch {
            mutableUiState.update { it.copy(applyingExitPolicy = true, exitFailed = false) }
            val context = mutableUiState.value.context
            when (repository.applyExitPolicy(context.originId, context.exitDisposition, commandId)) {
                BalanceExitResult.Applied, BalanceExitResult.AlreadyApplied -> {
                    mutableUiState.update { it.copy(applyingExitPolicy = false) }
                    send(InsufficientBalanceEffect.ReturnAfterExit(context.originId))
                }
                BalanceExitResult.Failed -> mutableUiState.update {
                    it.copy(applyingExitPolicy = false, exitFailed = true)
                }
            }
        }
    }

    private fun send(effect: InsufficientBalanceEffect) { viewModelScope.launch { effectChannel.send(effect) } }
}
