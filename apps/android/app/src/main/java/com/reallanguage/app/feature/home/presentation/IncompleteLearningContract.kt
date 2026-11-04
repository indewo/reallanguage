package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.feature.home.domain.IncompleteLearningEntry

data class IncompleteLearningUiState(
    val entries: List<IncompleteLearningEntry> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
)

sealed interface IncompleteLearningAction {
    data class ResumeRequested(val learningId: String) : IncompleteLearningAction
    data object RetryRequested : IncompleteLearningAction
    data object ReturnHomeRequested : IncompleteLearningAction
}

sealed interface IncompleteLearningEffect {
    data class Resume(
        val learningId: String,
        val savedStepId: String,
        val checkpointStepId: String? = null,
    ) : IncompleteLearningEffect
    data object OpenHome : IncompleteLearningEffect
}
