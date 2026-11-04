package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter

data class ReadWriteCorrectionUiState(
    val chapter: FixedLearningChapter,
    val currentFocusSentenceId: String,
    val lockedHintItems: List<String>,
    val audioLooping: Boolean = true,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val audioFailed: Boolean = false,
)

sealed interface ReadWriteCorrectionAction {
    data object BackRequested : ReadWriteCorrectionAction
    data object AudioToggled : ReadWriteCorrectionAction
    data object AudioPlaybackFailed : ReadWriteCorrectionAction
    data object WrittenFinished : ReadWriteCorrectionAction
}

sealed interface ReadWriteCorrectionEffect {
    data object ReturnToDictation : ReadWriteCorrectionEffect
    data object OpenFullSentenceSpeaking : ReadWriteCorrectionEffect
}
