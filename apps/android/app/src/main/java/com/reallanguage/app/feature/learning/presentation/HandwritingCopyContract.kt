package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter

data class HandwritingCopyUiState(
    val chapter: FixedLearningChapter,
    val currentFocusSentenceId: String,
    val confirmedCopyCount: Int = 0,
    val copyInProgress: Boolean = true,
    val canvasRevision: Int = 0,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
) {
    val minimumCompleted: Boolean get() = confirmedCopyCount >= 2
}

sealed interface HandwritingCopyAction {
    data object BackRequested : HandwritingCopyAction
    data object WrittenFinished : HandwritingCopyAction
    data object CopyAgainRequested : HandwritingCopyAction
    data object ContinueRequested : HandwritingCopyAction
}

sealed interface HandwritingCopyEffect {
    data object ReturnToReadSpeak : HandwritingCopyEffect
    data object OpenAudioDictation : HandwritingCopyEffect
}
