package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.ChapterArchiveRepository
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChapterResultViewModel(
    chapterId: String,
    commandId: String,
    private val repository: ChapterArchiveRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        ChapterResultUiState(chapterId = chapterId, commandId = commandId),
    )
    val uiState: StateFlow<ChapterResultUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ChapterResultEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var navigationSent = false

    init {
        archive()
    }

    fun onAction(action: ChapterResultAction) {
        when (action) {
            ChapterResultAction.RetryArchiveRequested -> archive()
            ChapterResultAction.EnterNextChapterRequested -> {
                if (mutableUiState.value.summary?.hasNextChapter == true) {
                    send(ChapterResultEffect.OpenChapterSelection)
                }
            }
            ChapterResultAction.LearnLaterRequested,
            ChapterResultAction.ReturnHomeRequested,
            -> if (mutableUiState.value.summary != null) {
                send(ChapterResultEffect.ReturnHome)
            }
        }
    }

    private fun archive() {
        if (navigationSent || mutableUiState.value.archiving || mutableUiState.value.summary != null) return
        mutableUiState.update { it.copy(archiving = true, archiveFailed = false) }
        viewModelScope.launch {
            val result = try {
                repository.archive(
                    mutableUiState.value.chapterId,
                    mutableUiState.value.commandId,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ChapterArchiveResult.Failed }
            when (result) {
                is ChapterArchiveResult.Archived -> showSummary(result.summary)
                is ChapterArchiveResult.AlreadyArchived -> showSummary(result.summary)
                ChapterArchiveResult.Failed -> mutableUiState.update {
                    it.copy(archiving = false, archiveFailed = true)
                }
            }
        }
    }

    private fun showSummary(summary: com.reallanguage.app.feature.learning.domain.ChapterArchiveSummary) {
        if (summary.chapterId != mutableUiState.value.chapterId || summary.items.any {
                it.completedCount < 0 || it.totalCount < 0 || it.completedCount > it.totalCount
            }) {
            mutableUiState.update { it.copy(archiving = false, archiveFailed = true, summary = null) }
            return
        }
        mutableUiState.update {
            it.copy(archiving = false, archiveFailed = false, summary = summary, chapterNumber = summary.chapterNumber)
        }
    }

    private fun send(effect: ChapterResultEffect) {
        if (navigationSent) return
        navigationSent = true
        viewModelScope.launch { effectChannel.send(effect) }
    }
}
