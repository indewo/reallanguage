package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SceneEvaluationResult
import com.reallanguage.app.feature.learning.domain.SceneResponseRepository
import com.reallanguage.app.feature.learning.domain.SceneResponseSaveResult
import com.reallanguage.app.feature.learning.domain.SceneTaskGenerationResult
import com.reallanguage.app.feature.learning.domain.SceneTrainingFeedbackResult
import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import com.reallanguage.app.feature.learning.domain.LearningProgressStore
import com.reallanguage.app.feature.learning.domain.SceneEvaluationCandidate
import com.reallanguage.app.feature.learning.domain.SceneResponseRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

class SceneResponseViewModel(
    chapter: FixedLearningChapter,
    tasks: List<FixedSceneResponseTask>?,
    private val repository: SceneResponseRepository,
    private val progressStore: LearningProgressStore? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        SceneResponseUiState(
            chapter = chapter,
            tasks = tasks.orEmpty(),
            loading = tasks == null,
        ),
    )
    val uiState: StateFlow<SceneResponseUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<SceneResponseEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private val suppliedTasks = tasks
    private var progressReady = progressStore == null
    private val progressMutex = Mutex()
    private var lastSavedProgress: Map<String, String>? = null
    private var pendingCandidate: SceneEvaluationCandidate? = null
    private var transcriptRecordingId: String? = null
    private var progressObserver: Job? = null
    private data class PendingTransition(val next: SceneResponseUiState, val after: () -> Unit)
    private var pendingTransition: PendingTransition? = null

    init {
        if (tasks?.isEmpty() == true) viewModelScope.launch { effectChannel.send(SceneResponseEffect.OpenChapterResult) }
        else if (progressStore != null) restoreProgress()
        else if (tasks == null) loadTasks(chapter)
    }

    fun interruptUnsubmittedCapture() {
        val current = mutableUiState.value
        if (!current.recording || current.transcribing || current.submitting) return
        repository.cancelCapture()
        mutableUiState.update { it.copy(recording = false, captureFailed = true) }
    }

    fun onAction(action: SceneResponseAction) {
        if (mutableUiState.value.submitting || mutableUiState.value.transcribing || mutableUiState.value.loading) return
        if (!progressReady) {
            if (action == SceneResponseAction.RetryRequested) restoreProgress()
            else if (action == SceneResponseAction.BackRequested) viewModelScope.launch { effectChannel.send(SceneResponseEffect.ReturnToCompleteWriting) }
            return
        }
        pendingTransition?.let { pending ->
            val retriesTrainingRound = action == SceneResponseAction.ContinueRoundRequested &&
                pending.next.taskIndex == mutableUiState.value.taskIndex &&
                pending.next.roundIndex == mutableUiState.value.roundIndex + 1
            if (action == SceneResponseAction.RetryRequested || retriesTrainingRound) persistTransition(pending)
            return
        }
        if (mutableUiState.value.completed && action != SceneResponseAction.BackRequested && action != SceneResponseAction.RetryRequested) return
        when (action) {
            SceneResponseAction.BackRequested -> {
                repository.cancelCapture()
                send(SceneResponseEffect.ReturnToCompleteWriting)
            }
            SceneResponseAction.AudioToggled -> mutableUiState.update {
                it.copy(audioMuted = !it.audioMuted)
            }
            SceneResponseAction.StartRequested -> startCapture()
            SceneResponseAction.FinishedRequested -> finishResponse()
            is SceneResponseAction.TranscriptChanged -> mutableUiState.update {
                if (!it.transcriptReady || it.technicalRetryPending || it.technicalRetryBlocked) it
                else it.copy(transcript = action.value)
            }
            SceneResponseAction.ConfirmTranscriptRequested -> submitConfirmedTranscript()
            SceneResponseAction.ContinueRoundRequested -> continueRound()
            SceneResponseAction.RetryRequested -> {
                val current = mutableUiState.value
                if (current.progressFailed && !current.saveFailed) {
                    viewModelScope.launch { if (saveProgress() && current.completed) effectChannel.send(SceneResponseEffect.OpenChapterResult) }
                } else if (current.saveFailed && current.finalDecision == SceneEvaluationDecision.PASS) {
                    val task = current.currentTask ?: return
                    mutableUiState.update { it.copy(submitting = true, saveFailed = false) }
                    viewModelScope.launch { savePassAndAdvance(task) }
                } else if (current.saveFailed && current.finalDecision == SceneEvaluationDecision.FAIL) {
                    saveFailureAndContinue()
                } else if (current.technicalRetryBlocked) {
                    return
                } else if (current.technicalRetryPending) {
                    retryOriginalAttempt()
                } else if (current.tasks.isEmpty()) {
                    mutableUiState.update { it.copy(loading = true, captureFailed = false) }
                    loadTasks(mutableUiState.value.chapter)
                } else {
                    resetCurrentRound()
                }
            }
            SceneResponseAction.StopPractisingRequested -> {
                if (mutableUiState.value.finalDecision == SceneEvaluationDecision.FAIL) {
                    saveFailureAndContinue()
                }
            }
        }
    }

    private fun loadTasks(chapter: FixedLearningChapter) {
        viewModelScope.launch {
            when (val result = repository.generateTasks(chapter)) {
                is SceneTaskGenerationResult.Ready -> {
                    mutableUiState.update {
                        it.copy(tasks = result.tasks, loading = false, captureFailed = false)
                    }
                    if (result.tasks.isEmpty()) send(SceneResponseEffect.OpenChapterResult)
                }
                SceneTaskGenerationResult.Failed -> mutableUiState.update {
                    it.copy(loading = false, captureFailed = true)
                }
            }
        }
    }

    private fun startCapture() {
        val current = mutableUiState.value
        if (current.technicalRetryPending) { retryOriginalAttempt(); return }
        if (current.loading || current.recording || current.submitting || current.transcribing ||
            current.transcriptReady || current.finalDecision != null || current.trainingRoundCompleted
        ) return
        if (repository.requiresAttemptRecovery) {
            val task = current.currentTask ?: return
            mutableUiState.update { it.copy(submitting = true) }
            viewModelScope.launch {
                val prepared = try {
                    saveProgress() && repository.prepareCapture(current.chapter, task, current.roundIndex)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                currentCoroutineContext().ensureActive()
                mutableUiState.update { it.copy(submitting = false) }
                if (!prepared) mutableUiState.update { it.copy(progressFailed = true, captureFailed = true) }
                else beginCapture()
            }
            return
        }
        beginCapture()
    }

    private fun beginCapture() {
        if (!repository.startCapture()) {
            mutableUiState.update { it.copy(captureFailed = true) }
            return
        }
        transcriptRecordingId = repository.currentRecordingId()
        mutableUiState.update {
            it.copy(recording = true, captureFailed = false, feedbackZh = null, needsNewRecording = false,
                transcript = "", transcriptReady = false, technicalRetryPending = false, technicalRetryBlocked = false)
        }
    }

    private fun retryOriginalAttempt() {
        if (repository.hasPendingTranscriptionRetry()) finishResponse(retry = true)
        else if (repository.hasPendingEvaluationRetry()) submitConfirmedTranscript(retry = true)
    }

    private fun finishResponse(retry: Boolean = false) {
        val current = mutableUiState.value
        val task = current.currentTask ?: return
        if ((!current.recording && !(retry && current.technicalRetryPending)) || current.submitting || current.transcribing) return
        mutableUiState.update { it.copy(recording = false, transcribing = true, captureFailed = false) }
        viewModelScope.launch {
            when (val result = repository.transcribeResponse(task.taskId)) {
                is SpeakingTranscriptionResult.Ready -> {
                    transcriptRecordingId = repository.currentRecordingId()
                    mutableUiState.update {
                    it.copy(
                        transcribing = false,
                        transcript = result.value.transcript,
                        transcriptReady = true,
                        captureFailed = false,
                        technicalRetryPending = false,
                        technicalRetryBlocked = false,
                    )
                    }
                }
                SpeakingTranscriptionResult.Failed -> mutableUiState.update {
                    it.copy(transcribing = false, captureFailed = true,
                        technicalRetryPending = repository.hasPendingTranscriptionRetry(),
                        technicalRetryBlocked = repository.technicalRetryBlocked())
                }
            }
        }
    }

    private fun submitConfirmedTranscript(retry: Boolean = false) {
        val current = mutableUiState.value
        val task = current.currentTask ?: return
        if ((!current.transcriptReady && !(retry && current.technicalRetryPending)) || current.transcript.isBlank() || current.submitting) {
            if (current.transcriptReady && current.transcript.isBlank()) {
                mutableUiState.update { it.copy(captureFailed = true) }
            }
            return
        }
        mutableUiState.update { it.copy(submitting = true, transcriptReady = false, captureFailed = false) }
        viewModelScope.launch {
            if (!current.isFinalRound) {
                when (
                    val result = repository.trainingFeedback(
                        task.taskId,
                        current.roundIndex,
                        current.transcript.trim(),
                    )
                ) {
                    is SceneTrainingFeedbackResult.Ready -> mutableUiState.update {
                        it.copy(
                            submitting = false,
                            feedbackZh = result.feedbackZh,
                            trainingRoundCompleted = true,
                            technicalRetryPending = false,
                            technicalRetryBlocked = false,
                        )
                    }
                    SceneTrainingFeedbackResult.Inconclusive,
                    SceneTrainingFeedbackResult.Failed,
                    -> mutableUiState.update {
                        it.copy(submitting = false, captureFailed = true,
                            technicalRetryPending = repository.hasPendingEvaluationRetry(),
                            technicalRetryBlocked = repository.technicalRetryBlocked())
                    }
                }
                return@launch
            }
            when (
                val result = repository.evaluateFinalRound(
                    task.taskId,
                    current.transcript.trim(),
                )
            ) {
                is SceneEvaluationResult.Ready -> {
                    val candidate = result.candidate
                    pendingCandidate = candidate
                    val decision = when {
                        candidate.decision == SceneEvaluationDecision.INCONCLUSIVE -> {
                            SceneEvaluationDecision.INCONCLUSIVE
                        }
                        candidate.decision == SceneEvaluationDecision.PASS &&
                            candidate.correctTurn &&
                            candidate.targetSentenceComplete &&
                            candidate.pronunciationIntelligible -> SceneEvaluationDecision.PASS
                        else -> SceneEvaluationDecision.FAIL
                    }
                    mutableUiState.update {
                        it.copy(
                            submitting = decision == SceneEvaluationDecision.PASS,
                            feedbackZh = candidate.feedbackZh,
                            finalDecision = decision,
                            technicalRetryPending = false,
                            technicalRetryBlocked = false,
                        )
                    }
                    if (decision == SceneEvaluationDecision.PASS) savePassAndAdvance(task)
                }
                SceneEvaluationResult.Failed -> mutableUiState.update {
                    it.copy(submitting = false, captureFailed = true,
                        technicalRetryPending = repository.hasPendingEvaluationRetry(),
                        technicalRetryBlocked = repository.technicalRetryBlocked())
                }
            }
        }
    }

    private suspend fun savePassAndAdvance(task: FixedSceneResponseTask) {
        if (!saveProgress()) { mutableUiState.update { it.copy(submitting = false, saveFailed = true) }; return }
        when (
            repository.saveFinalDecision(
                mutableUiState.value.chapter.chapterId,
                task.sentenceId,
                SceneEvaluationDecision.PASS,
            )
        ) {
            SceneResponseSaveResult.Saved -> advanceTask()
            SceneResponseSaveResult.Failed -> mutableUiState.update {
                it.copy(saveFailed = true, submitting = false)
            }
        }
    }

    private fun saveFailureAndContinue() {
        val current = mutableUiState.value
        val task = current.currentTask ?: return
        mutableUiState.update { it.copy(submitting = true, saveFailed = false) }
        viewModelScope.launch {
            if (!saveProgress()) { mutableUiState.update { it.copy(submitting = false, saveFailed = true) }; return@launch }
            when (
                repository.saveFinalDecision(
                    current.chapter.chapterId,
                    task.sentenceId,
                    SceneEvaluationDecision.FAIL,
                )
            ) {
                SceneResponseSaveResult.Saved -> advanceTask(
                    SceneResponseEffect.ArchiveWrongAndContinue,
                )
                SceneResponseSaveResult.Failed -> mutableUiState.update {
                    it.copy(saveFailed = true, submitting = false)
                }
            }
        }
    }

    private fun continueRound() {
        val current = mutableUiState.value
        if (!current.trainingRoundCompleted || current.isFinalRound) return
        transition(current.copy(
                roundIndex = current.roundIndex + 1,
                recording = false,
                transcript = "",
                transcriptReady = false,
                feedbackZh = null,
                trainingRoundCompleted = false,
                captureFailed = false,
                technicalRetryPending = false,
                technicalRetryBlocked = false,
            ))
    }

    private fun resetCurrentRound() {
        repository.cancelCapture()
        pendingCandidate = null
        transcriptRecordingId = null
        mutableUiState.update {
            it.copy(
                recording = false,
                transcript = "",
                feedbackZh = null,
                trainingRoundCompleted = false,
                finalDecision = null,
                captureFailed = false,
                transcribing = false,
                transcriptReady = false,
                saveFailed = false,
                technicalRetryPending = false,
                technicalRetryBlocked = false,
            )
        }
    }

    private fun advanceTask(finalEffect: SceneResponseEffect = SceneResponseEffect.OpenChapterResult) {
        val current = mutableUiState.value
        if (current.taskIndex + 1 >= current.tasks.size) {
            transition(current.copy(submitting = false, saveFailed = false, completed = true)) {
                viewModelScope.launch { effectChannel.send(finalEffect) }
            }
        } else {
            transition(
                current.copy(
                    taskIndex = current.taskIndex + 1,
                    roundIndex = 0,
                    recording = false,
                    transcript = "",
                    feedbackZh = null,
                    trainingRoundCompleted = false,
                    finalDecision = null,
                    captureFailed = false,
                    transcribing = false,
                    transcriptReady = false,
                    saveFailed = false,
                    submitting = false,
                    technicalRetryPending = false,
                    technicalRetryBlocked = false,
                )
            )
        }
    }

    private fun send(effect: SceneResponseEffect) {
        viewModelScope.launch { if (saveProgress()) effectChannel.send(effect) }
    }

    private fun transition(next: SceneResponseUiState, after: () -> Unit = {}) {
        val pending = PendingTransition(next, after)
        if (progressStore == null) { applyTransition(pending); return }
        pendingTransition = pending
        persistTransition(pending)
    }

    private fun persistTransition(pending: PendingTransition) {
        if (pendingTransition !== pending) return
        mutableUiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            if (saveProgress(pending.next)) {
                pendingTransition = null
                applyTransition(pending)
            }
            else mutableUiState.update { it.copy(submitting = false) }
        }
    }

    private fun applyTransition(pending: PendingTransition) {
        if (pending.next.taskIndex != mutableUiState.value.taskIndex) pendingCandidate = null
        if (repository.requiresAttemptRecovery && !pending.next.completed) {
            transcriptRecordingId = null
            mutableUiState.value = pending.next.copy(loading = true, submitting = false)
            viewModelScope.launch {
                try {
                    val restored = recoverAttempt(pending.next)
                    currentCoroutineContext().ensureActive()
                    mutableUiState.value = restored.copy(loading = false, progressFailed = false, submitting = false)
                    pending.after()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    progressReady = false
                    mutableUiState.update { it.copy(loading = false, progressFailed = true, captureFailed = true) }
                }
            }
            return
        }
        mutableUiState.value = pending.next.copy(progressFailed = false, submitting = false)
        pending.after()
    }

    private fun restoreProgress() {
        val store = progressStore ?: return
        mutableUiState.update { it.copy(loading = true, progressFailed = false, captureFailed = false) }
        viewModelScope.launch {
            try {
                val saved = store.read()
                val chapter = mutableUiState.value.chapter
                val fixed = suppliedTasks ?: when (val result = if (saved == null) repository.generateTasks(chapter) else repository.restoreTasks(chapter)) {
                    is SceneTaskGenerationResult.Ready -> result.tasks
                    SceneTaskGenerationResult.Failed -> error("Unable to load fixed scene tasks")
                }
                val loaded = mutableUiState.value.copy(tasks = fixed, loading = false)
                val restored = saved?.let { restoreSceneProgress(loaded, it) }
                pendingCandidate = restored?.candidate
                // Completed reentry is read-only and does not open the protected
                // audio journal. Preserve its already saved identity nonetheless.
                transcriptRecordingId = saved?.get("recordingId")
                var state = restored?.state ?: loaded
                if (repository.requiresAttemptRecovery && !state.completed && fixed.isNotEmpty()) {
                    // The authorization guard must see the current fixed task/round
                    // saved before it permits reading or creating an audio journal.
                    if (saved == null) check(store.write(encodeSceneProgress(state, pendingCandidate)))
                    state = recoverAttempt(state, saved?.get("recordingId"))
                }
                currentCoroutineContext().ensureActive()
                progressReady = true
                lastSavedProgress = saved
                mutableUiState.value = state
                if (fixed.isEmpty() || restored?.state?.completed == true) effectChannel.send(SceneResponseEffect.OpenChapterResult)
                if (progressObserver == null) progressObserver = viewModelScope.launch {
                    mutableUiState.map { encodeSceneProgress(it, pendingCandidate, transcriptRecordingId) }.distinctUntilChanged().collect { saveProgress() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { progressReady = false; mutableUiState.update { it.copy(loading = false, submitting = false, progressFailed = true, captureFailed = true) } }
        }
    }

    private suspend fun recoverAttempt(state: SceneResponseUiState, savedRecordingId: String? = null): SceneResponseUiState {
        val task = state.currentTask ?: return state
        val recovery = repository.restoreAttempt(state.chapter, task, state.roundIndex)
        currentCoroutineContext().ensureActive()
        if (recovery == SceneResponseRecovery.Unavailable) error("Scene attempt recovery unavailable")
        if (recovery == SceneResponseRecovery.None) return state
        pendingCandidate = null
        transcriptRecordingId = repository.currentRecordingId()
        val clean = state.copy(recording = false, submitting = false, transcribing = false, transcript = "",
            transcriptReady = false, feedbackZh = null, trainingRoundCompleted = false, finalDecision = null,
            captureFailed = false, technicalRetryPending = false, technicalRetryBlocked = false,
            saveFailed = false, needsNewRecording = false)
        return when (recovery) {
            SceneResponseRecovery.None, SceneResponseRecovery.Unavailable -> error("Handled above")
            SceneResponseRecovery.TranscriptionPending -> clean.copy(technicalRetryPending = true, captureFailed = true)
            is SceneResponseRecovery.TranscriptReady -> {
                transcriptRecordingId = recovery.recordingId
                clean.copy(transcript = if (savedRecordingId == recovery.recordingId) state.transcript else recovery.value.transcript,
                    transcriptReady = true)
            }
            is SceneResponseRecovery.FeedbackPending -> clean.copy(transcript = recovery.confirmedTranscript,
                technicalRetryPending = true, captureFailed = true)
            is SceneResponseRecovery.TrainingReady -> {
                require(state.roundIndex < 2)
                when (val result = recovery.result) {
                    is SceneTrainingFeedbackResult.Ready -> clean.copy(transcript = recovery.confirmedTranscript,
                        feedbackZh = result.feedbackZh, trainingRoundCompleted = true)
                    SceneTrainingFeedbackResult.Inconclusive -> clean.copy(captureFailed = true, needsNewRecording = true)
                    SceneTrainingFeedbackResult.Failed -> error("Failed feedback is not delivered")
                }
            }
            is SceneResponseRecovery.EvaluationReady -> {
                require(state.roundIndex == 2)
                pendingCandidate = recovery.candidate
                val candidate = recovery.candidate
                val decision = when {
                    candidate.decision == SceneEvaluationDecision.INCONCLUSIVE -> SceneEvaluationDecision.INCONCLUSIVE
                    candidate.decision == SceneEvaluationDecision.PASS && candidate.correctTurn && candidate.targetSentenceComplete &&
                        candidate.pronunciationIntelligible -> SceneEvaluationDecision.PASS
                    else -> SceneEvaluationDecision.FAIL
                }
                clean.copy(transcript = recovery.confirmedTranscript, feedbackZh = candidate.feedbackZh,
                    finalDecision = decision, saveFailed = decision == SceneEvaluationDecision.PASS)
            }
            SceneResponseRecovery.Blocked -> clean.copy(technicalRetryBlocked = true, captureFailed = true)
        }
    }

    private suspend fun saveProgress(target: SceneResponseUiState? = null): Boolean {
        val store = progressStore ?: return true
        if (!progressReady) return false
        return progressMutex.withLock {
            // Once a decision is saved, only its pending transition may move the durable
            // position forward. An observer of the old UI state must not erase that retry.
            if (target == null && pendingTransition != null) return@withLock false
            val state = target ?: mutableUiState.value
            val candidate = if (state.taskIndex == mutableUiState.value.taskIndex) pendingCandidate else null
            val snapshot = encodeSceneProgress(state, candidate, transcriptRecordingId.takeIf {
                state.taskIndex == mutableUiState.value.taskIndex && state.roundIndex == mutableUiState.value.roundIndex
            })
            val saved = if (snapshot == lastSavedProgress) true else try { store.write(snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (saved) lastSavedProgress = snapshot
            mutableUiState.update { it.copy(progressFailed = !saved) }
            saved
        }
    }

    override fun onCleared() { repository.cancelCapture(); super.onCleared() }
}
