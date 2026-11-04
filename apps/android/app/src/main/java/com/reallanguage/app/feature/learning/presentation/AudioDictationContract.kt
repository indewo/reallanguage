package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter

data class AudioDictationUiState(
    val chapter: FixedLearningChapter,
    val currentFocusSentenceId: String,
    val input: String = "",
    val audioLooping: Boolean = true,
    val hintExpanded: Boolean = false,
    val lockedHintItems: List<String>,
    val emptySubmission: Boolean = false,
    val correct: Boolean = false,
    val incorrect: Boolean = false,
    val submitting: Boolean = false,
    val saveFailed: Boolean = false,
    val audioFailed: Boolean = false,
)

sealed interface AudioDictationAction {
    data object BackRequested : AudioDictationAction
    data object AudioToggled : AudioDictationAction
    data object AudioPlaybackFailed : AudioDictationAction
    data object HintToggled : AudioDictationAction
    data class InputChanged(val value: String) : AudioDictationAction
    data object SubmitRequested : AudioDictationAction
    data object ContinueRequested : AudioDictationAction
}

sealed interface AudioDictationEffect {
    data object ReturnToHandwriting : AudioDictationEffect
    data object OpenFullSentenceSpeaking : AudioDictationEffect
    data class OpenReadWriteCorrection(val input: String) : AudioDictationEffect
}
