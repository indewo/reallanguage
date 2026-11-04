package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackRecovery
import com.reallanguage.app.feature.learning.domain.ReadSpeakRepository
import com.reallanguage.app.feature.learning.domain.LearningProgressStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReadSpeakViewModel(
    chapter: FixedLearningChapter,
    currentFocusSentenceId: String,
    private val repository: ReadSpeakRepository,
    private val progressStore: LearningProgressStore? = null,
) : ViewModel() {
    private val sentence = chapter.sentences.first { it.sentenceId == currentFocusSentenceId }
    private val wordCount = sentence.textEn.split(Regex("\\s+")).size
    private val mutableUiState = MutableStateFlow(
        ReadSpeakUiState(chapter, currentFocusSentenceId),
    )
    val uiState: StateFlow<ReadSpeakUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ReadSpeakEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var progressReady = progressStore == null && !repository.requiresFeedbackRecovery
    private val progressMutex = Mutex()
    private var lastSavedProgress: Map<String, String>? = null
    private var failedFeedbackSignal: ReadSpeakCompletionSignal? = null

    init { if (!progressReady) restoreProgress() }

    fun interruptUnsubmittedCapture() {
        if (!mutableUiState.value.listening || mutableUiState.value.submitting) return
        repository.cancelCapture()
        mutableUiState.update { it.copy(listening = false, captureFailed = true) }
    }

    fun onAction(action: ReadSpeakAction) {
        if (mutableUiState.value.submitting) return
        if (!progressReady) {
            if (action == ReadSpeakAction.BackRequested) viewModelScope.launch { effectChannel.send(ReadSpeakEffect.ReturnToExplanation) }
            else if (action == ReadSpeakAction.StartRequested || action == ReadSpeakAction.RetryRequested) restoreProgress()
            return
        }
        when (action) {
            ReadSpeakAction.BackRequested -> send(ReadSpeakEffect.ReturnToExplanation)
            ReadSpeakAction.PlayToggled -> mutableUiState.update {
                it.copy(audioPlaying = !it.audioPlaying)
            }
            ReadSpeakAction.StartRequested -> start()
            ReadSpeakAction.RetryRequested -> {
                if (mutableUiState.value.feedbackRetryBlocked) return
                val signal = failedFeedbackSignal
                if (signal != null && repository.hasPendingFeedbackRetry()) complete(signal, retry = true)
                else start()
            }
            is ReadSpeakAction.WordReached -> wordReached(action.wordIndex)
            ReadSpeakAction.RecognitionCompleted -> complete(
                ReadSpeakCompletionSignal.RECOGNITION_COMPLETE,
            )
            ReadSpeakAction.PermissionOrRecognitionFailed -> mutableUiState.update {
                if (it.completed) it else it.copy(listening = false, captureFailed = true)
            }
            ReadSpeakAction.ManualFinished -> complete(
                ReadSpeakCompletionSignal.MANUAL_CONFIRMATION,
            )
            ReadSpeakAction.ContinueRequested -> {
                if (mutableUiState.value.completed) send(ReadSpeakEffect.OpenHandwriting)
            }
        }
    }

    private fun start() {
        if (mutableUiState.value.completed || mutableUiState.value.submitting) return
        failedFeedbackSignal = null
        mutableUiState.update {
            it.copy(
                listening = true,
                captureFailed = false,
                highlightedWordCount = 0,
                feedbackZh = null,
                pendingFeedbackRetry = false,
                feedbackRetryBlocked = false,
            )
        }
    }

    private fun wordReached(wordIndex: Int) {
        val current = mutableUiState.value
        if (!current.listening || current.submitting || current.completed) return
        val reachedCount = (wordIndex + 1).coerceIn(0, wordCount)
        mutableUiState.update {
            it.copy(highlightedWordCount = maxOf(it.highlightedWordCount, reachedCount))
        }
    }

    private fun complete(signal: ReadSpeakCompletionSignal, retry: Boolean = false) {
        val current = mutableUiState.value
        if (current.submitting || current.completed ||
            (!retry && !current.listening && !(signal == ReadSpeakCompletionSignal.MANUAL_CONFIRMATION && current.captureFailed))
        ) return
        mutableUiState.update { it.copy(listening = false, submitting = true) }
        viewModelScope.launch {
            val result = if (retry) repository.retryFeedback(sentence.sentenceId, signal)
                else repository.feedback(sentence.sentenceId, signal)
            when (result) {
                is ReadSpeakFeedbackResult.Ready -> {
                    failedFeedbackSignal = null
                    mutableUiState.update { it.copy(pendingFeedbackRetry = false, feedbackRetryBlocked = false) }
                    if (
                        signal == ReadSpeakCompletionSignal.RECOGNITION_COMPLETE &&
                        !result.contentComplete
                    ) {
                        mutableUiState.update {
                            it.copy(
                                submitting = false,
                                captureFailed = true,
                                feedbackZh = result.feedbackZh,
                            )
                        }
                        return@launch
                    }
                    mutableUiState.update {
                        it.copy(
                            submitting = false,
                            completed = true,
                            captureFailed = false,
                            feedbackZh = result.feedbackZh,
                        )
                    }
                }
                ReadSpeakFeedbackResult.CompletedWithoutAudio -> mutableUiState.update {
                    failedFeedbackSignal = null
                    if (signal != ReadSpeakCompletionSignal.MANUAL_CONFIRMATION) it.copy(submitting = false, captureFailed = true)
                    else it.copy(submitting = false, completed = true, captureFailed = false, pendingFeedbackRetry = false, feedbackRetryBlocked = false,
                        feedbackZh = null)
                }
                ReadSpeakFeedbackResult.Failed -> mutableUiState.update {
                    failedFeedbackSignal = signal
                    it.copy(submitting = false, captureFailed = true, pendingFeedbackRetry = repository.hasPendingFeedbackRetry(),
                        feedbackRetryBlocked = repository.isFeedbackRetryBlocked())
                }
            }
            saveProgress()
        }
    }

    private fun send(effect: ReadSpeakEffect) {
        viewModelScope.launch { if (saveProgress()) effectChannel.send(effect) }
    }

    private fun encodeProgress(state: ReadSpeakUiState) = mapOf(
        "version" to "1", "completed" to state.completed.toString(),
        "highlightedWordCount" to state.highlightedWordCount.toString(),
        "feedbackZh" to state.feedbackZh
            ?.takeUnless(String::isReadSpeakNoAudioCompletionCopy)
            .orEmpty(),
        "interrupted" to (state.listening || state.captureFailed).toString(),
    )

    private fun restoreProgress() {
        if (mutableUiState.value.submitting) return
        mutableUiState.update { it.copy(submitting = true, progressFailed = false) }
        viewModelScope.launch {
            try {
                val saved = progressStore?.read()
                var restored = mutableUiState.value
                if (saved != null) {
                    require(saved["version"] == "1")
                    val completed = saved.getValue("completed").toBooleanStrict()
                    restored = restored.copy(completed = completed,
                        highlightedWordCount = saved.getValue("highlightedWordCount").toInt().also { require(it in 0..wordCount) },
                        feedbackZh = saved.getValue("feedbackZh").ifEmpty { null }
                            ?.takeUnless(String::isReadSpeakNoAudioCompletionCopy),
                        captureFailed = !completed && saved.getValue("interrupted").toBooleanStrict(),
                    )
                }
                when (val recovery = repository.restoreFeedback()) {
                    ReadSpeakFeedbackRecovery.None -> Unit
                    ReadSpeakFeedbackRecovery.Unavailable -> error("Original feedback recovery is unavailable")
                    ReadSpeakFeedbackRecovery.Blocked -> restored = restored.copy(captureFailed = !restored.completed,
                        pendingFeedbackRetry = false, feedbackRetryBlocked = true)
                    is ReadSpeakFeedbackRecovery.Pending -> {
                        // Do not recapture or automatically retry a submitted request on entry.
                        failedFeedbackSignal = recovery.signal
                        restored = restored.copy(completed = false, captureFailed = true, pendingFeedbackRetry = true)
                    }
                    is ReadSpeakFeedbackRecovery.Completed -> {
                        val result = recovery.result
                        val completed = result == ReadSpeakFeedbackResult.CompletedWithoutAudio ||
                            (result is ReadSpeakFeedbackResult.Ready &&
                                (recovery.signal != ReadSpeakCompletionSignal.RECOGNITION_COMPLETE || result.contentComplete))
                        restored = restored.copy(completed = completed, captureFailed = !completed,
                            feedbackZh = when (result) {
                                is ReadSpeakFeedbackResult.Ready -> result.feedbackZh
                                ReadSpeakFeedbackResult.CompletedWithoutAudio -> null
                                ReadSpeakFeedbackResult.Failed -> error("Failure is not saved feedback")
                            })
                    }
                }
                progressReady = true
                lastSavedProgress = saved
                mutableUiState.value = restored.copy(listening = false, audioPlaying = false, submitting = false, progressFailed = false)
                mutableUiState.map(::encodeProgress).distinctUntilChanged().collect { saveProgress() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableUiState.update { it.copy(submitting = false, progressFailed = true) } }
        }
    }

    private suspend fun saveProgress(): Boolean {
        val store = progressStore ?: return true
        if (!progressReady) return false
        return progressMutex.withLock {
            val snapshot = encodeProgress(mutableUiState.value)
            val saved = if (snapshot == lastSavedProgress) true else try { store.write(snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (saved) lastSavedProgress = snapshot
            mutableUiState.update { it.copy(progressFailed = !saved) }
            saved
        }
    }
}
