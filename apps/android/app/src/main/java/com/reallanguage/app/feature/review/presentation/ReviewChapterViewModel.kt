package com.reallanguage.app.feature.review.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft
import com.reallanguage.app.feature.review.domain.ReviewChapterCommitResult
import com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult
import com.reallanguage.app.feature.review.domain.ReviewChapterRepository
import com.reallanguage.app.feature.review.domain.isValidFor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class ReviewChapterViewModel(
    reviewChapterId: String,
    commandId: String,
    private val repository: ReviewChapterRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        ReviewChapterUiState(reviewChapterId, commandId, loading = false),
    )
    val uiState: StateFlow<ReviewChapterUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ReviewChapterEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init {
        loadChapter()
    }

    fun onAction(action: ReviewChapterAction) {
        val state = mutableUiState.value
        if (state.completed || state.committing) return
        if (state.loading && action != ReviewChapterAction.ExitRequested) return
        when (action) {
            ReviewChapterAction.OpenCurrentModuleRequested -> {
                mutableUiState.value.currentAbility?.let {
                    send(ReviewChapterEffect.OpenAbilityModule(it.abilityId, it.module))
                }
            }
            is ReviewChapterAction.ModuleCompleted -> completeModule(action)
            ReviewChapterAction.RetryLoadRequested -> loadChapter()
            ReviewChapterAction.RetryCommitRequested -> commitChapter()
            ReviewChapterAction.ExitRequested -> send(ReviewChapterEffect.OpenExitConfirmation)
        }
    }

    private fun loadChapter() {
        if (mutableUiState.value.loading) return
        mutableUiState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            val loaded = try { repository.loadChapter(mutableUiState.value.reviewChapterId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ReviewChapterLoadResult.Failed }
            when (val result = loaded) {
                is ReviewChapterLoadResult.Ready -> mutableUiState.update {
                    it.copy(chapter = result.chapter, loading = false)
                }
                ReviewChapterLoadResult.Failed -> mutableUiState.update {
                    it.copy(loading = false, loadFailed = true)
                }
            }
        }
    }

    private fun completeModule(action: ReviewChapterAction.ModuleCompleted) {
        val current = mutableUiState.value.currentAbility ?: return
        if (current.abilityId != action.abilityId) return
        val result = ReviewAbilityResultDraft(action.abilityId, action.evidenceId, action.passed, action.qualification, action.patternReceipt)
        if (!result.isValidFor(current.module)) return
        mutableUiState.update {
            it.copy(
                resultDrafts = it.resultDrafts + result,
            )
        }
        if (mutableUiState.value.currentAbility == null) commitChapter()
    }

    private fun commitChapter() {
        val state = mutableUiState.value
        if (state.committing || state.completed || state.currentAbility != null || state.chapter == null) return
        mutableUiState.update { it.copy(committing = true, commitFailed = false) }
        viewModelScope.launch {
            val committed = try { repository.commitChapter(
                    state.reviewChapterId,
                    state.commandId,
                    state.resultDrafts,
                ) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ReviewChapterCommitResult.Failed }
            when (val result = committed) {
                is ReviewChapterCommitResult.Committed -> complete(result.nextReviewChapterId, result.reviewSessionId)
                is ReviewChapterCommitResult.AlreadyCommitted -> complete(result.nextReviewChapterId, result.reviewSessionId)
                ReviewChapterCommitResult.Failed -> mutableUiState.update {
                    it.copy(committing = false, commitFailed = true)
                }
            }
        }
    }

    private fun complete(nextReviewChapterId: String?, reviewSessionId: String) {
        mutableUiState.update { it.copy(committing = false, commitFailed = false, completed = true) }
        send(
            nextReviewChapterId?.let(ReviewChapterEffect::OpenNextReviewChapter)
                ?: reviewSessionId.takeIf(String::isNotBlank)
                    ?.let(ReviewChapterEffect::OpenReviewResultForSession)
                ?: ReviewChapterEffect.OpenReviewResult,
        )
    }

    private fun send(effect: ReviewChapterEffect) {
        viewModelScope.launch { effectChannel.send(effect) }
    }
}
