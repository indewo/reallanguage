package com.reallanguage.app.feature.error.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.error.domain.ConnectionRecoveryRepository
import com.reallanguage.app.feature.error.domain.ConnectionRecoveryResult
import com.reallanguage.app.feature.error.domain.ConnectionResumePoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ConnectionErrorViewModel(
    resumePoint: ConnectionResumePoint,
    private val repository: ConnectionRecoveryRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ConnectionErrorUiState(resumePoint))
    val uiState: StateFlow<ConnectionErrorUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ConnectionErrorEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var request: Job? = null
    private var generation = 0L
    private var departed = false

    fun canNavigateToLogin(): Boolean = repository.canNavigateToLogin()

    fun onAction(action: ConnectionErrorAction) {
        if (departed) return
        when (action) {
            ConnectionErrorAction.ReconnectRequested -> reconnect()
            ConnectionErrorAction.ReturnRequested -> {
                depart()
                effectChannel.trySend(ConnectionErrorEffect.ReturnUsingCurrentFlowRule(mutableUiState.value.resumePoint.pageId))
            }
        }
    }

    private fun reconnect() {
        if (mutableUiState.value.reconnecting) return
        val owner = ++generation
        mutableUiState.update { it.copy(reconnecting = true, reconnectFailed = false) }
        request = viewModelScope.launch {
            try {
                val result = try { repository.verifyNetworkAndAccount() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ConnectionRecoveryResult.Unavailable }
                if (departed || owner != generation) return@launch
                when (result) {
                    ConnectionRecoveryResult.LoginRequired, ConnectionRecoveryResult.InitialSettingsRequired -> {
                        departed = true
                        mutableUiState.update { it.copy(reconnecting = false) }
                        effectChannel.trySend(if (result == ConnectionRecoveryResult.LoginRequired) ConnectionErrorEffect.LoginRequired
                            else ConnectionErrorEffect.InitialSettingsRequired)
                    }
                    ConnectionRecoveryResult.Restored -> {
                        departed = true
                        mutableUiState.update { it.copy(reconnecting = false, overlayVisible = false) }
                        effectChannel.trySend(ConnectionErrorEffect.Restore(mutableUiState.value.resumePoint))
                    }
                    ConnectionRecoveryResult.Unavailable -> mutableUiState.update {
                        it.copy(reconnecting = false, reconnectFailed = true, overlayVisible = true)
                    }
                }
            } finally {
                if (owner == generation) mutableUiState.update { it.copy(reconnecting = false) }
            }
        }
    }

    private fun depart() {
        departed = true
        generation++
        request?.cancel()
        mutableUiState.update { it.copy(reconnecting = false) }
    }

    override fun onCleared() { depart(); super.onCleared() }
}
