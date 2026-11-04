package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionRepository
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionResult
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ReadWriteCorrectionViewModel(
    chapter: FixedLearningChapter,
    currentFocusSentenceId: String,
    lockedHintItems: List<String>,
    private val completionRepository: AudioDictationCompletionRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        ReadWriteCorrectionUiState(chapter, currentFocusSentenceId, lockedHintItems),
    )
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ReadWriteCorrectionEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var navigationSent = false

    fun onAction(action: ReadWriteCorrectionAction) {
        if (navigationSent) return
        if (mutableUiState.value.saving && action != ReadWriteCorrectionAction.AudioPlaybackFailed) return
        when (action) {
            ReadWriteCorrectionAction.AudioPlaybackFailed -> mutableUiState.update {
                it.copy(audioLooping = false, audioFailed = true)
            }
            ReadWriteCorrectionAction.BackRequested -> send(
                ReadWriteCorrectionEffect.ReturnToDictation,
            )
            ReadWriteCorrectionAction.AudioToggled -> mutableUiState.update {
                it.copy(audioLooping = !it.audioLooping, audioFailed = false)
            }
            ReadWriteCorrectionAction.WrittenFinished -> finish()
        }
    }

    private fun finish() {
        val current = mutableUiState.value
        if (current.saving) return
        mutableUiState.update { it.copy(saving = true, saveFailed = false, audioLooping = false) }
        viewModelScope.launch {
            val result = try { completionRepository.complete(current.currentFocusSentenceId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { AudioDictationCompletionResult.Failed }
            when (result) {
                AudioDictationCompletionResult.Saved -> {
                    mutableUiState.update { it.copy(saving = false) }
                    send(ReadWriteCorrectionEffect.OpenFullSentenceSpeaking)
                }
                AudioDictationCompletionResult.Failed -> mutableUiState.update {
                    it.copy(saving = false, saveFailed = true)
                }
            }
        }
    }

    private fun send(effect: ReadWriteCorrectionEffect) {
        if (navigationSent) return
        navigationSent = true
        mutableUiState.update { it.copy(audioLooping = false) }
        viewModelScope.launch { effectChannel.send(effect) }
    }
}
