package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackDetail
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SpeakingTrainingMode

data class FullSentenceSpeakingUiState(
    val chapter: FixedLearningChapter,
    val currentFocusSentenceId: String,
    val attemptIndex: Int = 0,
    val recording: Boolean = false,
    val submitting: Boolean = false,
    val transcript: String = "",
    val transcriptReadyForConfirmation: Boolean = false,
    val lowConfidenceSegments: List<String> = emptyList(),
    val answerAudioPlayed: Boolean = false,
    val feedbackZh: String? = null,
    val feedbackConfidence: Double? = null,
    val feedbackDetails: List<ReadingFeedbackDetail> = emptyList(),
    val trainingCanContinue: Boolean = false,
    val finalDecision: SpeakingEvaluationDecision? = null,
    val captureFailed: Boolean = false,
    val progressFailed: Boolean = false,
    val needsNewRecording: Boolean = false,
    val evaluationReceived: Boolean = false,
    val decisionSaveFailed: Boolean = false,
    val technicalRetryPending: Boolean = false,
    val technicalRetryBlocked: Boolean = false,
    val answerPlaybackFailed: Boolean = false,
) {
    val isIndependentRetelling: Boolean get() = attemptIndex == 4
    val trainingMode: SpeakingTrainingMode?
        get() = when (attemptIndex) {
            0, 1 -> SpeakingTrainingMode.BILINGUAL_FOLLOW
            2, 3 -> SpeakingTrainingMode.ENGLISH_READING
            else -> null
        }
    val showChinese: Boolean get() = attemptIndex < 2 || isIndependentRetelling
    val showEnglish: Boolean get() = !isIndependentRetelling
}

sealed interface FullSentenceSpeakingAction {
    data object BackRequested : FullSentenceSpeakingAction
    data object AnswerAudioRequested : FullSentenceSpeakingAction
    data object StartRequested : FullSentenceSpeakingAction
    data object FinishedRequested : FullSentenceSpeakingAction
    data class TranscriptChanged(val value: String) : FullSentenceSpeakingAction
    data object ConfirmTranscriptRequested : FullSentenceSpeakingAction
    data object ContinueTrainingRequested : FullSentenceSpeakingAction
    data object RetryRequested : FullSentenceSpeakingAction
    data object StopPractisingRequested : FullSentenceSpeakingAction
}

sealed interface FullSentenceSpeakingEffect {
    data object ReturnToAudioDictation : FullSentenceSpeakingEffect
    data object OpenPatternSpeaking : FullSentenceSpeakingEffect
    data object ArchiveWrongAndContinue : FullSentenceSpeakingEffect
}
