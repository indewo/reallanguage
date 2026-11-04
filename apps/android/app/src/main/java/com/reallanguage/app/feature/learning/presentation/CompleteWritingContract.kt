package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FinalWritingBatchResult
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.LockedWritingTarget

enum class CompleteWritingPhase {
    AUDIBLE_SINGLE,
    SILENT_SINGLE,
    FINAL_BATCH,
}

data class CompleteWritingUiState(
    val chapter: FixedLearningChapter,
    val lockedTargets: List<LockedWritingTarget>,
    val phase: CompleteWritingPhase = CompleteWritingPhase.AUDIBLE_SINGLE,
    val sentenceIndex: Int = 0,
    val input: String = "",
    val finalInputs: Map<String, String> = emptyMap(),
    val helpUsed: Boolean = false,
    val audioPlaying: Boolean = true,
    val emptySubmission: Boolean = false,
    val currentAttemptCorrect: Boolean? = null,
    val finalResult: FinalWritingBatchResult? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val evaluationUnavailable: Boolean = false,
    val audioSentenceId: String? = null,
    val audioRequestId: Long = 0,
    val audioFailed: Boolean = false,
) {
    val focusSentences get() = chapter.sentences.filter { it.isFocus }
    val currentSentence get() = focusSentences.getOrNull(sentenceIndex)
    val currentFocusSentenceId get() = currentSentence?.sentenceId.orEmpty()
    val attemptFinished get() = currentAttemptCorrect != null
}

sealed interface CompleteWritingAction {
    data object BackRequested : CompleteWritingAction
    data object HelpRequested : CompleteWritingAction
    data object AudioToggled : CompleteWritingAction
    data class AudioPlaybackFinished(val requestId: Long, val successful: Boolean) : CompleteWritingAction
    data class FinalAudioRequested(val sentenceId: String) : CompleteWritingAction
    data class InputChanged(val value: String) : CompleteWritingAction
    data class FinalInputChanged(
        val sentenceId: String,
        val value: String,
    ) : CompleteWritingAction
    data object SubmitRequested : CompleteWritingAction
    data object RetryCurrentRequested : CompleteWritingAction
    data object NextRequested : CompleteWritingAction
    data object ContinueRequested : CompleteWritingAction
}

sealed interface CompleteWritingEffect {
    data object ReturnToPatternSpeaking : CompleteWritingEffect
    data object OpenSceneResponse : CompleteWritingEffect
}
