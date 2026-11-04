package com.reallanguage.app.feature.learning.domain

data class SceneDialogueLine(
    val speaker: String,
    val textEn: String,
)

data class FixedSceneResponseTask(
    val taskId: String,
    val sentenceId: String,
    val backgroundEn: String?,
    val contextLines: List<SceneDialogueLine>,
    val targetSentenceEn: String,
)

sealed interface SceneTrainingFeedbackResult {
    data class Ready(val feedbackZh: String) : SceneTrainingFeedbackResult
    data object Inconclusive : SceneTrainingFeedbackResult
    data object Failed : SceneTrainingFeedbackResult
}

enum class SceneEvaluationDecision {
    PASS,
    FAIL,
    INCONCLUSIVE,
}

data class SceneEvaluationCandidate(
    val decision: SceneEvaluationDecision,
    val correctTurn: Boolean,
    val targetSentenceComplete: Boolean,
    val pronunciationIntelligible: Boolean,
    val feedbackZh: String,
)

sealed interface SceneEvaluationResult {
    data class Ready(val candidate: SceneEvaluationCandidate) : SceneEvaluationResult
    data object Failed : SceneEvaluationResult
}

sealed interface SceneTaskGenerationResult {
    data class Ready(val tasks: List<FixedSceneResponseTask>) : SceneTaskGenerationResult
    data object Failed : SceneTaskGenerationResult
}

sealed interface SceneResponseSaveResult {
    data object Saved : SceneResponseSaveResult
    data object Failed : SceneResponseSaveResult
}

/** Ordinary learning only: recovered evidence is not an automatic submission. */
sealed interface SceneResponseRecovery {
    data object None : SceneResponseRecovery
    data object Unavailable : SceneResponseRecovery
    data object TranscriptionPending : SceneResponseRecovery
    data class TranscriptReady(val value: SpeakingTranscription, val recordingId: String) : SceneResponseRecovery
    data class FeedbackPending(val confirmedTranscript: String) : SceneResponseRecovery
    data class TrainingReady(val result: SceneTrainingFeedbackResult, val confirmedTranscript: String) : SceneResponseRecovery
    data class EvaluationReady(val candidate: SceneEvaluationCandidate, val confirmedTranscript: String) : SceneResponseRecovery
    data object Blocked : SceneResponseRecovery
}

interface SceneResponseRepository {
    val requiresAttemptRecovery: Boolean get() = false
    suspend fun restoreAttempt(chapter: FixedLearningChapter, task: FixedSceneResponseTask, roundIndex: Int): SceneResponseRecovery = SceneResponseRecovery.None
    suspend fun prepareCapture(chapter: FixedLearningChapter, task: FixedSceneResponseTask, roundIndex: Int): Boolean = true
    fun currentRecordingId(): String? = null
    suspend fun generateTasks(chapter: FixedLearningChapter): SceneTaskGenerationResult
    /** Reads previously fixed tasks without permitting a new AI generation. */
    suspend fun restoreTasks(chapter: FixedLearningChapter): SceneTaskGenerationResult = SceneTaskGenerationResult.Failed
    fun startCapture(): Boolean
    fun cancelCapture()
    fun hasPendingTranscriptionRetry(): Boolean = false
    /** Includes technical retries for training feedback and the final-round evaluation. */
    fun hasPendingEvaluationRetry(): Boolean = false
    fun technicalRetryBlocked(): Boolean = false
    suspend fun transcribeResponse(taskId: String): SpeakingTranscriptionResult

    suspend fun trainingFeedback(
        taskId: String,
        roundIndex: Int,
        confirmedTranscript: String,
    ): SceneTrainingFeedbackResult

    suspend fun evaluateFinalRound(
        taskId: String,
        confirmedTranscript: String,
    ): SceneEvaluationResult

    suspend fun saveFinalDecision(
        chapterId: String,
        sentenceId: String,
        decision: SceneEvaluationDecision,
    ): SceneResponseSaveResult
}
