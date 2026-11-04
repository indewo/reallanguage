package com.reallanguage.app.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.auth.domain.AgreementDocumentRepository
import com.reallanguage.app.feature.auth.domain.AgreementDocumentResult
import com.reallanguage.app.feature.auth.domain.AgreementKind
import com.reallanguage.app.feature.auth.domain.RegistrationDraftSnapshot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgreementDetailViewModel(
    kind: AgreementKind,
    registrationDraft: RegistrationDraftSnapshot,
    private val repository: AgreementDocumentRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AgreementDetailUiState(kind, registrationDraft))
    val uiState: StateFlow<AgreementDetailUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<AgreementDetailEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init { load() }

    fun onAction(action: AgreementDetailAction) {
        when (action) {
            AgreementDetailAction.RetryRequested -> load()
            AgreementDetailAction.ReturnRequested -> send(
                AgreementDetailEffect.ReturnToRegistration(mutableUiState.value.registrationDraft),
            )
        }
    }

    private fun load() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(document = null, loading = true, loadFailed = false) }
            when (val result = repository.load(mutableUiState.value.kind)) {
                is AgreementDocumentResult.Ready -> mutableUiState.update {
                    it.copy(document = result.document, loading = false)
                }
                AgreementDocumentResult.Failed -> mutableUiState.update {
                    it.copy(document = null, loading = false, loadFailed = true)
                }
            }
        }
    }

    private fun send(effect: AgreementDetailEffect) { viewModelScope.launch { effectChannel.send(effect) } }
}
