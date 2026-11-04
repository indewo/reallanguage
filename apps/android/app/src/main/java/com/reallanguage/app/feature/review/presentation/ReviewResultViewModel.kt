package com.reallanguage.app.feature.review.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.review.domain.ReviewResultRepository
import com.reallanguage.app.feature.review.domain.ReviewResultSummary
import com.reallanguage.app.feature.review.domain.ReviewSettlementResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class ReviewResultViewModel(
    reviewSessionId: String,
    commandId: String,
    private val repository: ReviewResultRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ReviewResultUiState(reviewSessionId, commandId, settling = false))
    val uiState: StateFlow<ReviewResultUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ReviewResultEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init { settle() }

    fun onAction(action: ReviewResultAction) {
        when (action) {
            ReviewResultAction.RetryRequested -> settle()
            ReviewResultAction.ReturnHomeRequested -> if (mutableUiState.value.summary != null) {
                send(ReviewResultEffect.ReturnHome)
            }
        }
    }

    private fun settle() {
        if (mutableUiState.value.settling || mutableUiState.value.summary != null) return
        mutableUiState.update { it.copy(settling = true, settlementFailed = false) }
        viewModelScope.launch {
            val result = try { repository.settleSession(mutableUiState.value.reviewSessionId, mutableUiState.value.commandId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ReviewSettlementResult.Failed }
            when (result) {
                is ReviewSettlementResult.Settled -> show(result.summary)
                is ReviewSettlementResult.AlreadySettled -> show(result.summary)
                ReviewSettlementResult.Failed -> mutableUiState.update { it.copy(settling = false, settlementFailed = true) }
            }
        }
    }

    private fun show(summary: ReviewResultSummary) {
        mutableUiState.update { it.copy(summary = summary, settling = false, settlementFailed = false) }
    }

    private fun send(effect: ReviewResultEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }
}
