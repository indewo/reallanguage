package com.reallanguage.app.feature.review.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.review.domain.ReviewExitRepository
import com.reallanguage.app.feature.review.domain.ReviewExitResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class ReviewExitViewModel(
    reviewChapterId: String,
    completedChapterCount: Int,
    private val repository: ReviewExitRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        ReviewExitUiState(reviewChapterId, completedChapterCount),
    )
    val uiState: StateFlow<ReviewExitUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ReviewExitEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init {
        if (completedChapterCount < 0) viewModelScope.launch {
            val count = try { repository.completedChapterCount(reviewChapterId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { -1 }
            mutableUiState.update { it.copy(completedChapterCount = count) }
        }
    }

    fun onAction(action: ReviewExitAction) {
        if (mutableUiState.value.discarding || mutableUiState.value.completed) return
        when (action) {
            ReviewExitAction.ContinueReviewRequested -> send(ReviewExitEffect.ContinueReview)
            ReviewExitAction.ConfirmExitRequested -> discardAndExit()
        }
    }

    private fun discardAndExit() {
        if (mutableUiState.value.discarding || mutableUiState.value.completed) return
        mutableUiState.update { it.copy(discarding = true, discardFailed = false) }
        viewModelScope.launch {
            val result = try { repository.discardWorkingState(mutableUiState.value.reviewChapterId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ReviewExitResult.Failed }
            when (result) {
                ReviewExitResult.Discarded,
                ReviewExitResult.AlreadyDiscarded,
                -> {
                    mutableUiState.update { it.copy(discarding = false, completed = true) }
                    send(ReviewExitEffect.ReturnHome)
                }
                ReviewExitResult.Failed -> mutableUiState.update {
                    it.copy(discarding = false, discardFailed = true)
                }
            }
        }
    }

    private fun send(effect: ReviewExitEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }
}
