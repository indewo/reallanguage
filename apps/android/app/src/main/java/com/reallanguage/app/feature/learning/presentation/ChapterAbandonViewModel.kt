package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.ChapterAbandonRepository
import com.reallanguage.app.feature.learning.domain.ChapterAbandonResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChapterAbandonViewModel(
    chapterId: String,
    commandId: String,
    private val repository: ChapterAbandonRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        ChapterAbandonUiState(chapterId, commandId),
    )
    val uiState: StateFlow<ChapterAbandonUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ChapterAbandonEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: ChapterAbandonAction) {
        if (mutableUiState.value.deleting || mutableUiState.value.completed) return
        when (action) {
            ChapterAbandonAction.ContinueLearningRequested -> send(
                ChapterAbandonEffect.ReturnToLearning,
            )
            ChapterAbandonAction.ConfirmAbandonRequested -> abandon()
        }
    }

    private fun abandon() {
        if (mutableUiState.value.deleting || mutableUiState.value.alreadyArchived || mutableUiState.value.completed) return
        mutableUiState.update { it.copy(deleting = true, deleteFailed = false) }
        viewModelScope.launch {
            val result = try {
                repository.abandonUnarchivedChapter(
                    mutableUiState.value.chapterId,
                    mutableUiState.value.commandId,
                )
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ChapterAbandonResult.Failed }
            when (result) {
                is ChapterAbandonResult.Abandoned -> {
                    mutableUiState.update { it.copy(deleting = false, completed = true) }
                    send(
                        if (result.hasRemainingChapters) {
                            result.sourceId?.let(ChapterAbandonEffect::OpenChapterSelection)
                                ?: ChapterAbandonEffect.ReturnHome
                        } else {
                            ChapterAbandonEffect.ReturnHome
                        },
                    )
                }
                ChapterAbandonResult.AlreadyArchived -> mutableUiState.update {
                    it.copy(deleting = false, alreadyArchived = true)
                }
                ChapterAbandonResult.Failed -> mutableUiState.update {
                    it.copy(deleting = false, deleteFailed = true)
                }
            }
        }
    }

    private fun send(effect: ChapterAbandonEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }
}
