package com.reallanguage.app.feature.scene.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneGeneratedDraft
import com.reallanguage.app.feature.scene.domain.SceneGenerationRepository
import com.reallanguage.app.feature.scene.domain.SceneGenerationRequest
import com.reallanguage.app.feature.scene.domain.SceneGenerationResult
import com.reallanguage.app.feature.scene.domain.SceneGenerationTask
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SceneGenerationViewModel(
    private val session: SceneQuestionSession,
    private val commandId: String,
    private val repository: SceneGenerationRepository,
    private val contentBudget: Int = 6,
    private val previousUnits: List<com.reallanguage.app.feature.scene.domain.SceneDraftUnit> = emptyList(),
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SceneGenerationUiState(session))
    val uiState: StateFlow<SceneGenerationUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<SceneGenerationEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init { generate() }

    fun onAction(action: SceneGenerationAction) {
        when (action) {
            SceneGenerationAction.RetryRequested -> generate()
            SceneGenerationAction.ReturnRequested -> {
                if (!mutableUiState.value.generating && mutableUiState.value.error != null) {
                    effectChannel.trySend(SceneGenerationEffect.ReturnToQuestions(session))
                }
            }
        }
    }

    private fun generate() {
        if (mutableUiState.value.generating) return
        mutableUiState.update { it.copy(generating = true, error = null) }
        val request = SceneGenerationRequest(
            task = when (session.contentType) {
                SceneContentType.DIALOGUE -> SceneGenerationTask.DIALOGUE_TURN
                SceneContentType.ARTICLE -> SceneGenerationTask.ARTICLE_DRAFT
            },
            session = session,
            remainingContentBudget = contentBudget,
            previousUnits = previousUnits,
        )
        viewModelScope.launch {
            val result = try {
                repository.generate(request, commandId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                SceneGenerationResult.Failed
            }
            when (result) {
                is SceneGenerationResult.Generated -> handleGenerated(result.draft)
                SceneGenerationResult.Failed -> fail()
            }
        }
    }

    private suspend fun handleGenerated(draft: SceneGeneratedDraft) {
        if (!draft.isValidFor(session.contentType, contentBudget)) {
            fail()
            return
        }
        mutableUiState.update { it.copy(generating = false) }
        effectChannel.send(SceneGenerationEffect.OpenChineseReview(session, draft))
    }

    private fun fail() {
        mutableUiState.update {
            it.copy(generating = false, error = SceneGenerationError.GENERATION_FAILED)
        }
    }
}

private fun SceneGeneratedDraft.isValidFor(type: SceneContentType, budget: Int): Boolean {
    if (contentType != type || draftId.isBlank() || units.isEmpty() || units.size > budget) return false
    if (units.any { it.unitId.isBlank() || it.textZh.isBlank() }) return false
    return when (type) {
        SceneContentType.ARTICLE -> units.all { it.speaker == null }
        SceneContentType.DIALOGUE -> units.all { it.speaker != null } &&
            units.any { it.speaker == SceneSpeaker.USER } &&
            units.any { it.speaker == SceneSpeaker.OTHER }
    }
}
