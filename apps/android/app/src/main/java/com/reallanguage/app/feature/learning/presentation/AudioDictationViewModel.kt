package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedWritingComparator
import com.reallanguage.app.feature.learning.domain.FixedWritingResult
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionRepository
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionResult
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

class AudioDictationViewModel(
    chapter: FixedLearningChapter,
    currentFocusSentenceId: String,
    lockedHintItems: List<String>,
    initialInput: String = "",
    private val comparator: FixedWritingComparator,
    private val completionRepository: AudioDictationCompletionRepository,
    private val progressStore: com.reallanguage.app.feature.learning.domain.LearningProgressStore? = null,
) : ViewModel() {
    private val expectedSentence = chapter.sentences.first {
        it.sentenceId == currentFocusSentenceId
    }.textEn
    private val mutableUiState = MutableStateFlow(
        AudioDictationUiState(
            chapter = chapter,
            currentFocusSentenceId = currentFocusSentenceId,
            lockedHintItems = lockedHintItems,
            input = initialInput,
        ),
    )
    val uiState: StateFlow<AudioDictationUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<AudioDictationEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var progressReady = progressStore == null
    private val progressMutex = Mutex()
    private var lastSavedProgress: Map<String, String>? = null

    init { if (progressStore != null) restoreProgress() }

    fun onAction(action: AudioDictationAction) {
        if (action == AudioDictationAction.AudioPlaybackFailed) {
            mutableUiState.update { it.copy(audioLooping = false, audioFailed = true) }
            return
        }
        val current = mutableUiState.value
        if (current.submitting) return
        if (!progressReady) {
            if (action == AudioDictationAction.SubmitRequested) restoreProgress()
            if (action == AudioDictationAction.BackRequested) viewModelScope.launch { effectChannel.send(AudioDictationEffect.ReturnToHandwriting) }
            return
        }
        if (current.correct && action !in setOf(AudioDictationAction.BackRequested, AudioDictationAction.ContinueRequested)) return
        when (action) {
            AudioDictationAction.AudioPlaybackFailed -> Unit
            AudioDictationAction.BackRequested -> send(
                AudioDictationEffect.ReturnToHandwriting,
            )
            AudioDictationAction.AudioToggled -> mutableUiState.update {
                it.copy(audioLooping = !it.audioLooping, audioFailed = false)
            }
            AudioDictationAction.HintToggled -> mutableUiState.update {
                it.copy(hintExpanded = !it.hintExpanded)
            }
            is AudioDictationAction.InputChanged -> mutableUiState.update {
                it.copy(
                    input = action.value,
                    emptySubmission = false,
                    correct = false,
                    incorrect = false,
                    saveFailed = false,
                )
            }
            AudioDictationAction.SubmitRequested -> submit()
            AudioDictationAction.ContinueRequested -> if (current.correct) send(AudioDictationEffect.OpenFullSentenceSpeaking)
        }
    }

    private fun submit() {
        val current = mutableUiState.value
        if (current.submitting) return
        if (current.input.isBlank()) {
            mutableUiState.update { it.copy(emptySubmission = true) }
            return
        }
        mutableUiState.update { it.copy(submitting = true, audioLooping = false) }
        viewModelScope.launch {
        if (!saveProgress()) { mutableUiState.update { it.copy(submitting = false) }; return@launch }
        when (comparator.compare(expectedSentence, current.input)) {
            FixedWritingResult.Correct -> {
                    when (completionRepository.complete(current.currentFocusSentenceId)) {
                        AudioDictationCompletionResult.Saved -> {
                            mutableUiState.update {
                                it.copy(submitting = false, correct = true)
                            }
                            saveProgress()
                        }
                        AudioDictationCompletionResult.Failed -> mutableUiState.update {
                            it.copy(submitting = false, saveFailed = true)
                        }
                    }
            }
            is FixedWritingResult.Incorrect -> {
                mutableUiState.update { it.copy(submitting = false, incorrect = true) }
                if (saveProgress()) effectChannel.send(AudioDictationEffect.OpenReadWriteCorrection(current.input))
            }
        }
        }
    }

    private fun send(effect: AudioDictationEffect) {
        viewModelScope.launch { if (saveProgress()) effectChannel.send(effect) }
    }

    private fun restoreProgress() {
        val store = progressStore ?: return
        mutableUiState.update { it.copy(submitting = true, saveFailed = false, audioLooping = false) }
        viewModelScope.launch {
            try {
                val saved = store.read()
                var restored = mutableUiState.value.copy(audioLooping = true)
                if (saved != null) {
                    require(saved["version"] == "1")
                    restored = restored.copy(input = saved.getValue("input"),
                        audioLooping = saved.getValue("audioLooping").toBooleanStrict(),
                        hintExpanded = saved.getValue("hintExpanded").toBooleanStrict(),
                        correct = saved.getValue("correct").toBooleanStrict(),
                        incorrect = saved.getValue("incorrect").toBooleanStrict())
                    require(!(restored.correct && restored.incorrect))
                    if (restored.correct) require(comparator.compare(expectedSentence, restored.input) == FixedWritingResult.Correct)
                }
                progressReady = true
                lastSavedProgress = saved
                mutableUiState.value = restored.copy(submitting = false, saveFailed = false)
                mutableUiState.map(::encodeProgress).distinctUntilChanged().collect { saveProgress() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableUiState.update { it.copy(submitting = false, saveFailed = true) } }
        }
    }

    private fun encodeProgress(state: AudioDictationUiState) = mapOf(
        "version" to "1", "input" to state.input, "audioLooping" to state.audioLooping.toString(),
        "hintExpanded" to state.hintExpanded.toString(), "correct" to state.correct.toString(), "incorrect" to state.incorrect.toString(),
    )

    private suspend fun saveProgress(): Boolean {
        val store = progressStore ?: return true
        if (!progressReady) return false
        return progressMutex.withLock {
            val snapshot = encodeProgress(mutableUiState.value)
            val saved = if (snapshot == lastSavedProgress) true else try { store.write(snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (saved) lastSavedProgress = snapshot
            mutableUiState.update { it.copy(saveFailed = !saved) }
            saved
        }
    }
}
