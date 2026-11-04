package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter

internal const val READ_SPEAK_NO_AUDIO_COMPLETION_COPY =
    "本句已由你确认读完。本次无法提供发音反馈。"

internal fun String.isReadSpeakNoAudioCompletionCopy(): Boolean =
    startsWith("本句已由你确认读完") && contains("无法提供发音反馈")

data class ReadSpeakUiState(
    val chapter: FixedLearningChapter,
    val currentFocusSentenceId: String,
    val listening: Boolean = false,
    val submitting: Boolean = false,
    val highlightedWordCount: Int = 0,
    val feedbackZh: String? = null,
    val captureFailed: Boolean = false,
    val completed: Boolean = false,
    val audioPlaying: Boolean = false,
    val progressFailed: Boolean = false,
    val pendingFeedbackRetry: Boolean = false,
    val feedbackRetryBlocked: Boolean = false,
)

sealed interface ReadSpeakAction {
    data object BackRequested : ReadSpeakAction
    data object PlayToggled : ReadSpeakAction
    data object StartRequested : ReadSpeakAction
    data class WordReached(val wordIndex: Int) : ReadSpeakAction
    data object RecognitionCompleted : ReadSpeakAction
    data object PermissionOrRecognitionFailed : ReadSpeakAction
    data object ManualFinished : ReadSpeakAction
    data object RetryRequested : ReadSpeakAction
    data object ContinueRequested : ReadSpeakAction
}

sealed interface ReadSpeakEffect {
    data object ReturnToExplanation : ReadSpeakEffect
    data object OpenHandwriting : ReadSpeakEffect
}
