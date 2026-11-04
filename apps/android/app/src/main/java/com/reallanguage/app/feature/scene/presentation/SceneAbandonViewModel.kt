package com.reallanguage.app.feature.scene.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.scene.domain.SceneAbandonRepository
import com.reallanguage.app.feature.scene.domain.SceneAbandonResult
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SceneAbandonViewModel(
    private val session: SceneQuestionSession,
    private val commandId: String,
    private val repository: SceneAbandonRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SceneAbandonUiState(session))
    val uiState: StateFlow<SceneAbandonUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<SceneAbandonEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: SceneAbandonAction) {
        when (action) {
            SceneAbandonAction.ContinueRequested -> {
                if (!mutableUiState.value.deleting) {
                    effectChannel.trySend(SceneAbandonEffect.ReturnToQuestions(session))
                }
            }
            SceneAbandonAction.ConfirmRequested -> abandon()
        }
    }

    private fun abandon() {
        if (mutableUiState.value.deleting) return
        mutableUiState.update { it.copy(deleting = true, error = null) }
        viewModelScope.launch {
            val result = try {
                repository.abandon(session, commandId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                SceneAbandonResult.Failed
            }
            when (result) {
                SceneAbandonResult.Abandoned -> effectChannel.send(SceneAbandonEffect.OpenHome)
                SceneAbandonResult.Failed -> mutableUiState.update {
                    it.copy(deleting = false, error = SceneAbandonError.DELETE_FAILED)
                }
            }
        }
    }
}
