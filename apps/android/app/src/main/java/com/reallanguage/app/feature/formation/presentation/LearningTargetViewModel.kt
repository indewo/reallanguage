package com.reallanguage.app.feature.formation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetGenerationResult
import com.reallanguage.app.feature.formation.domain.LearningTargetGenerator
import com.reallanguage.app.feature.formation.domain.QueryableExpression
import com.reallanguage.app.feature.formation.domain.wordExpressions
import com.reallanguage.app.feature.formation.domain.hasValidRelatedVocabularyItems
import com.reallanguage.app.feature.formation.domain.hasConsistentVocabularyExplanations
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LearningTargetViewModel(
    chapters: List<EnglishChapterDraft>,
    selectedCandidateIds: Map<String, String>,
    extractedTargets: List<LearningTarget>,
    queryableExpressions: List<QueryableExpression>,
    private val generator: LearningTargetGenerator,
    initialSelectedTargetIds: Set<String>? = null,
    initialSentenceIndex: Int = 0,
) : ViewModel() {
    private val focusUnitIds = chapters.flatMap { it.sentences }
        .filter { it.isFocus }
        .mapTo(mutableSetOf()) { it.unitId }
    private val positionAvailable = initialSentenceIndex in 0 until chapters.sumOf { chapter -> chapter.sentences.count { it.isFocus } }
    private val focusTargets = extractedTargets.filter { it.unitId in focusUnitIds }
    private val mutableUiState = MutableStateFlow(
        LearningTargetUiState(
            chapters = chapters,
            selectedCandidateIds = selectedCandidateIds,
            targets = focusTargets,
            selectedTargetIds = initialSelectedTargetIds
                ?.filterTo(mutableSetOf()) { selected -> focusTargets.any { it.targetId == selected } }
                ?: focusTargets.mapTo(mutableSetOf()) { it.targetId },
            queryableExpressions = queryableExpressions.filter { it.unitId in focusUnitIds },
            focusSentenceIndex = initialSentenceIndex,
            continueEnabled = positionAvailable,
            recoveryUnavailable = !positionAvailable,
        ),
    )
    val uiState: StateFlow<LearningTargetUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<LearningTargetEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var unavailableReturnSent = false

    fun onAction(action: LearningTargetAction) {
        if (mutableUiState.value.submitting) return
        if (mutableUiState.value.recoveryUnavailable) {
            // Never silently clamp a corrupt saved position to a different sentence.
            if (action == LearningTargetAction.BackRequested && !unavailableReturnSent) {
                unavailableReturnSent = true
                viewModelScope.launch { effectChannel.send(LearningTargetEffect.ReturnToEnglishConfirmation) }
            }
            return
        }
        when (action) {
            is LearningTargetAction.TargetToggled -> toggle(action.targetId)
            is LearningTargetAction.ExpressionRequested -> generate(action)
            LearningTargetAction.ClearAllRequested -> mutableUiState.update {
                it.copy(selectedTargetIds = emptySet())
            }
            LearningTargetAction.ContinueRequested -> continueToFinal()
            LearningTargetAction.BackRequested -> {
                val current = mutableUiState.value
                if (!current.generating) {
                    if (current.focusSentenceIndex > 0) {
                        mutableUiState.update { it.copy(focusSentenceIndex = it.focusSentenceIndex - 1, generationFailed = false) }
                    } else viewModelScope.launch {
                        effectChannel.send(LearningTargetEffect.ReturnToEnglishConfirmation)
                    }
                }
            }
        }
    }

    private fun toggle(targetId: String) {
        val current = mutableUiState.value
        if (current.targets.none { it.targetId == targetId }) return
        val selected = current.selectedTargetIds.toMutableSet()
        if (!selected.remove(targetId)) selected += targetId
        mutableUiState.update { it.copy(selectedTargetIds = selected) }
    }

    private fun generate(action: LearningTargetAction.ExpressionRequested) {
        val current = mutableUiState.value
        if (current.generating || action.unitId != current.currentSentence?.unitId) return
        val words = current.currentSentence?.wordExpressions(current.selectedCandidateIds[current.currentSentence?.unitId]).orEmpty()
        val expression = (words + current.currentExpressions.filterNot { it.expressionId.startsWith("word:") }).firstOrNull {
            it.unitId == action.unitId && it.expressionId == action.expressionId
        } ?: return
        val existing = current.existingTargetForExpression(expression)
        if (existing != null) {
            toggle(existing.targetId)
            return
        }
        mutableUiState.update { it.copy(generating = true, generationFailed = false) }
        viewModelScope.launch {
            val generated = try {
                generator.generate(expression.unitId, expression.expressionId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                LearningTargetGenerationResult.Failed
            }
            val english = current.currentSentence?.candidates?.singleOrNull {
                it.candidateId == current.selectedCandidateIds[expression.unitId]
            }?.textEn.orEmpty()
            val result = if (generated is LearningTargetGenerationResult.Added && (
                !generated.target.matchesRequestedExpression(expression, english) ||
                    !generated.target.hasValidRelatedVocabularyItems(english) ||
                    !(mutableUiState.value.targets + generated.target).hasConsistentVocabularyExplanations() ||
                    generated.target.targetId.isBlank() ||
                    mutableUiState.value.targets.any { it.targetId == generated.target.targetId }
                )) LearningTargetGenerationResult.Failed else generated
            when (result) {
                is LearningTargetGenerationResult.Added -> mutableUiState.update {
                    it.copy(
                        targets = it.targets + result.target,
                        selectedTargetIds = it.selectedTargetIds + result.target.targetId,
                        queryableExpressions = it.queryableExpressions - expression,
                        generating = false,
                    )
                }
                LearningTargetGenerationResult.Failed -> mutableUiState.update {
                    it.copy(generating = false, generationFailed = true)
                }
            }
        }
    }

    private fun continueToFinal() {
        val current = mutableUiState.value
        if (!current.continueEnabled || current.generating) return
        if (current.focusSentenceIndex < current.focusSentences.lastIndex) {
            mutableUiState.update { it.copy(focusSentenceIndex = it.focusSentenceIndex + 1, generationFailed = false) }
            return
        }
        mutableUiState.update { it.copy(submitting = true, continueEnabled = false) }
        viewModelScope.launch {
            effectChannel.send(
                LearningTargetEffect.OpenFinalConfirmation(current.selectedTargetIds, current.targets),
            )
        }
    }
}
