package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FullSentenceSpeakingRepository
import com.reallanguage.app.feature.learning.domain.FullSpeakingRecovery
import com.reallanguage.app.feature.learning.domain.LearningProgressStore
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationResult
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationCandidate
import com.reallanguage.app.feature.learning.domain.SpeakingDecisionSaveResult
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackResult
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackAction
import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FullSentenceSpeakingViewModel(
    chapter: FixedLearningChapter,
    currentFocusSentenceId: String,
    private val repository: FullSentenceSpeakingRepository,
    private val progressStore: LearningProgressStore? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        FullSentenceSpeakingUiState(
            chapter = chapter,
            currentFocusSentenceId = currentFocusSentenceId,
        ),
    )
    val uiState: StateFlow<FullSentenceSpeakingUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<FullSentenceSpeakingEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var pendingCandidate: SpeakingEvaluationCandidate? = null
    // This binds an editable ASR draft, not merely an in-progress microphone session.
    private var transcriptRecordingId: String? = null
    private var progressReady = progressStore == null && !repository.requiresAttemptRecovery
    private val progressMutex = Mutex()
    private var lastSavedProgress: Map<String, String>? = null
    private var progressObserver: Job? = null
    private var capturePreparation: Job? = null
    private var preparingCapture = false
    private var captureGeneration = 0L
    private var restoreJob: Job? = null
    private var actionAfterRestore: FullSentenceSpeakingAction? = null
    private var automaticRetryJob: Job? = null
    private var automaticRetryAttempt = 0

    init { if (progressStore != null || repository.requiresAttemptRecovery) restoreProgress() }

    /** Stop an unsubmitted microphone attempt without cancelling an already delivered/paid result. */
    fun interruptUnsubmittedCapture() {
        if (!mutableUiState.value.recording && !preparingCapture) return
        val wasPreparing = preparingCapture
        captureGeneration++
        preparingCapture = false
        capturePreparation?.cancel()
        capturePreparation = null
        repository.cancelCapture()
        mutableUiState.update { it.copy(recording = false, submitting = if (wasPreparing) false else it.submitting, captureFailed = true) }
    }

    fun onAction(action: FullSentenceSpeakingAction) {
        if (!progressReady) {
            if (action == FullSentenceSpeakingAction.BackRequested) viewModelScope.launch { effectChannel.send(FullSentenceSpeakingEffect.ReturnToAudioDictation) }
            else if (action == FullSentenceSpeakingAction.StartRequested || action == FullSentenceSpeakingAction.RetryRequested) {
                // Preserve the user's one tap while the durable journal is still being read.
                // The action is dispatched immediately after restoration, so a second tap is
                // never required just to continue the same recording/request.
                actionAfterRestore = action
                restoreProgress()
            }
            return
        }
        if (mutableUiState.value.submitting) return
        when (action) {
            FullSentenceSpeakingAction.BackRequested -> send(
                FullSentenceSpeakingEffect.ReturnToAudioDictation,
            )
            FullSentenceSpeakingAction.AnswerAudioRequested -> mutableUiState.update {
                if (it.technicalRetryPending || it.technicalRetryBlocked) it
                else it.copy(answerAudioPlayed = it.answerAudioPlayed || it.isIndependentRetelling)
            }
            FullSentenceSpeakingAction.StartRequested -> startCapture()
            FullSentenceSpeakingAction.FinishedRequested -> finishAttempt()
            is FullSentenceSpeakingAction.TranscriptChanged -> mutableUiState.update {
                if (it.isIndependentRetelling && pendingCandidate == null && it.finalDecision == null &&
                    !it.technicalRetryPending && !it.technicalRetryBlocked) it.copy(transcript = action.value) else it
            }
            FullSentenceSpeakingAction.ConfirmTranscriptRequested -> evaluateConfirmedTranscript()
            FullSentenceSpeakingAction.ContinueTrainingRequested -> continueTraining()
            FullSentenceSpeakingAction.RetryRequested -> {
                if (mutableUiState.value.technicalRetryBlocked) return
                if (mutableUiState.value.technicalRetryPending) {
                    cancelAutomaticRetry(resetAttempt = true)
                    replayPendingRequest()
                    return
                }
                if (mutableUiState.value.finalDecision == SpeakingEvaluationDecision.PASS) {
                    retryCompletedCheckpoint()
                    return
                }
                pendingCandidate = null
                transcriptRecordingId = null
                mutableUiState.update {
                it.copy(
                    recording = false,
                    submitting = false,
                    transcript = "",
                    transcriptReadyForConfirmation = false,
                    lowConfidenceSegments = emptyList(),
                    feedbackZh = null,
                    feedbackConfidence = null,
                    feedbackDetails = emptyList(),
                    finalDecision = null,
                    captureFailed = false,
                    evaluationReceived = false,
                    decisionSaveFailed = false,
                    technicalRetryPending = false,
                    technicalRetryBlocked = false,
                )
            }
            }
            FullSentenceSpeakingAction.StopPractisingRequested -> send(
                FullSentenceSpeakingEffect.ArchiveWrongAndContinue,
            )
        }
    }

    private fun startCapture() {
        val current = mutableUiState.value
        if (current.recording || current.submitting || current.trainingCanContinue || current.transcriptReadyForConfirmation || current.finalDecision != null || current.technicalRetryPending) return
        if (!repository.requiresAttemptRecovery) {
            openMicrophone()
            return
        }
        val generation = ++captureGeneration
        preparingCapture = true
        mutableUiState.update { it.copy(submitting = true, captureFailed = false) }
        capturePreparation = viewModelScope.launch {
            try {
                if (!saveProgress()) return@launch
                currentCoroutineContext().ensureActive()
                if (captureGeneration != generation || !preparingCapture) return@launch
                val prepared = repository.prepareCapture(current.currentFocusSentenceId, current.attemptIndex)
                currentCoroutineContext().ensureActive()
                if (captureGeneration != generation || !preparingCapture) return@launch
                if (prepared) openMicrophone()
                else mutableUiState.update { it.copy(captureFailed = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (captureGeneration == generation) mutableUiState.update { it.copy(captureFailed = true) }
            } finally {
                if (captureGeneration == generation) {
                    preparingCapture = false
                    capturePreparation = null
                    mutableUiState.update { it.copy(submitting = false) }
                }
            }
        }
    }

    private fun openMicrophone() {
        cancelAutomaticRetry(resetAttempt = true)
        if (repository.startCapture()) {
            if (repository.requiresAttemptRecovery) {
                pendingCandidate = null
                transcriptRecordingId = null
            }
            mutableUiState.update {
                it.copy(recording = true, captureFailed = false, feedbackZh = null,
                    feedbackConfidence = null, feedbackDetails = emptyList(), needsNewRecording = false,
                    technicalRetryBlocked = false, technicalRetryPending = false,
                    transcript = if (repository.requiresAttemptRecovery) "" else it.transcript,
                    lowConfidenceSegments = if (repository.requiresAttemptRecovery) emptyList() else it.lowConfidenceSegments,
                    evaluationReceived = if (repository.requiresAttemptRecovery) false else it.evaluationReceived,
                    decisionSaveFailed = if (repository.requiresAttemptRecovery) false else it.decisionSaveFailed)
            }
        } else {
            mutableUiState.update { it.copy(recording = false, captureFailed = true) }
        }
    }

    private fun finishAttempt(retry: Boolean = false) {
        val current = mutableUiState.value
        if ((!current.recording && !(retry && current.technicalRetryPending && current.trainingMode != null)) || current.submitting) return
        mutableUiState.update { it.copy(recording = false, submitting = true, captureFailed = false) }
        val trainingMode = current.trainingMode
        if (trainingMode != null) {
            viewModelScope.launch {
                when (
                    val result = repository.trainingFeedback(
                        current.currentFocusSentenceId,
                        trainingMode,
                    )
                ) {
                    is ReadingFeedbackResult.Ready -> {
                        cancelAutomaticRetry(resetAttempt = true)
                        mutableUiState.update {
                            it.copy(
                                submitting = false,
                                feedbackZh = result.feedback.feedbackZh,
                                feedbackConfidence = result.feedback.feedbackConfidence,
                                feedbackDetails = result.feedback.details,
                                trainingCanContinue = result.feedback.action == ReadingFeedbackAction.CONTINUE,
                                technicalRetryPending = false,
                                technicalRetryBlocked = false,
                            )
                        }
                    }
                    ReadingFeedbackResult.Failed -> {
                        val pending = repository.hasPendingTrainingRetry()
                        mutableUiState.update {
                            it.copy(submitting = false, captureFailed = true,
                                technicalRetryPending = pending,
                                technicalRetryBlocked = repository.technicalRetryBlocked())
                        }
                        if (pending) scheduleAutomaticRetry()
                    }
                }
            }
            return
        }
        transcribeCurrentAttempt()
    }

    private fun transcribeCurrentAttempt() {
        val current = mutableUiState.value
        mutableUiState.update { it.copy(recording = false, submitting = true, captureFailed = false) }
        viewModelScope.launch {
            when (val result = repository.transcribeIndependentRetelling(current.currentFocusSentenceId)) {
                is SpeakingTranscriptionResult.Ready -> {
                    cancelAutomaticRetry(resetAttempt = true)
                    currentCoroutineContext().ensureActive()
                    transcriptRecordingId = repository.currentRecordingId()?.takeIf { it.isNotBlank() }
                    mutableUiState.update {
                        it.copy(
                            submitting = false,
                            transcript = result.value.transcript,
                            lowConfidenceSegments = result.value.lowConfidenceSegments,
                            transcriptReadyForConfirmation = true,
                            technicalRetryPending = false,
                            technicalRetryBlocked = false,
                        )
                    }
                }
                SpeakingTranscriptionResult.Failed -> {
                    val pending = repository.hasPendingTranscriptionRetry()
                    mutableUiState.update {
                        it.copy(submitting = false, captureFailed = true,
                            technicalRetryPending = pending,
                            technicalRetryBlocked = repository.technicalRetryBlocked())
                    }
                    if (pending) scheduleAutomaticRetry()
                }
            }
        }
    }

    private fun evaluateConfirmedTranscript() {
        val current = mutableUiState.value
        if (!current.isIndependentRetelling || !current.transcriptReadyForConfirmation ||
            current.transcript.isBlank() || current.submitting || current.finalDecision != null || current.technicalRetryBlocked
        ) return
        mutableUiState.update { it.copy(submitting = true, captureFailed = false) }
        viewModelScope.launch {
            val result = pendingCandidate?.let { SpeakingEvaluationResult.Ready(it) } ?: repository.evaluateIndependentRetelling(
                current.currentFocusSentenceId,
                current.transcript.trim(),
                current.answerAudioPlayed,
            )
            when (result) {
                is SpeakingEvaluationResult.Ready -> {
                    cancelAutomaticRetry(resetAttempt = true)
                    val candidate = result.candidate
                    pendingCandidate = candidate
                    mutableUiState.update { it.copy(evaluationReceived = true, technicalRetryPending = false, technicalRetryBlocked = false) }
                    if (!saveProgress()) { mutableUiState.update { it.copy(submitting = false) }; return@launch }
                    val eligiblePass = !current.answerAudioPlayed &&
                        candidate.completenessScore >= 80 &&
                        candidate.coreMeaningRecognizable &&
                        candidate.pronunciationIntelligible &&
                        candidate.decision == SpeakingEvaluationDecision.PASS
                    val decision = when {
                        candidate.decision == SpeakingEvaluationDecision.INCONCLUSIVE -> {
                            SpeakingEvaluationDecision.INCONCLUSIVE
                        }
                        eligiblePass -> SpeakingEvaluationDecision.PASS
                        else -> SpeakingEvaluationDecision.FAIL
                    }
                    if (decision == SpeakingEvaluationDecision.INCONCLUSIVE) {
                        mutableUiState.update {
                            it.copy(submitting = false, feedbackZh = candidate.feedbackZh, finalDecision = decision)
                        }
                    } else when (
                        repository.saveIndependentDecision(current.currentFocusSentenceId, decision)
                    ) {
                        SpeakingDecisionSaveResult.Saved -> {
                            mutableUiState.update {
                                it.copy(submitting = false, feedbackZh = candidate.feedbackZh, finalDecision = decision, decisionSaveFailed = false, captureFailed = false)
                            }
                            if (decision == SpeakingEvaluationDecision.PASS) {
                                send(FullSentenceSpeakingEffect.OpenPatternSpeaking)
                            }
                        }
                        SpeakingDecisionSaveResult.Failed -> mutableUiState.update {
                            it.copy(submitting = false, captureFailed = true, decisionSaveFailed = true)
                        }
                    }
                }
                SpeakingEvaluationResult.Failed -> {
                    // Unknown delivery retains and replays the original command automatically. A
                    // missing original or terminal server status remains a different, blocked state.
                    val retryable = repository.hasPendingEvaluationRetry()
                    val blocked = repository.technicalRetryBlocked()
                    mutableUiState.update {
                        it.copy(submitting = false, captureFailed = true,
                            transcriptReadyForConfirmation = retryable, needsNewRecording = !retryable && !blocked,
                            technicalRetryPending = retryable, technicalRetryBlocked = blocked)
                    }
                    if (retryable) scheduleAutomaticRetry()
                }
            }
        }
    }

    private fun continueTraining() {
        val current = mutableUiState.value
        if (!current.trainingCanContinue || current.isIndependentRetelling) return
        val next = current.copy(
                attemptIndex = current.attemptIndex + 1,
                recording = false,
                feedbackZh = null,
                feedbackConfidence = null,
                feedbackDetails = emptyList(),
                trainingCanContinue = false,
                captureFailed = false,
            )
        if (repository.requiresAttemptRecovery) {
            mutableUiState.update { it.copy(submitting = true) }
            viewModelScope.launch {
                try {
                    if (!saveProgress(next, publishTarget = true)) {
                        mutableUiState.update { it.copy(submitting = false) }
                        return@launch
                    }
                    // The new index is already acknowledged and visible to queued auto-saves.
                    // Only then may the repository bind its journal to the next exact round.
                    progressReady = false
                    val recovered = recoverAttempt(mutableUiState.value)
                    currentCoroutineContext().ensureActive()
                    progressReady = true
                    mutableUiState.value = recovered.copy(submitting = false, progressFailed = false)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    progressReady = false
                    mutableUiState.update { it.copy(submitting = false, progressFailed = true) }
                }
            }
        } else if (progressStore == null) mutableUiState.value = next
        else {
            mutableUiState.update { it.copy(submitting = true) }
            viewModelScope.launch {
                if (saveProgress(next)) mutableUiState.value = next.copy(progressFailed = false)
                else mutableUiState.update { it.copy(submitting = false) }
            }
        }
    }

    private fun retryCompletedCheckpoint() {
        if (!mutableUiState.value.progressFailed) return
        // The decision is already durable. Retry only the completion checkpoint,
        // never the consumed recording, paid evaluation or successful result write.
        mutableUiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            val saved = saveProgress()
            mutableUiState.update { it.copy(submitting = false) }
            if (saved) effectChannel.send(FullSentenceSpeakingEffect.OpenPatternSpeaking)
        }
    }

    override fun onCleared() {
        captureGeneration++
        preparingCapture = false
        capturePreparation?.cancel()
        restoreJob?.cancel()
        automaticRetryJob?.cancel()
        repository.cancelCapture()
        super.onCleared()
    }

    private fun send(effect: FullSentenceSpeakingEffect) {
        viewModelScope.launch { if (saveProgress()) effectChannel.send(effect) }
    }

    private fun encodeProgress(state: FullSentenceSpeakingUiState): Map<String, String> = buildMap {
        put("version", "1"); put("attemptIndex", state.attemptIndex.toString())
        put("answerAudioPlayed", state.answerAudioPlayed.toString())
        put("transcript", state.transcript); put("feedbackZh", state.feedbackZh.orEmpty())
        if (state.attemptIndex == 4) transcriptRecordingId?.let { put("recordingId", it) }
        put("trainingCanContinue", state.trainingCanContinue.toString())
        put("finalDecision", state.finalDecision?.name.orEmpty())
        pendingCandidate?.let { candidate ->
            put("candidateDecision", candidate.decision.name); put("candidateScore", candidate.completenessScore.toString())
            put("candidateMeaning", candidate.coreMeaningRecognizable.toString())
            put("candidatePronunciation", candidate.pronunciationIntelligible.toString())
            put("candidateFeedback", candidate.feedbackZh)
        }
    }

    private fun restoreProgress() {
        if (restoreJob?.isActive == true) return
        progressReady = false
        mutableUiState.update { it.copy(submitting = true, progressFailed = false) }
        restoreJob = viewModelScope.launch {
            try {
                val saved = progressStore?.read()
                currentCoroutineContext().ensureActive()
                var restored = mutableUiState.value
                pendingCandidate = null
                if (saved != null) {
                    require(saved["version"] == "1")
                    val round = saved.getValue("attemptIndex").toInt().also { require(it in 0..4) }
                    pendingCandidate = saved["candidateDecision"]?.let { decision ->
                        require(round == 4)
                        SpeakingEvaluationCandidate(SpeakingEvaluationDecision.valueOf(decision),
                            saved.getValue("candidateScore").toInt().also { require(it in 0..100) },
                            saved.getValue("candidateMeaning").toBooleanStrict(),
                            saved.getValue("candidatePronunciation").toBooleanStrict(), saved.getValue("candidateFeedback"))
                    }
                    restored = restored.copy(attemptIndex = round,
                        answerAudioPlayed = saved.getValue("answerAudioPlayed").toBooleanStrict(),
                        transcript = saved.getValue("transcript"), feedbackZh = saved.getValue("feedbackZh").ifEmpty { null },
                        trainingCanContinue = saved.getValue("trainingCanContinue").toBooleanStrict(),
                        finalDecision = saved.getValue("finalDecision").takeIf { it.isNotEmpty() }?.let(SpeakingEvaluationDecision::valueOf),
                        transcriptReadyForConfirmation = pendingCandidate != null,
                        evaluationReceived = pendingCandidate != null,
                        needsNewRecording = round == 4 && pendingCandidate == null && saved.getValue("transcript").isNotBlank(),
                    )
                    require(!restored.trainingCanContinue || round < 4)
                    require(restored.finalDecision == null || pendingCandidate != null)
                    if (restored.finalDecision == SpeakingEvaluationDecision.PASS) {
                        val candidate = requireNotNull(pendingCandidate)
                        require(!restored.answerAudioPlayed && candidate.decision == SpeakingEvaluationDecision.PASS &&
                            candidate.completenessScore >= 80 && candidate.coreMeaningRecognizable && candidate.pronunciationIntelligible)
                    }
                }
                if (repository.requiresAttemptRecovery) restored = recoverAttempt(restored, saved?.get("recordingId"))
                currentCoroutineContext().ensureActive()
                progressReady = true
                lastSavedProgress = saved
                mutableUiState.value = restored.copy(recording = false, submitting = false, progressFailed = false)
                if (restored.finalDecision == SpeakingEvaluationDecision.PASS) effectChannel.send(FullSentenceSpeakingEffect.OpenPatternSpeaking)
                if (progressStore != null && progressObserver == null) {
                    progressObserver = viewModelScope.launch {
                        mutableUiState.map(::encodeProgress).distinctUntilChanged().collect { saveProgress() }
                    }
                }
                val deferredAction = actionAfterRestore.also { actionAfterRestore = null }
                if (deferredAction != null && restored.finalDecision != SpeakingEvaluationDecision.PASS) {
                    onAction(deferredAction)
                } else if (restored.technicalRetryPending) {
                    scheduleAutomaticRetry()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                actionAfterRestore = null
                progressReady = false
                mutableUiState.update { it.copy(submitting = false, progressFailed = true) }
            } finally {
                restoreJob = null
            }
        }
    }

    /** Replays the exact durable request/audio; this never starts a second paid AI command. */
    private fun replayPendingRequest(): Boolean = when {
        repository.hasPendingTrainingRetry() -> true.also { finishAttempt(retry = true) }
        repository.hasPendingTranscriptionRetry() -> true.also { transcribeCurrentAttempt() }
        repository.hasPendingEvaluationRetry() -> true.also { evaluateConfirmedTranscript() }
        else -> false
    }

    /**
     * A lost response is recovered in-place. The increasing wait absorbs provider latency and
     * weak networks while every attempt keeps the same encrypted audio, command id and request body.
     */
    private fun scheduleAutomaticRetry() {
        val state = mutableUiState.value
        if (!repository.automaticallyResumePendingRequests || !progressReady || state.submitting ||
            !state.technicalRetryPending || state.technicalRetryBlocked || automaticRetryJob?.isActive == true ||
            automaticRetryAttempt >= AUTOMATIC_RETRY_DELAYS_MS.size
        ) return
        val waitMs = AUTOMATIC_RETRY_DELAYS_MS[automaticRetryAttempt++]
        automaticRetryJob = viewModelScope.launch {
            delay(waitMs)
            automaticRetryJob = null
            val latest = mutableUiState.value
            if (progressReady && latest.technicalRetryPending && !latest.technicalRetryBlocked && !latest.submitting) {
                replayPendingRequest()
            }
        }
    }

    private fun cancelAutomaticRetry(resetAttempt: Boolean) {
        automaticRetryJob?.cancel()
        automaticRetryJob = null
        if (resetAttempt) automaticRetryAttempt = 0
    }

    /** Journal facts replace edits that were persisted without an audio-attempt identity. */
    private suspend fun recoverAttempt(state: FullSentenceSpeakingUiState, savedRecordingId: String? = null): FullSentenceSpeakingUiState {
        transcriptRecordingId = null
        val recovery = repository.restoreAttempt(state.currentFocusSentenceId, state.attemptIndex)
        currentCoroutineContext().ensureActive()
        if (recovery == FullSpeakingRecovery.Unavailable) error("Attempt recovery unavailable")
        if (recovery == FullSpeakingRecovery.None) return state
        val previousCandidate = pendingCandidate
        pendingCandidate = null
        val clean = state.copy(recording = false, transcript = "", transcriptReadyForConfirmation = false,
            lowConfidenceSegments = emptyList(), feedbackZh = null, feedbackConfidence = null,
            feedbackDetails = emptyList(), trainingCanContinue = false,
            finalDecision = null, captureFailed = false, needsNewRecording = false, evaluationReceived = false,
            decisionSaveFailed = false, technicalRetryPending = false, technicalRetryBlocked = false)
        return when (recovery) {
            FullSpeakingRecovery.None, FullSpeakingRecovery.Unavailable -> error("Handled above")
            FullSpeakingRecovery.Blocked -> clean.copy(technicalRetryBlocked = true, captureFailed = true)
            FullSpeakingRecovery.TrainingPending -> {
                require(state.attemptIndex in 0..3)
                clean.copy(technicalRetryPending = true, captureFailed = true)
            }
            is FullSpeakingRecovery.TrainingReady -> {
                require(state.attemptIndex in 0..3)
                clean.copy(feedbackZh = recovery.feedback.feedbackZh,
                    feedbackConfidence = recovery.feedback.feedbackConfidence,
                    feedbackDetails = recovery.feedback.details,
                    trainingCanContinue = recovery.feedback.action == ReadingFeedbackAction.CONTINUE)
            }
            FullSpeakingRecovery.TranscriptionPending -> {
                require(state.attemptIndex == 4)
                clean.copy(technicalRetryPending = true, captureFailed = true)
            }
            is FullSpeakingRecovery.TranscriptReady -> {
                require(state.attemptIndex == 4)
                val originalId = recovery.recordingId?.takeIf { it.isNotBlank() }
                transcriptRecordingId = originalId
                val ownsEdit = originalId != null && savedRecordingId == originalId
                clean.copy(transcript = if (ownsEdit) state.transcript else recovery.value.transcript,
                    lowConfidenceSegments = recovery.value.lowConfidenceSegments,
                    transcriptReadyForConfirmation = true)
            }
            is FullSpeakingRecovery.EvaluationPending -> {
                require(state.attemptIndex == 4)
                transcriptRecordingId = repository.currentRecordingId()?.takeIf { it.isNotBlank() }
                clean.copy(transcript = recovery.confirmedTranscript, answerAudioPlayed = recovery.answerAudioPlayed,
                    transcriptReadyForConfirmation = true, technicalRetryPending = true, captureFailed = true)
            }
            is FullSpeakingRecovery.EvaluationReady -> {
                require(state.attemptIndex == 4)
                transcriptRecordingId = repository.currentRecordingId()?.takeIf { it.isNotBlank() }
                pendingCandidate = recovery.candidate
                val sameDeliveredResult = previousCandidate == recovery.candidate &&
                    state.transcript == recovery.confirmedTranscript && state.answerAudioPlayed == recovery.answerAudioPlayed
                clean.copy(transcript = recovery.confirmedTranscript, answerAudioPlayed = recovery.answerAudioPlayed,
                    transcriptReadyForConfirmation = true, evaluationReceived = true, feedbackZh = recovery.candidate.feedbackZh,
                    finalDecision = state.finalDecision.takeIf { sameDeliveredResult })
            }
        }
    }

    private suspend fun saveProgress(target: FullSentenceSpeakingUiState? = null, publishTarget: Boolean = false): Boolean {
        val store = progressStore
        if (store == null) {
            if (publishTarget && target != null) mutableUiState.value = target.copy(submitting = true, progressFailed = false)
            return true
        }
        if (!progressReady) return false
        return progressMutex.withLock {
            val snapshot = encodeProgress(target ?: mutableUiState.value)
            val saved = if (snapshot == lastSavedProgress) true else try { store.write(snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (saved) lastSavedProgress = snapshot
            if (saved && publishTarget && target != null) mutableUiState.value = target.copy(submitting = true, progressFailed = false)
            else mutableUiState.update { it.copy(progressFailed = !saved) }
            saved
        }
    }

    private companion object {
        // About two minutes of automatic recovery without asking the learner to tap twice.
        val AUTOMATIC_RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000, 30_000, 30_000)
    }
}
