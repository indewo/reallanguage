package com.reallanguage.app.feature.home.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.home.domain.MessageTargetState
import com.reallanguage.app.feature.home.domain.MessagesLoadResult
import com.reallanguage.app.feature.home.domain.MessagesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MessagesViewModel(private val repository: MessagesRepository) : ViewModel() {
    private val mutableUiState = MutableStateFlow(MessagesUiState())
    val uiState: StateFlow<MessagesUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<MessagesEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var operationJob: Job? = null
    private var operationVersion = 0L
    private var departed = false
    init { load() }

    fun onAction(action: MessagesAction) {
        if (departed) return
        when (action) {
            is MessagesAction.MessageRequested -> open(action.messageId)
            MessagesAction.MarkAllReadRequested -> markAllRead()
            MessagesAction.RetryRequested -> load()
            MessagesAction.ReturnHomeRequested -> leave(MessagesEffect.OpenHome)
        }
    }
    private fun markAllRead() {
        if (departed || operationJob?.isActive == true || mutableUiState.value.loading) return
        val version = ++operationVersion
        mutableUiState.update { it.copy(markingAllRead = true, markAllReadFailed = false) }
        operationJob = viewModelScope.launch {
            try {
                val accepted = try { repository.markAllRead() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                currentCoroutineContext().ensureActive()
                if (!isCurrent(version)) return@launch
                mutableUiState.update { state -> state.copy(
                    messages = if (accepted) state.messages.map { it.copy(read = true) } else state.messages,
                    markingAllRead = false,
                    markAllReadFailed = !accepted,
                ) }
            } finally {
                if (isCurrent(version)) mutableUiState.update { it.copy(markingAllRead = false) }
            }
        }
    }
    private fun leave(effect: MessagesEffect) {
        if (departed) return
        departed = true
        operationVersion++
        operationJob?.cancel()
        mutableUiState.update { it.copy(loading = false, markingAllRead = false) }
        effectChannel.trySend(effect)
    }
    private fun isCurrent(version: Long) = !departed && version == operationVersion

    override fun onCleared() {
        departed = true
        operationVersion++
        operationJob?.cancel()
        super.onCleared()
    }

    private fun open(messageId: String) {
        if (mutableUiState.value.loading) return
        val message = mutableUiState.value.messages.firstOrNull { it.messageId == messageId } ?: return
        val target = message.target
        when {
            message.targetState == MessageTargetState.UNAVAILABLE || target == null ->
                mutableUiState.update { it.copy(unavailableMessageId = messageId) }
            message.targetState == MessageTargetState.REQUIRES_LOGIN ->
                leave(MessagesEffect.OpenLoginForTarget(target))
            else -> leave(MessagesEffect.OpenTarget(target))
        }
    }
    private fun load() {
        if (departed || operationJob?.isActive == true) return
        val version = ++operationVersion
        mutableUiState.update { it.copy(loading = true, loadFailed = false) }
        operationJob = viewModelScope.launch {
            try {
                val result = try { repository.load() }
                catch (cancellation: CancellationException) { throw cancellation }
                catch (_: Exception) { MessagesLoadResult.Failed }
                currentCoroutineContext().ensureActive()
                if (!isCurrent(version)) return@launch
                mutableUiState.value = when (result) {
                    is MessagesLoadResult.Ready -> MessagesUiState(
                        messages = result.messages.sortedByDescending { it.createdAtEpochMillis }, loading = false,
                    )
                    MessagesLoadResult.Failed -> mutableUiState.value.copy(loading = false, loadFailed = true)
                }
            } finally {
                if (isCurrent(version)) mutableUiState.update { it.copy(loading = false) }
            }
        }
    }
}
