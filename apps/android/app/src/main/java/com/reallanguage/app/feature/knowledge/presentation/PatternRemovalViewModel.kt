package com.reallanguage.app.feature.knowledge.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.knowledge.domain.PatternRemovalRepository
import com.reallanguage.app.feature.knowledge.domain.PatternRemovalResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class PatternRemovalViewModel(
    patternId: String,
    commandId: String,
    private val repository: PatternRemovalRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(PatternRemovalUiState(patternId, commandId))
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<PatternRemovalEffect>(Channel.BUFFERED)
    private var completed = false
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: PatternRemovalAction) {
        if (mutableUiState.value.removing || completed) return
        when (action) {
            PatternRemovalAction.CancelRequested -> {
                // End this exact confirmation before its navigation effect is collected.
                // A queued click must not revive a choice the user already cancelled.
                completed = true
                send(PatternRemovalEffect.ReturnToDetail)
            }
            PatternRemovalAction.ConfirmRequested -> remove()
        }
    }
    private fun remove() {
        if (mutableUiState.value.removing || completed) return
        mutableUiState.update { it.copy(removing = true, removeFailed = false) }
        viewModelScope.launch {
            val result = try { repository.removeFromPatternBook(mutableUiState.value.patternId, mutableUiState.value.commandId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PatternRemovalResult.Failed }
            when (result) {
                PatternRemovalResult.Removed, PatternRemovalResult.AlreadyRemoved -> {
                    completed = true
                    mutableUiState.update { it.copy(removing = false) }
                    send(PatternRemovalEffect.PatternRemoved(mutableUiState.value.patternId))
                }
                PatternRemovalResult.Failed -> mutableUiState.update { it.copy(removing = false, removeFailed = true) }
            }
        }
    }
    private fun send(effect: PatternRemovalEffect) { viewModelScope.launch { effectChannel.send(effect) } }
}
