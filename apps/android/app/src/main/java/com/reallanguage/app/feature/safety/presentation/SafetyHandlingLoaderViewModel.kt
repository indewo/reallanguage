package com.reallanguage.app.feature.safety.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.safety.domain.SafetyCaseDetails
import com.reallanguage.app.feature.safety.domain.SafetyHandlingDetailsRepository
import com.reallanguage.app.feature.safety.domain.SafetyHandlingDetailsResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SafetyHandlingLoaderUiState(val loading: Boolean = false, val failed: Boolean = false)
sealed interface SafetyHandlingLoaderEffect { data class OpenHandling(val details: SafetyCaseDetails) : SafetyHandlingLoaderEffect }
class SafetyHandlingLoaderViewModel(private val safetyCaseId: String, private val repository: SafetyHandlingDetailsRepository) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SafetyHandlingLoaderUiState())
    val uiState = mutableUiState.asStateFlow()
    private val effectsChannel = Channel<SafetyHandlingLoaderEffect>(Channel.BUFFERED)
    val effects = effectsChannel.receiveAsFlow()
    init { load() }
    fun retry() = load()
    private fun load() {
        if (mutableUiState.value.loading) return
        mutableUiState.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            when (val result = repository.load(safetyCaseId)) {
                is SafetyHandlingDetailsResult.Ready -> effectsChannel.send(SafetyHandlingLoaderEffect.OpenHandling(result.details))
                SafetyHandlingDetailsResult.Failed -> mutableUiState.update { it.copy(loading = false, failed = true) }
            }
        }
    }
}
