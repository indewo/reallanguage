package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.ExpressionExplanation
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.SelectableExpression

data class ExplanationReadingUiState(
    val chapter: FixedLearningChapter,
    val currentFocusSentenceId: String,
    val baseExplanationZh: String,
    val currentIncrementalHint: String? = null,
    val expressions: List<SelectableExpression>,
    val selectedExpression: ExpressionExplanation? = null,
    val activeExpressionId: String? = null,
    val completedReadCount: Int = 0,
    val pendingHintRound: Int? = null,
    val explaining: Boolean = true,
    val loading: Boolean = false,
    val loadingFailed: Boolean = false,
    val audioPlaying: Boolean = true,
    val progressFailed: Boolean = false,
    val audioFailed: Boolean = false,
    val pronunciationText: String? = null,
    val autoExplanationSuppressed: Boolean = false,
    val voiceEnabled: Boolean = true,
    val speechRate: Float = 1f,
) {
    val threeReadsCompleted: Boolean get() = completedReadCount >= 3
}

sealed interface ExplanationReadingAction {
    data object RetryProgressRequested : ExplanationReadingAction
    data object BackRequested : ExplanationReadingAction
    data object AudioToggled : ExplanationReadingAction
    data object AudioPlaybackFailed : ExplanationReadingAction
    data object ExplanationFinished : ExplanationReadingAction
    data object ReadFinished : ExplanationReadingAction
    data object SkipExplanationRequested : ExplanationReadingAction
    data object SkipSentenceRequested : ExplanationReadingAction
    data object ContinueReadingRequested : ExplanationReadingAction
    data object UnderstoodRequested : ExplanationReadingAction
    data class ExpressionRequested(val expressionId: String) : ExplanationReadingAction
}

sealed interface ExplanationReadingEffect {
    data object ReturnToChapterEntry : ExplanationReadingEffect
    data object SentenceSkipped : ExplanationReadingEffect
    data object OpenReadAndSpeak : ExplanationReadingEffect
}
