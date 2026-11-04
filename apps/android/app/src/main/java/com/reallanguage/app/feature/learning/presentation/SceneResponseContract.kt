package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision

data class SceneResponseUiState(
    val chapter: FixedLearningChapter,
    val tasks: List<FixedSceneResponseTask>,
    val taskIndex: Int = 0,
    val roundIndex: Int = 0,
    val recording: Boolean = false,
    val submitting: Boolean = false,
    val transcript: String = "",
    val feedbackZh: String? = null,
    val trainingRoundCompleted: Boolean = false,
    val finalDecision: SceneEvaluationDecision? = null,
    val captureFailed: Boolean = false,
    val technicalRetryPending: Boolean = false,
    val technicalRetryBlocked: Boolean = false,
    val audioMuted: Boolean = false,
    val loading: Boolean = false,
    val transcribing: Boolean = false,
    val transcriptReady: Boolean = false,
    val saveFailed: Boolean = false,
    val progressFailed: Boolean = false,
    val completed: Boolean = false,
    val needsNewRecording: Boolean = false,
) {
    val currentTask get() = tasks.getOrNull(taskIndex)
    val currentFocusSentenceId get() = currentTask?.sentenceId.orEmpty()
    val isFinalRound get() = roundIndex == 2
}

sealed interface SceneResponseAction {
    data object BackRequested : SceneResponseAction
    data object AudioToggled : SceneResponseAction
    data object StartRequested : SceneResponseAction
    data object FinishedRequested : SceneResponseAction
    data object ConfirmTranscriptRequested : SceneResponseAction
    data class TranscriptChanged(val value: String) : SceneResponseAction
    data object ContinueRoundRequested : SceneResponseAction
    data object RetryRequested : SceneResponseAction
    data object StopPractisingRequested : SceneResponseAction
}

sealed interface SceneResponseEffect {
    data object ReturnToCompleteWriting : SceneResponseEffect
    data object OpenChapterResult : SceneResponseEffect
    data object ArchiveWrongAndContinue : SceneResponseEffect
}
