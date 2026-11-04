package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.ChapterSelectionLoadResult
import com.reallanguage.app.feature.learning.domain.ChapterSelectionRepository
import com.reallanguage.app.feature.learning.domain.ChapterSelectionBoundary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class ChapterSelectionViewModel(
    private val boundary: ChapterSelectionBoundary,
    private val repository: ChapterSelectionRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ChapterSelectionUiState())
    val uiState: StateFlow<ChapterSelectionUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ChapterSelectionEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init {
        load()
    }

    fun onAction(action: ChapterSelectionAction) {
        when (action) {
            is ChapterSelectionAction.ChapterSelected -> {
                if (mutableUiState.value.chapters.any { it.chapterId == action.chapterId }) {
                    send(ChapterSelectionEffect.OpenChapter(action.chapterId))
                }
            }
            ChapterSelectionAction.BackRequested -> send(
                ChapterSelectionEffect.ReturnToPrevious,
            )
            ChapterSelectionAction.LearnLaterRequested -> send(
                ChapterSelectionEffect.ReturnHome,
            )
            ChapterSelectionAction.RetryLoadRequested -> load()
        }
    }

    private fun load() {
        if (mutableUiState.value.loading) return
        viewModelScope.launch {
            mutableUiState.value = mutableUiState.value.copy(loading = true, loadFailed = false)
            when (val result = repository.loadRemaining(boundary)) {
                is ChapterSelectionLoadResult.Ready -> mutableUiState.value = ChapterSelectionUiState(
                    sourceId = result.sourceId,
                    chapters = result.chapters,
                )
                ChapterSelectionLoadResult.Failed -> mutableUiState.value = mutableUiState.value.copy(
                    loading = false,
                    loadFailed = true,
                )
            }
        }
    }

    private fun send(effect: ChapterSelectionEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }
}
