package com.reallanguage.app.feature.learning.domain

enum class SpeakingTrainingMode {
    BILINGUAL_FOLLOW,
    ENGLISH_READING,
}

enum class ReadingFeedbackAction {
    CONTINUE,
    REPEAT_FOR_CLARITY,
}

enum class ReadingFeedbackAspect {
    WORD_OR_PHRASE,
    RHYTHM,
    COMPLETENESS,
}

enum class ReadingFeedbackEvidence {
    AUDIO,
    TRANSCRIPT,
    GENERAL,
}

data class ReadingFeedbackDetail(
    val aspect: ReadingFeedbackAspect,
    val targetText: String?,
    val observationZh: String,
    val suggestionZh: String,
    val evidence: ReadingFeedbackEvidence,
)

data class ReadingFeedback(
    val feedbackZh: String,
    val action: ReadingFeedbackAction,
    val feedbackConfidence: Double? = null,
    val details: List<ReadingFeedbackDetail> = emptyList(),
)

sealed interface ReadingFeedbackResult {
    data class Ready(val feedback: ReadingFeedback) : ReadingFeedbackResult
    data object Failed : ReadingFeedbackResult
}

enum class SpeakingEvaluationDecision {
    PASS,
    FAIL,
    INCONCLUSIVE,
}

data class SpeakingEvaluationCandidate(
    val decision: SpeakingEvaluationDecision,
    val completenessScore: Int,
    val coreMeaningRecognizable: Boolean,
    val pronunciationIntelligible: Boolean,
    val feedbackZh: String,
)

sealed interface SpeakingEvaluationResult {
    data class Ready(
        val candidate: SpeakingEvaluationCandidate,
    ) : SpeakingEvaluationResult

    data object Failed : SpeakingEvaluationResult
}

sealed interface SpeakingDecisionSaveResult {
    data object Saved : SpeakingDecisionSaveResult
    data object Failed : SpeakingDecisionSaveResult
}

data class SpeakingTranscription(
    val transcript: String,
    val lowConfidenceSegments: List<String>,
    val confidence: Double?,
)

sealed interface SpeakingTranscriptionResult {
    data class Ready(val value: SpeakingTranscription) : SpeakingTranscriptionResult
    data object Failed : SpeakingTranscriptionResult
}

/** Recovery is a local read, never permission to start a microphone or repeat a paid request. */
sealed interface FullSpeakingRecovery {
    data object None : FullSpeakingRecovery
    data object Unavailable : FullSpeakingRecovery
    data object Blocked : FullSpeakingRecovery
    data object TrainingPending : FullSpeakingRecovery
    data object TranscriptionPending : FullSpeakingRecovery
    data class TrainingReady(val feedback: ReadingFeedback) : FullSpeakingRecovery
    data class TranscriptReady(val value: SpeakingTranscription, val recordingId: String? = null) : FullSpeakingRecovery
    data class EvaluationPending(val confirmedTranscript: String, val answerAudioPlayed: Boolean) : FullSpeakingRecovery
    data class EvaluationReady(val candidate: SpeakingEvaluationCandidate, val confirmedTranscript: String,
        val answerAudioPlayed: Boolean) : FullSpeakingRecovery
}

interface FullSentenceSpeakingRepository {
    val requiresAttemptRecovery: Boolean get() = false
    /**
     * Durable production repositories can safely replay the exact saved command until its
     * result arrives. In-memory/test repositories stay opt-in so recovery never invents a new
     * paid request.
     */
    val automaticallyResumePendingRequests: Boolean get() = false
    suspend fun restoreAttempt(sentenceId: String, attemptIndex: Int): FullSpeakingRecovery = FullSpeakingRecovery.None
    suspend fun prepareCapture(sentenceId: String, attemptIndex: Int): Boolean = true
    fun currentRecordingId(): String? = null
    fun startCapture(): Boolean
    fun cancelCapture()
    fun hasPendingTranscriptionRetry(): Boolean = false
    fun hasPendingTrainingRetry(): Boolean = false
    fun hasPendingEvaluationRetry(): Boolean = false
    fun technicalRetryBlocked(): Boolean = false

    suspend fun trainingFeedback(
        sentenceId: String,
        mode: SpeakingTrainingMode,
    ): ReadingFeedbackResult

    suspend fun transcribeIndependentRetelling(
        sentenceId: String,
    ): SpeakingTranscriptionResult

    suspend fun evaluateIndependentRetelling(
        sentenceId: String,
        confirmedTranscript: String,
        answerAudioPlayed: Boolean,
    ): SpeakingEvaluationResult

    suspend fun saveIndependentDecision(
        sentenceId: String,
        decision: SpeakingEvaluationDecision,
    ): SpeakingDecisionSaveResult
}
