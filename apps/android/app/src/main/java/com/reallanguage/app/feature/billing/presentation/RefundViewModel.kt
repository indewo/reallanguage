package com.reallanguage.app.feature.billing.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.billing.domain.RefundPageRepository
import com.reallanguage.app.feature.billing.domain.RefundPageResult
import com.reallanguage.app.feature.billing.domain.RefundSubmitResult
import com.reallanguage.app.feature.billing.domain.MinorRechargeSupportResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RefundViewModel(
    private val commandId: String,
    private val repository: RefundPageRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(RefundUiState())
    val uiState: StateFlow<RefundUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<RefundEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init { load() }

    fun onAction(action: RefundAction) {
        when (action) {
            RefundAction.RetryLoadRequested -> load()
            RefundAction.SubmitRequested -> submit()
            is RefundAction.StatusChanged -> mutableUiState.update { it.copy(requestStatus = action.status) }
            RefundAction.MinorRechargeSupportRequested -> submitMinorRechargeSupport()
            RefundAction.ReturnBillingRequested -> send(RefundEffect.ReturnBilling)
        }
    }

    private fun submitMinorRechargeSupport() {
        if (mutableUiState.value.supportSubmitting || mutableUiState.value.supportCaseId != null) return
        viewModelScope.launch {
            mutableUiState.update { it.copy(supportSubmitting = true, supportFailed = false) }
            when (val result = repository.submitMinorRechargeSupport("minor-$commandId")) {
                is MinorRechargeSupportResult.Accepted -> mutableUiState.update {
                    it.copy(supportSubmitting = false, supportCaseId = result.supportCaseId)
                }
                MinorRechargeSupportResult.Failed -> mutableUiState.update {
                    it.copy(supportSubmitting = false, supportFailed = true)
                }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(loading = true, loadFailed = false) }
            when (val result = repository.loadPage()) {
                is RefundPageResult.Ready -> mutableUiState.update {
                    it.copy(
                        refundableAmountFen = result.snapshot.refundableAmountFen,
                        refundMethodLabelZh = result.snapshot.refundMethodLabelZh,
                        requestId = result.snapshot.requestId,
                        requestStatus = result.snapshot.requestStatus,
                        loading = false,
                    )
                }
                RefundPageResult.Failed -> mutableUiState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    private fun submit() {
        val amount = mutableUiState.value.refundableAmountFen ?: return
        if (!mutableUiState.value.submitEnabled) return
        viewModelScope.launch {
            mutableUiState.update { it.copy(submitting = true, submitFailed = false) }
            when (val result = repository.submitRefund(amount, commandId)) {
                is RefundSubmitResult.Accepted -> mutableUiState.update {
                    it.copy(requestId = result.requestId, requestStatus = result.status, submitting = false)
                }
                RefundSubmitResult.Failed -> mutableUiState.update { it.copy(submitting = false, submitFailed = true) }
            }
        }
    }

    private fun send(effect: RefundEffect) { viewModelScope.launch { effectChannel.send(effect) } }
}
