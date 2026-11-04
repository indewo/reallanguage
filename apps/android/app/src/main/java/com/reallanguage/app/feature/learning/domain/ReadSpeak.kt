package com.reallanguage.app.feature.learning.domain

enum class ReadSpeakCompletionSignal {
    TOUCH_REACHED_END,
    RECOGNITION_COMPLETE,
    MANUAL_CONFIRMATION,
}

sealed interface ReadSpeakFeedbackResult {
    data class Ready(
        val feedbackZh: String,
        val contentComplete: Boolean = true,
    ) : ReadSpeakFeedbackResult
    /** User-confirmed training only, with no successful audio evaluation or mastery result. */
    data object CompletedWithoutAudio : ReadSpeakFeedbackResult
    data object Failed : ReadSpeakFeedbackResult
}

/** Local recovery never starts a microphone or sends a request. */
sealed interface ReadSpeakFeedbackRecovery {
    data object None : ReadSpeakFeedbackRecovery
    data object Unavailable : ReadSpeakFeedbackRecovery
    data object Blocked : ReadSpeakFeedbackRecovery
    data class Pending(val signal: ReadSpeakCompletionSignal) : ReadSpeakFeedbackRecovery
    data class Completed(val signal: ReadSpeakCompletionSignal, val result: ReadSpeakFeedbackResult) : ReadSpeakFeedbackRecovery
}

fun interface ReadSpeakRepository {
    val requiresFeedbackRecovery: Boolean get() = false
    suspend fun restoreFeedback(): ReadSpeakFeedbackRecovery = ReadSpeakFeedbackRecovery.None
    suspend fun retryFeedback(sentenceId: String, completionSignal: ReadSpeakCompletionSignal): ReadSpeakFeedbackResult =
        feedback(sentenceId, completionSignal)
    fun cancelCapture() = Unit
    /** A retained original request or delivered feedback can be retried without recording again. */
    fun hasPendingFeedbackRetry(): Boolean = false
    /** A known terminal request requires a separate, explicit new-recording action. */
    fun isFeedbackRetryBlocked(): Boolean = false

    suspend fun feedback(
        sentenceId: String,
        completionSignal: ReadSpeakCompletionSignal,
    ): ReadSpeakFeedbackResult
}
