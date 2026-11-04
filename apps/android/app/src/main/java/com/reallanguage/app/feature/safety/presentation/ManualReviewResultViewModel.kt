package com.reallanguage.app.feature.safety.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.safety.domain.ManualReviewResult
import com.reallanguage.app.feature.safety.domain.ManualReviewResultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class ManualReviewResultViewModel(
    private val reviewRequestId: String,
    private val repository: ManualReviewResultRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ManualReviewResultUiState())
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<ManualReviewResultEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var result: ManualReviewResult.Ready? = null
    private var loadJob: Job? = null
    private var departed = false
    init { load() }
    fun onAction(action: ManualReviewResultAction) {
        if (departed) return
        if (action == ManualReviewResultAction.ReturnMessagesRequested) {
            departed = true
            result = null
            loadJob?.cancel()
            effectChannel.trySend(ManualReviewResultEffect.OpenMessages)
            return
        }
        if (mutableUiState.value.loading || mutableUiState.value.unavailable) return
        when (action) {
            ManualReviewResultAction.ReturnMessagesRequested -> Unit
            ManualReviewResultAction.RetryRequested -> load()
            ManualReviewResultAction.OpenFullSentenceSplitRequested -> result?.takeIf { it.outcome == ManualReviewOutcome.APPROVED }?.let {
                departed = true
                result = null
                effectChannel.trySend(ManualReviewResultEffect.OpenSemanticSplit(it.source))
            }
            ManualReviewResultAction.ReturnToSafetyHandlingRequested -> result?.takeIf { it.outcome == ManualReviewOutcome.NOT_APPROVED }?.let {
                departed = true
                result = null
                effectChannel.trySend(ManualReviewResultEffect.OpenSafetyHandling(it.source.safetyCaseId, it.source.text))
            }
        }
    }
    private fun load() {
        if (departed || loadJob?.isActive == true) return
        result = null
        mutableUiState.value = ManualReviewResultUiState()
        loadJob = viewModelScope.launch {
            val loaded = try { repository.load(reviewRequestId) } catch (c: CancellationException) { throw c } catch (_: Exception) { ManualReviewResult.Failed }
            if (departed) return@launch
            result = loaded as? ManualReviewResult.Ready
            mutableUiState.value = when (loaded) {
                is ManualReviewResult.Ready -> ManualReviewResultUiState(
                    outcome = loaded.outcome,
                    summaryZh = if (loaded.outcome == ManualReviewOutcome.APPROVED) "本次内容已通过人工复核。" else "本次内容未通过人工复核。",
                    reasonZh = loaded.source.userReasonZh,
                    loading = false,
                )
                ManualReviewResult.Failed -> ManualReviewResultUiState(loading = false, loadFailed = true)
                ManualReviewResult.Unavailable -> ManualReviewResultUiState(loading = false, unavailable = true)
                ManualReviewResult.RequiresLogin -> {
                    departed = true
                    effectChannel.send(ManualReviewResultEffect.OpenLoginPreservingTarget(reviewRequestId))
                    ManualReviewResultUiState(loading = false, loadFailed = true)
                }
            }
        }
    }
}
