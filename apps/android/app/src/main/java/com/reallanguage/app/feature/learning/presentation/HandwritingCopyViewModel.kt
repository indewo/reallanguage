package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.HandwritingCompletionRepository
import com.reallanguage.app.feature.learning.domain.HandwritingCompletionResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class HandwritingCopyViewModel(
    chapter: FixedLearningChapter,
    currentFocusSentenceId: String,
    private val repository: HandwritingCompletionRepository,
    private val progressStore: com.reallanguage.app.feature.learning.domain.LearningProgressStore? = null,
) : ViewModel() {
    private val sentenceId = currentFocusSentenceId
    private val mutableUiState = MutableStateFlow(
        HandwritingCopyUiState(chapter, currentFocusSentenceId),
    )
    val uiState: StateFlow<HandwritingCopyUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<HandwritingCopyEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var progressReady = progressStore == null

    init { if (progressStore != null) restoreProgress() }

    fun onAction(action: HandwritingCopyAction) {
        if (mutableUiState.value.saving) return
        if (!progressReady) {
            if (action == HandwritingCopyAction.WrittenFinished) restoreProgress()
            if (action == HandwritingCopyAction.BackRequested) send(HandwritingCopyEffect.ReturnToReadSpeak)
            return
        }
        when (action) {
            HandwritingCopyAction.BackRequested -> send(
                HandwritingCopyEffect.ReturnToReadSpeak,
            )
            HandwritingCopyAction.WrittenFinished -> finishCopy()
            HandwritingCopyAction.CopyAgainRequested -> copyAgain()
            HandwritingCopyAction.ContinueRequested -> continueToDictation()
        }
    }

    private fun finishCopy() {
        val current = mutableUiState.value
        if (!current.copyInProgress || current.saving) return
        val count = current.confirmedCopyCount + 1
        commit(current.copy(
                confirmedCopyCount = count,
                copyInProgress = count < 2,
                canvasRevision = current.canvasRevision + 1,
            ))
    }

    private fun copyAgain() {
        val current = mutableUiState.value
        if (!current.minimumCompleted || current.copyInProgress || current.saving) return
        commit(current.copy(copyInProgress = true, canvasRevision = current.canvasRevision + 1))
    }

    private fun continueToDictation() {
        val current = mutableUiState.value
        if (!current.minimumCompleted || current.copyInProgress || current.saving) return
        mutableUiState.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            when (repository.complete(sentenceId, current.confirmedCopyCount)) {
                HandwritingCompletionResult.Saved -> {
                    mutableUiState.update { it.copy(saving = false) }
                    effectChannel.send(HandwritingCopyEffect.OpenAudioDictation)
                }
                HandwritingCompletionResult.Failed -> mutableUiState.update {
                    it.copy(saving = false, saveFailed = true)
                }
            }
        }
    }

    private fun send(effect: HandwritingCopyEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }

    private fun restoreProgress() {
        val store = progressStore ?: return
        mutableUiState.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                val saved = store.read()
                var restored = mutableUiState.value
                if (saved != null) {
                    require(saved["version"] == "1")
                    val count = saved.getValue("confirmedCopyCount").toInt()
                    val active = saved.getValue("copyInProgress").toBooleanStrict()
                    require(count >= 0 && (count >= 2 || active))
                    restored = restored.copy(confirmedCopyCount = count, copyInProgress = active)
                }
                progressReady = true
                mutableUiState.value = restored.copy(saving = false, saveFailed = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableUiState.update { it.copy(saving = false, saveFailed = true) } }
        }
    }

    private fun commit(next: HandwritingCopyUiState) {
        val store = progressStore
        if (store == null) { mutableUiState.value = next; return }
        mutableUiState.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val saved = try { store.write(mapOf("version" to "1", "confirmedCopyCount" to next.confirmedCopyCount.toString(), "copyInProgress" to next.copyInProgress.toString())) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (saved) mutableUiState.value = next.copy(saving = false, saveFailed = false)
            else mutableUiState.update { it.copy(saving = false, saveFailed = true) }
        }
    }
}
