package com.reallanguage.app.feature.billing.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.billing.domain.AiBillingRepository
import com.reallanguage.app.feature.billing.domain.AiBillingSnapshotResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AiBillingViewModel(
    private val repository: AiBillingRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AiBillingUiState())
    val uiState: StateFlow<AiBillingUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<AiBillingEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var loadJob: Job? = null
    private var loadInFlight = false
    private var departed = false
    private var loadGeneration = 0L

    init { load() }

    fun onAction(action: AiBillingAction) {
        if (departed) return
        when (action) {
            AiBillingAction.RetryRequested -> load()
            AiBillingAction.RechargeRequested -> send(AiBillingEffect.OpenRecharge)
            AiBillingAction.RefundRequested -> send(AiBillingEffect.OpenRefund)
            AiBillingAction.ReturnAccountRequested -> {
                depart()
                send(AiBillingEffect.ReturnAccount)
            }
        }
    }

    private fun load() {
        if (departed || loadInFlight) return
        // Hold the gate before launch; queued taps can arrive before Main dispatches the read.
        loadInFlight = true
        val generation = ++loadGeneration
        mutableUiState.update { it.copy(loading = true, loadFailed = false) }
        loadJob = viewModelScope.launch {
            try {
            val result = try { repository.loadSnapshot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { AiBillingSnapshotResult.Failed }
            // Some callbacks cannot be cancelled. They still cannot update a departed page.
            if (departed || generation != loadGeneration) return@launch
            when (result) {
                is AiBillingSnapshotResult.Ready -> mutableUiState.update {
                    it.copy(
                        balanceAmountFen = result.snapshot.balanceAmountFen,
                        charges = result.snapshot.charges.sortedByDescending { charge -> charge.occurredAtEpochMillis },
                        loading = false,
                    )
                }
                AiBillingSnapshotResult.Failed -> mutableUiState.update {
                    it.copy(balanceAmountFen = null, charges = emptyList(), loading = false, loadFailed = true)
                }
            }
            } finally {
                if (generation == loadGeneration) loadInFlight = false
            }
        }
    }

    private fun depart() {
        departed = true
        loadGeneration++
        loadJob?.cancel()
    }

    override fun onCleared() {
        depart()
        super.onCleared()
    }

    private fun send(effect: AiBillingEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }
}
