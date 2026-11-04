package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample

enum class PatternSpeakingStage {
    LOADING,
    READING,
    RESPONDING,
    UNAVAILABLE,
}

data class PatternSpeakingUiState(
    val chapter: FixedLearningChapter,
    val currentFocusSentenceId: String,
    val lockedPatterns: List<LockedSpeakingPattern>,
    val patternIndex: Int = 0,
    val stage: PatternSpeakingStage = PatternSpeakingStage.LOADING,
    val examples: List<PatternPracticeExample> = emptyList(),
    val completedReadingExampleIds: Set<String> = emptySet(),
    val readingExampleId: String? = null,
    val responseIndex: Int = 0,
    val recording: Boolean = false,
    val submitting: Boolean = false,
    val transcript: String = "",
    val feedbackZh: String? = null,
    val repeatRecommended: Boolean = false,
    val currentResponseCompleted: Boolean = false,
    val audioMuted: Boolean = false,
    val failureMessageVisible: Boolean = false,
    val technicalRetryPending: Boolean = false,
    val technicalRetryBlocked: Boolean = false,
    val feedbackExampleId: String? = null,
    val progressFailed: Boolean = false,
    val completed: Boolean = false,
    /** Processed training targets, including the existing explicitly unavailable path; not mastery. */
    val processedPatternIds: Set<String> = emptySet(),
) {
    val currentPattern: LockedSpeakingPattern? get() = lockedPatterns.getOrNull(patternIndex)
    val currentResponseExample: PatternPracticeExample? get() = examples.getOrNull(responseIndex)
}

sealed interface PatternSpeakingAction {
    data object BackRequested : PatternSpeakingAction
    data object AudioToggled : PatternSpeakingAction
    data object PronounceRequested : PatternSpeakingAction
    data class StartReadingRequested(val exampleId: String) : PatternSpeakingAction
    data class ReadingFinished(val exampleId: String) : PatternSpeakingAction
    data object StartResponsesRequested : PatternSpeakingAction
    data object StartResponseRequested : PatternSpeakingAction
    data object FinishResponseRequested : PatternSpeakingAction
    data class TranscriptChanged(val value: String) : PatternSpeakingAction
    data object RetryResponseRequested : PatternSpeakingAction
    data object NextSceneRequested : PatternSpeakingAction
    data object SkipSceneRequested : PatternSpeakingAction
    data object RetryLoadingRequested : PatternSpeakingAction
    data object RetryProgressRequested : PatternSpeakingAction
    data object ContinueWithoutPatternRequested : PatternSpeakingAction
}

sealed interface PatternSpeakingEffect {
    data object ReturnToFullSentenceSpeaking : PatternSpeakingEffect
    data object OpenCompleteWriting : PatternSpeakingEffect
}
