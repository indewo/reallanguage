package com.reallanguage.app.feature.scene.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneEditReviewRepository
import com.reallanguage.app.feature.scene.domain.SceneEditReviewRequest
import com.reallanguage.app.feature.scene.domain.SceneEditReviewResult
import com.reallanguage.app.feature.scene.domain.SceneGeneratedDraft
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

class SceneChineseReviewViewModel(
    private val session: SceneQuestionSession,
    private val draft: SceneGeneratedDraft,
    private val commandId: String,
    private val repository: SceneEditReviewRepository,
) : ViewModel() {
    private val reviewCommandIds = mutableMapOf<List<com.reallanguage.app.feature.scene.domain.SceneDraftUnit>, String>()
    private val originalById = draft.units.associateBy { it.unitId }
    private val mutableUiState = MutableStateFlow(
        SceneChineseReviewUiState(session = session, units = draft.units),
    )
    val uiState: StateFlow<SceneChineseReviewUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<SceneChineseReviewEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: SceneChineseReviewAction) {
        when (action) {
            is SceneChineseReviewAction.UnitChanged -> updateUnit(action.unitId, action.textZh)
            SceneChineseReviewAction.ConfirmRequested -> confirm()
            SceneChineseReviewAction.ReturnToQuestionsRequested -> navigate(
                SceneChineseReviewEffect.ReturnToQuestions(session),
            )
            SceneChineseReviewAction.RegenerateRequested -> navigate(
                SceneChineseReviewEffect.OpenGeneration(session, mutableUiState.value.units),
            )
        }
    }

    private fun updateUnit(unitId: String, textZh: String) {
        if (mutableUiState.value.reviewing || unitId !in originalById) return
        mutableUiState.update { state ->
            val changed = if (originalById[unitId]?.textZh == textZh) {
                state.changedUnitIds - unitId
            } else {
                state.changedUnitIds + unitId
            }
            state.copy(
                units = state.units.map { if (it.unitId == unitId) it.copy(textZh = textZh) else it },
                changedUnitIds = changed,
                issues = emptyList(),
                error = null,
            )
        }
    }

    private fun confirm() {
        val state = mutableUiState.value
        if (state.reviewing) return
        if (state.units.any { it.textZh.isBlank() }) {
            mutableUiState.update { it.copy(error = SceneChineseReviewError.EMPTY_CONTENT) }
            return
        }
        if (state.changedUnitIds.isEmpty()) {
            effectChannel.trySend(state.confirmedEffect())
            return
        }
        mutableUiState.update { it.copy(reviewing = true, error = null, issues = emptyList()) }
        val reviewCommandId = reviewCommandIds.getOrPut(state.units) {
            if (reviewCommandIds.isEmpty()) commandId else java.util.UUID.nameUUIDFromBytes(
                (commandId + state.units.joinToString("") { "${it.unitId.length}:${it.unitId}${it.textZh.length}:${it.textZh}" }).toByteArray(),
            ).toString()
        }
        viewModelScope.launch {
            val result = try {
                repository.review(
                    SceneEditReviewRequest(
                        originalDraft = draft,
                        editedUnits = state.units,
                        changedUnitIds = state.changedUnitIds,
                        contentType = session.contentType,
                    ),
                    reviewCommandId,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                SceneEditReviewResult.Failed
            }
            when (result) {
                SceneEditReviewResult.Pass -> {
                    // Publish the exact payload that passed review, not a later
                    // visible edit or the old generation response kept by the route.
                    val confirmed = state.confirmedEffect()
                    mutableUiState.update { it.copy(reviewing = false) }
                    effectChannel.send(confirmed)
                }
                is SceneEditReviewResult.NeedsUserEdit -> mutableUiState.update {
                    it.copy(reviewing = false, issues = result.issues)
                }
                SceneEditReviewResult.Failed -> mutableUiState.update {
                    it.copy(reviewing = false, error = SceneChineseReviewError.REVIEW_FAILED)
                }
            }
        }
    }

    private fun navigate(effect: SceneChineseReviewEffect) {
        if (!mutableUiState.value.reviewing) effectChannel.trySend(effect)
    }

    private fun SceneChineseReviewUiState.confirmedEffect() = SceneChineseReviewEffect.OpenSafetyCheck(
        toConfirmedSource(), draft.copy(units = units),
    )
}

private fun SceneChineseReviewUiState.toConfirmedSource(): ConfirmedSource {
    val text = when (session.contentType) {
        SceneContentType.ARTICLE -> units.joinToString("\n") { it.textZh.trim() }
        SceneContentType.DIALOGUE -> units.joinToString("\n") {
            val role = if (it.speaker == SceneSpeaker.USER) "用户" else "对方"
            "$role：${it.textZh.trim()}"
        }
    }
    return ConfirmedSource(
        text = text,
        sourceType = SourceType.AI_SCENE,
        contentShape = when (session.contentType) {
            SceneContentType.ARTICLE -> SourceContentShape.ARTICLE
            SceneContentType.DIALOGUE -> SourceContentShape.DIALOGUE
        },
    )
}
