package com.reallanguage.app.feature.safety.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SafetyCheckRepository
import com.reallanguage.app.feature.safety.domain.SafetyCheckResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SafetyCheckViewModel(
    source: ConfirmedSource,
    private val repository: SafetyCheckRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SafetyCheckUiState(source))
    val uiState: StateFlow<SafetyCheckUiState> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<SafetyCheckEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init {
        check()
    }

    fun onAction(action: SafetyCheckAction) {
        when (action) {
            SafetyCheckAction.RetryRequested -> check()
        }
    }

    private fun check() {
        if (mutableUiState.value.processing) return
        mutableUiState.update { it.copy(processing = true, failed = false) }
        viewModelScope.launch {
            val source = mutableUiState.value.source
            when (val result = repository.check(source)) {
                SafetyCheckResult.Passed -> effectChannel.send(
                    SafetyCheckEffect.OpenFullSentenceSplit(source),
                )
                is SafetyCheckResult.Blocked -> effectChannel.send(
                    SafetyCheckEffect.OpenSafetyHandling(result.safetyCaseId, source),
                )
                SafetyCheckResult.Failed -> mutableUiState.update {
                    it.copy(processing = false, failed = true)
                }
            }
        }
    }
}
