package com.reallanguage.app.feature.review.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.review.domain.ReviewQueueResult
import com.reallanguage.app.feature.review.domain.TodayReviewRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class TodayReviewViewModel(
    private val repository: TodayReviewRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(TodayReviewUiState(loading = false))
    val uiState: StateFlow<TodayReviewUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<TodayReviewEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init {
        loadQueue()
    }

    fun onAction(action: TodayReviewAction) {
        when (action) {
            TodayReviewAction.StartCurrentChapterRequested -> {
                if (mutableUiState.value.loading || mutableUiState.value.loadFailed) return
                mutableUiState.value.currentChapter?.let {
                    send(TodayReviewEffect.OpenReviewChapter(it.reviewChapterId))
                }
            }
            TodayReviewAction.ReturnHomeRequested -> send(TodayReviewEffect.ReturnHome)
            TodayReviewAction.RetryRequested -> loadQueue()
        }
    }

    private fun loadQueue() {
        if (mutableUiState.value.loading) return
        mutableUiState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            val result = try { repository.loadTodayQueue(mutableUiState.value.dailyFocusSentenceLimit) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ReviewQueueResult.Failed }
            when (result) {
                is ReviewQueueResult.Ready -> mutableUiState.update {
                    it.copy(chapters = result.chapters, loading = false, deferredByDailyLimit = result.deferredByDailyLimit)
                }
                ReviewQueueResult.Failed -> mutableUiState.update {
                    it.copy(chapters = emptyList(), loading = false, loadFailed = true, deferredByDailyLimit = false)
                }
            }
        }
    }

    private fun send(effect: TodayReviewEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }
}
