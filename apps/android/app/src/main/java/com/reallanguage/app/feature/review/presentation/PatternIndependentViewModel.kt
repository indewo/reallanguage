package com.reallanguage.app.feature.review.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.review.domain.PatternIndependentDecision
import com.reallanguage.app.feature.review.domain.PatternIndependentEvaluationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentGenerationResult
import com.reallanguage.app.feature.review.domain.PatternIndependentRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PatternIndependentViewModel(
    pattern: LockedSpeakingPattern,
    private val repository: PatternIndependentRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(PatternIndependentUiState(pattern, loading = false))
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<PatternIndependentEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var completionSent = false

    init { generate() }

    fun interruptUnsubmittedCapture() {
        val state = mutableUiState.value
        if (!state.recording || state.evaluating) return
        repository.cancelCapture()
        // The connection cover already explains the interruption. No request was
        // submitted: keep the existing practice visible, ready for explicit start.
        mutableUiState.update { it.copy(recording = false) }
    }

    fun onAction(action: PatternIndependentAction) {
        if (completionSent) return
        val current = mutableUiState.value
        if ((current.loading || current.evaluating) && action != PatternIndependentAction.BackRequested) return
        when (action) {
            PatternIndependentAction.BackRequested -> send(PatternIndependentEffect.ReturnToChapter)
            PatternIndependentAction.StartRequested -> if (!current.technicalRetryPending && !current.recording && current.example != null) {
                val started = repository.startCapture()
                mutableUiState.update { it.copy(recording = started, failed = !started, decision = null, feedbackZh = null, receipt = null,
                    technicalRetryPending = repository.hasPendingEvaluationRetry(), technicalRetryBlocked = repository.technicalRetryBlocked()) }
            }
            PatternIndependentAction.FinishedRequested -> evaluate()
            PatternIndependentAction.RetryRequested -> when {
                current.technicalRetryBlocked -> Unit
                current.technicalRetryPending -> evaluate(retry = true)
                current.example == null -> generate()
                else -> mutableUiState.update {
                    it.copy(decision = null, feedbackZh = null, receipt = null, failed = false)
                }
            }
            PatternIndependentAction.ContinueRequested -> {
                val state = mutableUiState.value
                if (state.recording || state.evaluating || state.loading) return
                val completion = when {
                    state.notNaturallyVerifiable -> PatternIndependentEffect.NotNaturallyVerifiable
                    state.decision == PatternIndependentDecision.PASS && state.receipt != null -> PatternIndependentEffect.Completed(true, state.receipt)
                    state.decision == PatternIndependentDecision.FAIL && state.receipt != null -> PatternIndependentEffect.Completed(false, state.receipt)
                    else -> null
                }
                if (completion != null) {
                    completionSent = true
                    send(completion)
                }
            }
        }
    }

    private fun generate() {
        val state = mutableUiState.value
        if (state.loading || state.evaluating || state.recording || completionSent) return
        mutableUiState.update { it.copy(loading = true, failed = false, notNaturallyVerifiable = false) }
        viewModelScope.launch {
            when (val result = repository.generate(state.pattern)) {
                is PatternIndependentGenerationResult.Ready -> mutableUiState.update {
                    it.copy(loading = false, example = result.practiceSet.examples.first())
                }
                PatternIndependentGenerationResult.NotNaturallyVerifiable -> mutableUiState.update {
                    it.copy(loading = false, notNaturallyVerifiable = true)
                }
                PatternIndependentGenerationResult.Failed -> mutableUiState.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private fun evaluate(retry: Boolean = false) {
        val state = mutableUiState.value
        val example = state.example ?: return
        if (state.evaluating || (!state.recording && !(retry && state.technicalRetryPending))) return
        mutableUiState.update { it.copy(recording = false, evaluating = true, failed = false) }
        viewModelScope.launch {
            when (val result = repository.evaluate(state.pattern.patternId, example.exampleId)) {
                is PatternIndependentEvaluationResult.Ready -> mutableUiState.update {
                    val evaluation = result.evaluation
                    val receipt = evaluation.receipt
                    val valid = evaluation.decision == PatternIndependentDecision.INCONCLUSIVE ||
                        (receipt != null && receipt.hasValidIndependentJudgment() && receipt.decision == evaluation.decision &&
                            receipt.identity.targetId == state.pattern.patternId && receipt.identity.sourceSentenceId == state.pattern.sourceSentenceId &&
                            receipt.exampleId == example.exampleId)
                    it.copy(evaluating = false, failed = !valid, technicalRetryPending = false, technicalRetryBlocked = false,
                        decision = evaluation.decision.takeIf { valid },
                        feedbackZh = evaluation.feedbackZh.takeIf { valid },
                        receipt = receipt.takeIf { valid && evaluation.decision != PatternIndependentDecision.INCONCLUSIVE })
                }
                PatternIndependentEvaluationResult.Failed -> mutableUiState.update {
                    it.copy(evaluating = false, failed = true,
                        technicalRetryPending = repository.hasPendingEvaluationRetry(),
                        technicalRetryBlocked = repository.technicalRetryBlocked())
                }
            }
        }
    }

    private fun send(effect: PatternIndependentEffect) = viewModelScope.launch { effectChannel.send(effect) }

    override fun onCleared() {
        repository.cancelCapture()
        super.onCleared()
    }
}
