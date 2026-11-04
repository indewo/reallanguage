package com.reallanguage.app.feature.formation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EnglishConfirmationViewModel(
    private val chapters: List<EnglishChapterDraft>,
    initialSelectedCandidateIds: Map<String, String> = emptyMap(),
) : ViewModel() {
    private val initialSelections = chapters.flatMap { it.sentences }
        .filter { it.candidates.size == 1 }
        .associate { it.unitId to it.candidates.single().candidateId } +
        chapters.flatMap { it.sentences }.mapNotNull { sentence ->
            initialSelectedCandidateIds[sentence.unitId]?.takeIf { selected ->
                sentence.candidates.count { it.candidateId == selected } == 1
            }?.let { sentence.unitId to it }
        }.toMap()
    private val sentenceCount = chapters.sumOf { it.sentences.size }
    private val mutableUiState = MutableStateFlow(
        EnglishConfirmationUiState(
            chapters = chapters,
            selectedCandidateIds = initialSelections,
            continueEnabled = initialSelections.size == sentenceCount,
        ),
    )
    val uiState: StateFlow<EnglishConfirmationUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<EnglishConfirmationEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: EnglishConfirmationAction) {
        if (mutableUiState.value.submitting) return
        when (action) {
            is EnglishConfirmationAction.CandidateSelected -> selectCandidate(action)
            EnglishConfirmationAction.ContinueRequested -> continueToTargets()
            EnglishConfirmationAction.BackRequested -> viewModelScope.launch {
                effectChannel.send(EnglishConfirmationEffect.ReturnToFocusSelection)
            }
        }
    }

    private fun selectCandidate(action: EnglishConfirmationAction.CandidateSelected) {
        val sentence = chapters.flatMap { it.sentences }
            .firstOrNull { it.unitId == action.unitId } ?: return
        if (sentence.candidates.none { it.candidateId == action.candidateId }) return
        mutableUiState.update {
            val selections = it.selectedCandidateIds + (action.unitId to action.candidateId)
            it.copy(
                selectedCandidateIds = selections,
                continueEnabled = selections.size == sentenceCount,
            )
        }
    }

    private fun continueToTargets() {
        val current = mutableUiState.value
        if (!current.continueEnabled) return
        mutableUiState.update { it.copy(submitting = true, continueEnabled = false) }
        viewModelScope.launch {
            effectChannel.send(
                EnglishConfirmationEffect.OpenLearningTargets(
                    chapters,
                    current.selectedCandidateIds,
                ),
            )
        }
    }
}
