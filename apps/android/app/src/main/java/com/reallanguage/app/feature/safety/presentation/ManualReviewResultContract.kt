package com.reallanguage.app.feature.safety.presentation

typealias ManualReviewOutcome = com.reallanguage.app.feature.safety.domain.ManualReviewOutcome

data class ManualReviewResultUiState(
    val outcome: ManualReviewOutcome? = null,
    val summaryZh: String = "",
    val reasonZh: String? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val unavailable: Boolean = false,
)

sealed interface ManualReviewResultAction {
    data object OpenFullSentenceSplitRequested : ManualReviewResultAction
    data object ReturnToSafetyHandlingRequested : ManualReviewResultAction
    data object RetryRequested : ManualReviewResultAction
    data object ReturnMessagesRequested : ManualReviewResultAction
}

sealed interface ManualReviewResultEffect {
    data object OpenMessages : ManualReviewResultEffect
    data class OpenSemanticSplit(val source: com.reallanguage.app.feature.safety.domain.ReviewedSafetySource) : ManualReviewResultEffect
    data class OpenSafetyHandling(val safetyCaseId: String, val text: String) : ManualReviewResultEffect
    data class OpenLoginPreservingTarget(val reviewRequestId: String) : ManualReviewResultEffect
}
