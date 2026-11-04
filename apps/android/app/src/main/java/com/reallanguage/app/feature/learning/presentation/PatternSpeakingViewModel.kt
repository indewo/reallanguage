package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedbackResult
import com.reallanguage.app.feature.learning.domain.PatternPracticeGenerationResult
import com.reallanguage.app.feature.learning.domain.PatternSpeakingRepository
import com.reallanguage.app.feature.learning.domain.LearningProgressStore
import com.reallanguage.app.feature.learning.domain.PatternPracticeSet
import com.reallanguage.app.feature.learning.domain.PatternFeedbackRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PatternSpeakingViewModel(
    chapter: FixedLearningChapter,
    currentFocusSentenceId: String,
    lockedPatterns: List<LockedSpeakingPattern>?,
    private val repository: PatternSpeakingRepository,
    private val progressStore: LearningProgressStore? = null,
    private val reloadLockedPatterns: (() -> List<LockedSpeakingPattern>?)? = null,
    private val loadRemovedPatterns: suspend () -> List<LockedSpeakingPattern> = { emptyList() },
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        PatternSpeakingUiState(
            chapter = chapter,
            currentFocusSentenceId = currentFocusSentenceId,
            lockedPatterns = lockedPatterns.orEmpty(),
        ),
    )
    val uiState: StateFlow<PatternSpeakingUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<PatternSpeakingEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var progressReady = progressStore == null
    private val progressMutex = Mutex()
    private var lastSavedProgress: Map<String, String>? = null
    private var patternsReady = lockedPatterns != null
    private var restoreInProgress = false

    init {
        if (!patternsReady) {
            mutableUiState.update { it.copy(failureMessageVisible = true) }
        } else if (lockedPatterns.orEmpty().isEmpty()) {
            viewModelScope.launch { effectChannel.send(PatternSpeakingEffect.OpenCompleteWriting) }
        } else if (progressStore != null) {
            restoreProgress()
        } else {
            loadCurrentPattern()
        }
    }

    fun interruptUnsubmittedCapture() {
        if (!mutableUiState.value.recording || mutableUiState.value.submitting) return
        repository.cancelCapture()
        mutableUiState.update { it.copy(recording = false, readingExampleId = null, failureMessageVisible = true) }
    }

    fun onAction(action: PatternSpeakingAction) {
        if (mutableUiState.value.submitting) return
        if (!patternsReady) {
            when (action) {
                PatternSpeakingAction.BackRequested -> viewModelScope.launch { effectChannel.send(PatternSpeakingEffect.ReturnToFullSentenceSpeaking) }
                PatternSpeakingAction.RetryLoadingRequested, PatternSpeakingAction.RetryProgressRequested -> {
                    val loaded = runCatching { reloadLockedPatterns?.invoke() }.getOrNull() ?: return
                    patternsReady = true
                    mutableUiState.update { it.copy(lockedPatterns = loaded, failureMessageVisible = false) }
                    if (loaded.isEmpty()) viewModelScope.launch { effectChannel.send(PatternSpeakingEffect.OpenCompleteWriting) }
                    else if (progressStore != null) restoreProgress() else loadCurrentPattern()
                }
                else -> Unit
            }
            return
        }
        if (!progressReady) {
            when (action) {
                PatternSpeakingAction.BackRequested -> viewModelScope.launch { effectChannel.send(PatternSpeakingEffect.ReturnToFullSentenceSpeaking) }
                PatternSpeakingAction.RetryLoadingRequested, PatternSpeakingAction.RetryProgressRequested -> restoreProgress()
                else -> Unit
            }
            return
        }
        if (mutableUiState.value.completed && action != PatternSpeakingAction.BackRequested && action != PatternSpeakingAction.RetryProgressRequested) return
        when (action) {
            PatternSpeakingAction.BackRequested -> send(
                PatternSpeakingEffect.ReturnToFullSentenceSpeaking,
            )
            PatternSpeakingAction.AudioToggled -> mutableUiState.update {
                it.copy(audioMuted = !it.audioMuted)
            }
            PatternSpeakingAction.PronounceRequested -> Unit
            is PatternSpeakingAction.StartReadingRequested -> startReading(action.exampleId)
            is PatternSpeakingAction.ReadingFinished -> finishReading(action.exampleId)
            PatternSpeakingAction.StartResponsesRequested -> startResponses()
            PatternSpeakingAction.StartResponseRequested -> startResponse()
            PatternSpeakingAction.FinishResponseRequested -> finishResponse()
            is PatternSpeakingAction.TranscriptChanged -> mutableUiState.update {
                if (it.stage == PatternSpeakingStage.RESPONDING && !it.technicalRetryPending && !it.technicalRetryBlocked) {
                    it.copy(transcript = action.value)
                } else it
            }
            PatternSpeakingAction.RetryResponseRequested -> {
                if (mutableUiState.value.technicalRetryBlocked) return
                if (mutableUiState.value.technicalRetryPending) finishResponse(retry = true)
                else mutableUiState.update { it.copy(
                    recording = false,
                    transcript = "",
                    feedbackZh = null,
                    repeatRecommended = false,
                    currentResponseCompleted = false,
                    failureMessageVisible = false,
                ) }
            }
            PatternSpeakingAction.NextSceneRequested -> {
                if (mutableUiState.value.currentResponseCompleted) advanceResponse()
            }
            PatternSpeakingAction.SkipSceneRequested -> advanceResponse()
            PatternSpeakingAction.RetryLoadingRequested -> {
                if (mutableUiState.value.failureMessageVisible) loadCurrentPattern()
            }
            PatternSpeakingAction.RetryProgressRequested -> viewModelScope.launch {
                if (saveProgress() && mutableUiState.value.completed) effectChannel.send(PatternSpeakingEffect.OpenCompleteWriting)
            }
            PatternSpeakingAction.ContinueWithoutPatternRequested -> {
                if (mutableUiState.value.stage == PatternSpeakingStage.UNAVAILABLE) advancePattern()
            }
        }
    }

    private fun startReading(exampleId: String) {
        val current = mutableUiState.value
        if (current.technicalRetryPending) {
            if (current.feedbackExampleId == exampleId) finishReading(exampleId, retry = true)
            return
        }
        if (current.stage != PatternSpeakingStage.READING || current.submitting || current.recording ||
            exampleId in current.completedReadingExampleIds ||
            current.examples.none { it.exampleId == exampleId }
        ) return
        if (repository.requiresFeedbackRecovery) {
            prepareCapture(current, exampleId, reading = true)
        } else if (repository.startCapture()) {
            mutableUiState.update {
                it.copy(recording = true, readingExampleId = exampleId, failureMessageVisible = false,
                    technicalRetryPending = false, technicalRetryBlocked = false, feedbackExampleId = null)
            }
        } else mutableUiState.update { it.copy(failureMessageVisible = true) }
    }

    private fun startResponse() {
        val current = mutableUiState.value
        if (current.technicalRetryPending) { finishResponse(retry = true); return }
        if (current.stage != PatternSpeakingStage.RESPONDING || current.submitting || current.recording || current.currentResponseCompleted) return
        if (repository.requiresFeedbackRecovery) {
            prepareCapture(current, requireNotNull(current.currentResponseExample).exampleId, reading = false)
        } else if (repository.startCapture()) {
            mutableUiState.update { it.copy(recording = true, failureMessageVisible = false,
                technicalRetryPending = false, technicalRetryBlocked = false, feedbackExampleId = null) }
        } else mutableUiState.update { it.copy(failureMessageVisible = true) }
    }

    private fun prepareCapture(current: PatternSpeakingUiState, exampleId: String, reading: Boolean) {
        val pattern = current.currentPattern ?: return
        mutableUiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            try {
                // Saved examples and the exact target must exist before journal ownership can be granted.
                val progressSaved = saveProgress()
                val recovery = if (progressSaved) repository.restoreFeedback(pattern, PatternPracticeSet(pattern.patternId, current.examples))
                    else PatternFeedbackRecovery.Unavailable
                // Skip may already be committed while its prior unknown response still has a journal.
                // Release that exact superseded request only before an explicit new-scene recording.
                val superseded = !reading && recovery is PatternFeedbackRecovery.Pending && !recovery.reading && recovery.exampleId != exampleId
                val started = progressSaved && recovery != PatternFeedbackRecovery.Unavailable &&
                    (!superseded || repository.releaseSupersededFeedback()) &&
                    repository.prepareFeedbackCapture(pattern.patternId, exampleId, reading) && repository.startCapture()
                mutableUiState.update { it.copy(submitting = false, recording = started,
                    readingExampleId = if (started && reading) exampleId else null,
                    failureMessageVisible = !started,
                    technicalRetryPending = if (started) false else it.technicalRetryPending,
                    technicalRetryBlocked = if (started) false else it.technicalRetryBlocked,
                    feedbackExampleId = if (started) null else it.feedbackExampleId) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableUiState.update { it.copy(submitting = false, failureMessageVisible = true) } }
        }
    }

    private fun loadCurrentPattern() {
        if (mutableUiState.value.technicalRetryPending) return
        val pattern = mutableUiState.value.currentPattern ?: run {
            send(PatternSpeakingEffect.OpenCompleteWriting)
            return
        }
        if (mutableUiState.value.submitting) return
        mutableUiState.update {
            it.copy(stage = PatternSpeakingStage.LOADING, submitting = true, failureMessageVisible = false)
        }
        viewModelScope.launch {
            when (val result = repository.generate(pattern)) {
                is PatternPracticeGenerationResult.Ready -> {
                    val validExamples = result.practiceSet.examples
                        .takeIf { items -> items.size in 1..5 && items.map { it.exampleId }.distinct().size == items.size &&
                            items.all { listOf(it.exampleId, it.sceneZh, it.aiPromptEn, it.targetResponseEn, it.targetResponseZh).all(String::isNotBlank) } }
                    if (result.practiceSet.patternId != pattern.patternId || validExamples == null) {
                        mutableUiState.update {
                            it.copy(stage = PatternSpeakingStage.LOADING, submitting = false, failureMessageVisible = true)
                        }
                    } else {
                        mutableUiState.update {
                            it.copy(
                                stage = PatternSpeakingStage.READING,
                                examples = validExamples,
                                completedReadingExampleIds = emptySet(),
                                responseIndex = 0,
                                submitting = false,
                            )
                        }
                    }
                }
                PatternPracticeGenerationResult.NotNaturallyVerifiable -> {
                    mutableUiState.update { it.copy(stage = PatternSpeakingStage.UNAVAILABLE, submitting = false) }
                }
                PatternPracticeGenerationResult.Failed -> mutableUiState.update {
                    it.copy(stage = PatternSpeakingStage.LOADING, submitting = false, failureMessageVisible = true)
                }
            }
        }
    }

    private fun finishReading(exampleId: String, retry: Boolean = false) {
        val current = mutableUiState.value
        if (current.stage != PatternSpeakingStage.READING ||
            current.completedReadingExampleIds.contains(exampleId) ||
            current.submitting ||
            (if (retry) !current.technicalRetryPending || current.feedbackExampleId != exampleId || !repository.hasPendingFeedbackRetry()
                else !current.recording || current.readingExampleId != exampleId) ||
            current.examples.none { it.exampleId == exampleId }
        ) return
        val pattern = current.currentPattern ?: return
        mutableUiState.update { it.copy(recording = false, readingExampleId = null, submitting = true) }
        viewModelScope.launch {
            when (val result = repository.readingFeedback(pattern.patternId, exampleId)) {
                is PatternPracticeFeedbackResult.Ready -> mutableUiState.update {
                    it.copy(
                        submitting = false,
                        completedReadingExampleIds = it.completedReadingExampleIds + exampleId,
                        feedbackZh = result.feedback.feedbackZh,
                        failureMessageVisible = false, technicalRetryPending = false,
                        technicalRetryBlocked = false, feedbackExampleId = null,
                    )
                }
                PatternPracticeFeedbackResult.Inconclusive,
                PatternPracticeFeedbackResult.Failed,
                -> mutableUiState.update {
                    it.copy(submitting = false, failureMessageVisible = true,
                        technicalRetryPending = repository.hasPendingFeedbackRetry(),
                        technicalRetryBlocked = repository.technicalRetryBlocked(), feedbackExampleId = exampleId)
                }
            }
        }
    }

    private fun startResponses() {
        val current = mutableUiState.value
        if (current.stage == PatternSpeakingStage.READING && current.examples.isNotEmpty() &&
            current.completedReadingExampleIds.size == current.examples.size
        ) {
            transition(current.copy(
                    stage = PatternSpeakingStage.RESPONDING,
                    responseIndex = 0,
                    feedbackZh = null,
                ))
        }
    }

    private fun finishResponse(retry: Boolean = false) {
        val current = mutableUiState.value
        val pattern = current.currentPattern ?: return
        val example = current.currentResponseExample ?: return
        if (current.stage != PatternSpeakingStage.RESPONDING || current.submitting ||
            (if (retry) !current.technicalRetryPending || current.feedbackExampleId != example.exampleId || !repository.hasPendingFeedbackRetry()
                else !current.recording)) return
        mutableUiState.update { it.copy(recording = false, submitting = true) }
        viewModelScope.launch {
            when (
                val result = repository.responseFeedback(
                    pattern.patternId,
                    example.exampleId,
                )
            ) {
                is PatternPracticeFeedbackResult.Ready -> mutableUiState.update {
                    it.copy(
                        submitting = false,
                        feedbackZh = result.feedback.feedbackZh,
                        transcript = result.feedback.transcript,
                        repeatRecommended = result.feedback.repeatRecommended,
                        currentResponseCompleted = true,
                        failureMessageVisible = false, technicalRetryPending = false,
                        technicalRetryBlocked = false, feedbackExampleId = null,
                    )
                }
                PatternPracticeFeedbackResult.Inconclusive,
                PatternPracticeFeedbackResult.Failed,
                -> mutableUiState.update {
                    it.copy(submitting = false, failureMessageVisible = true,
                        technicalRetryPending = repository.hasPendingFeedbackRetry(),
                        technicalRetryBlocked = repository.technicalRetryBlocked(), feedbackExampleId = example.exampleId)
                }
            }
        }
    }

    private fun advanceResponse() {
        val current = mutableUiState.value
        if (current.stage != PatternSpeakingStage.RESPONDING) return
        repository.cancelCapture()
        // Capture has stopped even if the following checkpoint cannot be saved.
        // The next pattern must not inherit a recording that no longer exists.
        mutableUiState.update { it.copy(recording = false, readingExampleId = null,
            technicalRetryPending = false, technicalRetryBlocked = false, feedbackExampleId = null) }
        if (current.responseIndex + 1 < current.examples.size) {
            transition(
                current.copy(
                    responseIndex = current.responseIndex + 1,
                    recording = false,
                    transcript = "",
                    feedbackZh = null,
                    repeatRecommended = false,
                    currentResponseCompleted = false,
                    failureMessageVisible = false,
                    technicalRetryPending = false, technicalRetryBlocked = false, feedbackExampleId = null,
                )
            )
        } else {
            advancePattern()
        }
    }

    private fun advancePattern() {
        val current = mutableUiState.value
        val pattern = current.currentPattern ?: return
        val processed = current.processedPatternIds + pattern.patternId
        // An old paid current target may now occur after other unfinished targets. Finish that
        // exact practice, then choose the first genuinely unprocessed target in source order.
        val nextIndex = current.lockedPatterns.indexOfFirst { it.patternId !in processed }
        if (nextIndex == -1) {
            transition(current.copy(completed = true, processedPatternIds = processed)) {
                viewModelScope.launch { effectChannel.send(PatternSpeakingEffect.OpenCompleteWriting) }
            }
            return
        }
        transition(
            current.copy(
                patternIndex = nextIndex,
                processedPatternIds = processed,
                stage = PatternSpeakingStage.LOADING,
                examples = emptyList(),
                completedReadingExampleIds = emptySet(),
                responseIndex = 0,
                transcript = "",
                feedbackZh = null,
                repeatRecommended = false,
                currentResponseCompleted = false,
                failureMessageVisible = false,
            )
        ) { loadCurrentPattern() }
    }

    private fun send(effect: PatternSpeakingEffect) {
        repository.cancelCapture()
        viewModelScope.launch { if (saveProgress()) effectChannel.send(effect) }
    }

    private fun transition(next: PatternSpeakingUiState, after: () -> Unit = {}) {
        if (progressStore == null) { mutableUiState.value = next; after(); return }
        mutableUiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            if (saveProgress(next)) after()
            else mutableUiState.update { it.copy(submitting = false) }
        }
    }

    private fun restoreProgress() {
        val store = progressStore ?: return
        if (restoreInProgress) return
        restoreInProgress = true
        mutableUiState.update { it.copy(submitting = true, failureMessageVisible = false, progressFailed = false) }
        viewModelScope.launch {
            try {
                val saved = store.read()
                var restored = saved?.let { projectPatternProgress(mutableUiState.value, it, loadRemovedPatterns()) }
                if (restored != null && restored.examples.isNotEmpty()) {
                    repository.restorePractice(requireNotNull(restored.currentPattern), PatternPracticeSet(requireNotNull(restored.currentPattern).patternId, restored.examples))
                    if (!restored.completed) restored = restoreFeedback(restored)
                }
                progressReady = true
                lastSavedProgress = saved
                mutableUiState.value = restored ?: mutableUiState.value.copy(submitting = false)
                if (restored?.completed == true) effectChannel.send(PatternSpeakingEffect.OpenCompleteWriting)
                else if (saved == null) loadCurrentPattern()
                restoreInProgress = false
                mutableUiState.map(::encodePatternProgress).distinctUntilChanged().collect { saveProgress() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                progressReady = false
                mutableUiState.update { it.copy(submitting = false, progressFailed = true, failureMessageVisible = true) }
            } finally { restoreInProgress = false }
        }
    }

    private suspend fun restoreFeedback(current: PatternSpeakingUiState): PatternSpeakingUiState {
        val pattern = requireNotNull(current.currentPattern)
        val recovery = repository.restoreFeedback(pattern, PatternPracticeSet(pattern.patternId, current.examples))
        check(recovery != PatternFeedbackRecovery.Unavailable) { "The current feedback request cannot be safely restored" }
        val identity = when (recovery) {
            is PatternFeedbackRecovery.Pending -> recovery.exampleId to recovery.reading
            is PatternFeedbackRecovery.Delivered -> recovery.exampleId to recovery.reading
            is PatternFeedbackRecovery.Blocked -> recovery.exampleId to recovery.reading
            else -> return current
        }
        val (exampleId, reading) = identity
        // Already-consumed feedback is idempotent by example identity. Never apply it to a later scene.
        val applicable = if (reading) current.stage == PatternSpeakingStage.READING &&
            exampleId !in current.completedReadingExampleIds && current.examples.any { it.exampleId == exampleId }
        else current.stage == PatternSpeakingStage.RESPONDING && current.currentResponseExample?.exampleId == exampleId && !current.currentResponseCompleted
        if (!applicable) return current
        return when (recovery) {
            is PatternFeedbackRecovery.Pending -> current.copy(technicalRetryPending = true, technicalRetryBlocked = false,
                feedbackExampleId = exampleId, failureMessageVisible = true)
            is PatternFeedbackRecovery.Blocked -> current.copy(technicalRetryPending = false, technicalRetryBlocked = true,
                feedbackExampleId = exampleId, failureMessageVisible = true)
            is PatternFeedbackRecovery.Delivered -> when (val result = recovery.result) {
                is PatternPracticeFeedbackResult.Ready -> if (reading) current.copy(
                    completedReadingExampleIds = current.completedReadingExampleIds + exampleId,
                    feedbackZh = result.feedback.feedbackZh, failureMessageVisible = false)
                else current.copy(transcript = result.feedback.transcript, feedbackZh = result.feedback.feedbackZh,
                    repeatRecommended = result.feedback.repeatRecommended, currentResponseCompleted = true, failureMessageVisible = false)
                PatternPracticeFeedbackResult.Inconclusive -> current.copy(failureMessageVisible = true)
                PatternPracticeFeedbackResult.Failed -> error("Failed is not a delivered candidate")
            }
            else -> current
        }
    }

    private suspend fun saveProgress(target: PatternSpeakingUiState? = null): Boolean {
        val store = progressStore ?: return true
        if (!progressReady) return false
        return progressMutex.withLock {
            val snapshot = encodePatternProgress(target ?: mutableUiState.value)
            val saved = if (snapshot == lastSavedProgress) true else try { store.write(snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (saved) lastSavedProgress = snapshot
            if (saved && target != null) {
                // Publish the committed transition before unlocking. A queued auto-save must
                // never read the old active scene after this newer scene/completion was saved.
                mutableUiState.value = target.copy(progressFailed = false, submitting = false)
            } else mutableUiState.update { it.copy(progressFailed = !saved) }
            saved
        }
    }

    override fun onCleared() {
        repository.cancelCapture()
        super.onCleared()
    }
}
