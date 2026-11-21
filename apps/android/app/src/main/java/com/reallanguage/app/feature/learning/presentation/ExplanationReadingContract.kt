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
    val initialSubject: com.reallanguage.app.feature.learning.domain.InitialExplanationSubject? = null,
) {
    val threeReadsCompleted: Boolean get() = completedReadCount >= 3
}

internal fun ExplanationReadingUiState.cardSubject(): com.reallanguage.app.feature.learning.domain.InitialExplanationSubject? {
    val activeId = activeExpressionId ?: selectedExpression?.expressionId
    if (activeId != null) return expressions.firstOrNull { it.expressionId == activeId }?.let { expression ->
        val selected = selectedExpression?.takeIf { it.expressionId == activeId }
        com.reallanguage.app.feature.learning.domain.InitialExplanationSubject(expression.display,
            selected?.contextualMeaningZh ?: selected?.explanationZh ?: expression.lockedMeaningZh.orEmpty())
    }
    return initialSubject ?: expressions.firstOrNull { !it.lockedMeaningZh.isNullOrBlank() }?.let {
        com.reallanguage.app.feature.learning.domain.InitialExplanationSubject(it.display, requireNotNull(it.lockedMeaningZh))
    }
}

sealed interface ExplanationReadingAction {
    data object RetryProgressRequested : ExplanationReadingAction
    data object BackRequested : ExplanationReadingAction
    data object AudioToggled : ExplanationReadingAction
    data object ExampleAudioRequested : ExplanationReadingAction
    data object RegenerateAudioRequested : ExplanationReadingAction
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
