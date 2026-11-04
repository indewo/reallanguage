package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.CompleteWritingEvaluator
import com.reallanguage.app.feature.learning.domain.CompleteWritingRepository
import com.reallanguage.app.feature.learning.domain.CompleteWritingSaveResult
import com.reallanguage.app.feature.learning.domain.FinalWritingBatchResult
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedWritingComparator
import com.reallanguage.app.feature.learning.domain.FixedWritingResult
import com.reallanguage.app.feature.learning.domain.LockedWritingTarget
import com.reallanguage.app.feature.learning.domain.WritingTargetEvidenceUnavailable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CompleteWritingViewModel(
    chapter: FixedLearningChapter,
    lockedTargets: List<LockedWritingTarget>,
    private val comparator: FixedWritingComparator,
    private val evaluator: CompleteWritingEvaluator,
    private val repository: CompleteWritingRepository,
    private val progressStore: com.reallanguage.app.feature.learning.domain.LearningProgressStore? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        CompleteWritingUiState(
            chapter = chapter,
            lockedTargets = lockedTargets,
            finalInputs = chapter.sentences.filter { it.isFocus }
                .associate { it.sentenceId to "" },
        ),
    )
    val uiState: StateFlow<CompleteWritingUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<CompleteWritingEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private val progressMutex = Mutex()
    private var progressReady = progressStore == null
    private var lastSavedProgress: Map<String, String>? = null

    init { if (progressStore != null) restoreProgress() }

    fun onAction(action: CompleteWritingAction) {
        val current = mutableUiState.value
        if (action is CompleteWritingAction.AudioPlaybackFinished) {
            if (current.audioPlaying && current.audioRequestId == action.requestId) {
                mutableUiState.update { it.copy(
                    audioPlaying = false,
                    audioSentenceId = null,
                    audioFailed = !action.successful,
                ) }
            }
            return
        }
        if (current.saving) return
        if (!progressReady) {
            if (action == CompleteWritingAction.SubmitRequested) restoreProgress()
            if (action == CompleteWritingAction.BackRequested) viewModelScope.launch { effectChannel.send(CompleteWritingEffect.ReturnToPatternSpeaking) }
            return
        }
        if (current.finalResult != null && action != CompleteWritingAction.ContinueRequested &&
            action != CompleteWritingAction.BackRequested
        ) return
        when (action) {
            is CompleteWritingAction.AudioPlaybackFinished -> Unit
            CompleteWritingAction.BackRequested -> send(
                CompleteWritingEffect.ReturnToPatternSpeaking,
            )
            CompleteWritingAction.HelpRequested -> mutableUiState.update {
                if (it.phase == CompleteWritingPhase.AUDIBLE_SINGLE) it
                else it.copy(helpUsed = true)
            }
            CompleteWritingAction.AudioToggled -> mutableUiState.update {
                it.copy(
                    audioPlaying = !it.audioPlaying,
                    helpUsed = it.helpUsed || it.phase != CompleteWritingPhase.AUDIBLE_SINGLE,
                    audioSentenceId = if (!it.audioPlaying) it.currentFocusSentenceId else null,
                    audioRequestId = it.audioRequestId + 1,
                    audioFailed = false,
                )
            }
            is CompleteWritingAction.FinalAudioRequested -> mutableUiState.update {
                if (it.phase != CompleteWritingPhase.FINAL_BATCH ||
                    action.sentenceId !in it.finalInputs || it.saving || it.finalResult != null
                ) it else it.copy(
                    helpUsed = true,
                    audioPlaying = true,
                    audioSentenceId = action.sentenceId,
                    audioRequestId = it.audioRequestId + 1,
                    audioFailed = false,
                )
            }
            is CompleteWritingAction.InputChanged -> mutableUiState.update {
                if (it.phase == CompleteWritingPhase.FINAL_BATCH) it else it.copy(
                    input = action.value,
                    emptySubmission = false,
                    currentAttemptCorrect = null,
                )
            }
            is CompleteWritingAction.FinalInputChanged -> mutableUiState.update {
                if (it.phase != CompleteWritingPhase.FINAL_BATCH ||
                    action.sentenceId !in it.finalInputs
                ) it else it.copy(
                    finalInputs = it.finalInputs + (action.sentenceId to action.value),
                    emptySubmission = false,
                    finalResult = null,
                    saveFailed = false,
                    evaluationUnavailable = false,
                )
            }
            CompleteWritingAction.SubmitRequested -> submit()
            CompleteWritingAction.RetryCurrentRequested -> mutableUiState.update {
                it.copy(input = "", emptySubmission = false, currentAttemptCorrect = null)
            }
            CompleteWritingAction.NextRequested -> advanceTraining()
            CompleteWritingAction.ContinueRequested -> {
                if (mutableUiState.value.finalResult != null) {
                    send(CompleteWritingEffect.OpenSceneResponse)
                }
            }
        }
    }

    private fun submit() {
        val current = mutableUiState.value
        if (current.phase == CompleteWritingPhase.FINAL_BATCH) {
            if (current.finalInputs.values.any { it.isBlank() }) {
                mutableUiState.update { it.copy(emptySubmission = true) }
                return
            }
            val result = try { evaluator.evaluate(
                current.focusSentences,
                current.finalInputs,
                current.lockedTargets,
                current.helpUsed,
            ) } catch (_: WritingTargetEvidenceUnavailable) {
                mutableUiState.update { it.copy(evaluationUnavailable = true, saveFailed = false, saving = false, audioPlaying = false) }
                return
            }
            mutableUiState.update {
                it.copy(saving = true, saveFailed = false, evaluationUnavailable = false, audioPlaying = false)
            }
            viewModelScope.launch {
                if (!saveProgress()) {
                    mutableUiState.update { it.copy(saving = false) }
                    return@launch
                }
                when (repository.saveFinalBatch(current.chapter.chapterId, result)) {
                    CompleteWritingSaveResult.Saved -> mutableUiState.update {
                        it.copy(saving = false, finalResult = result)
                    }
                    CompleteWritingSaveResult.Failed -> mutableUiState.update {
                        it.copy(saving = false, saveFailed = true)
                    }
                }
            }
            return
        }
        val sentence = current.currentSentence ?: return
        if (current.input.isBlank()) {
            mutableUiState.update { it.copy(emptySubmission = true) }
            return
        }
        val correct = comparator.compare(sentence.textEn, current.input) ==
            FixedWritingResult.Correct
        mutableUiState.update {
            it.copy(currentAttemptCorrect = correct, audioPlaying = false)
        }
    }

    private fun advanceTraining() {
        val current = mutableUiState.value
        if (!current.attemptFinished) return
        when (current.phase) {
            CompleteWritingPhase.AUDIBLE_SINGLE -> mutableUiState.update {
                it.copy(
                    phase = CompleteWritingPhase.SILENT_SINGLE,
                    input = "",
                    helpUsed = false,
                    audioPlaying = false,
                    currentAttemptCorrect = null,
                    audioSentenceId = null,
                    audioRequestId = it.audioRequestId + 1,
                    audioFailed = false,
                )
            }
            CompleteWritingPhase.SILENT_SINGLE -> {
                if (current.sentenceIndex + 1 < current.focusSentences.size) {
                    mutableUiState.update {
                        it.copy(
                            phase = CompleteWritingPhase.AUDIBLE_SINGLE,
                            sentenceIndex = it.sentenceIndex + 1,
                            input = "",
                            helpUsed = false,
                            audioPlaying = true,
                            currentAttemptCorrect = null,
                            audioSentenceId = it.focusSentences[it.sentenceIndex + 1].sentenceId,
                            audioRequestId = it.audioRequestId + 1,
                            audioFailed = false,
                        )
                    }
                } else {
                    mutableUiState.update {
                        it.copy(
                            phase = CompleteWritingPhase.FINAL_BATCH,
                            sentenceIndex = 0,
                            input = "",
                            helpUsed = false,
                            audioPlaying = false,
                            currentAttemptCorrect = null,
                            audioSentenceId = null,
                            audioRequestId = it.audioRequestId + 1,
                            audioFailed = false,
                        )
                    }
                }
            }
            CompleteWritingPhase.FINAL_BATCH -> Unit
        }
    }

    private fun send(effect: CompleteWritingEffect) {
        viewModelScope.launch { if (saveProgress()) effectChannel.send(effect) }
    }

    private fun restoreProgress() {
        val store = progressStore ?: return
        mutableUiState.update { it.copy(
            saving = true,
            saveFailed = false,
            audioPlaying = false,
            audioSentenceId = null,
            audioFailed = false,
            audioRequestId = it.audioRequestId + 1,
        ) }
        viewModelScope.launch {
            try {
                val saved = store.read()
                var restored = saved?.let { decodeProgress(it, mutableUiState.value) } ?: mutableUiState.value
                val accepted = repository.loadFinalBatch(restored.chapter.chapterId)
                if (accepted != null) restored = restored.copy(phase = CompleteWritingPhase.FINAL_BATCH, finalResult = accepted, sentenceIndex = 0, helpUsed = accepted == FinalWritingBatchResult.SupportedLearningOnly)
                progressReady = true
                lastSavedProgress = saved
                mutableUiState.value = restored.copy(saving = false, saveFailed = false, audioPlaying = false, audioSentenceId = null)
                // Observe only durable fields, not status flags, so a storage failure never creates a retry loop.
                mutableUiState.map(::encodeProgress).distinctUntilChanged().collect { saveProgress() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableUiState.update { it.copy(saving = false, saveFailed = true) } }
        }
    }

    private suspend fun saveProgress(): Boolean {
        val store = progressStore ?: return true
        if (!progressReady) return false
        return progressMutex.withLock {
            // Read the latest state inside the lock: an older queued keystroke must never overwrite a newer draft.
            val snapshot = encodeProgress(mutableUiState.value)
            val saved = if (snapshot == lastSavedProgress) true else try { store.write(snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (saved) lastSavedProgress = snapshot
            mutableUiState.update { it.copy(saveFailed = !saved) }
            saved
        }
    }

    private fun encodeProgress(state: CompleteWritingUiState): Map<String, String> = mapOf(
        "version" to "1", "phase" to state.phase.name, "sentenceIndex" to state.sentenceIndex.toString(),
        "input" to state.input, "helpUsed" to state.helpUsed.toString(),
        "attemptCorrect" to (state.currentAttemptCorrect?.toString() ?: ""),
    ) + state.finalInputs.mapKeys { "answer:${it.key}" }

    private fun decodeProgress(saved: Map<String, String>, state: CompleteWritingUiState): CompleteWritingUiState {
        require(saved["version"] == "1")
        val phase = CompleteWritingPhase.valueOf(saved.getValue("phase"))
        val index = saved.getValue("sentenceIndex").toInt()
        require(index in state.focusSentences.indices)
        val correct = saved.getValue("attemptCorrect").let { if (it.isEmpty()) null else it.toBooleanStrict() }
        val inputs = state.focusSentences.associate { it.sentenceId to saved.getValue("answer:${it.sentenceId}") }
        require(saved.keys.filter { it.startsWith("answer:") }.size == inputs.size)
        return state.copy(phase = phase, sentenceIndex = index, input = saved.getValue("input"),
            helpUsed = saved.getValue("helpUsed").toBooleanStrict(), currentAttemptCorrect = correct, finalInputs = inputs)
    }
}
