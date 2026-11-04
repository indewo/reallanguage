package com.reallanguage.app.feature.knowledge.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.knowledge.domain.PatternBookRepository
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class PatternBookViewModel(private val repository: PatternBookRepository) : ViewModel() {
    private val mutableUiState = MutableStateFlow(PatternBookUiState(loading = false))
    val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<PatternBookEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private data class PronunciationSelection(val patternId: String, val exampleIndex: Int)
    private var lastPronunciation: PronunciationSelection? = null
    private var activePronunciationRequest: Long? = null
    private var nextPronunciationRequestId = 0L
    init { load() }
    fun onAction(action: PatternBookAction) {
        when (action) {
            is PatternBookAction.QueryChanged -> {
                val state = mutableUiState.value
                val hidesExpandedItem = state.expandedPatternId != null &&
                    state.copy(query = action.query).expandedPattern == null
                if (hidesExpandedItem) stopPronunciation()
                mutableUiState.update { it.copy(query = action.query,
                    expandedPatternId = if (hidesExpandedItem) null else it.expandedPatternId) }
            }
            is PatternBookAction.PronunciationFinished -> if (
                activePronunciationRequest == action.requestId && mutableUiState.value.pronunciationBusy
            ) mutableUiState.update { it.copy(pronunciationBusy = false, pronunciationFailed = !action.succeeded) }
            PatternBookAction.PronunciationInterrupted -> stopPronunciation()
            PatternBookAction.RetryPronunciationRequested -> if (mutableUiState.value.pronunciationFailed) {
                lastPronunciation?.let(::requestPronunciation)
            }
            is PatternBookAction.PatternSelected -> if (mutableUiState.value.visiblePatterns.any { it.patternId == action.patternId }) {
                if (mutableUiState.value.expandedPatternId != action.patternId) stopPronunciation()
                mutableUiState.update { it.copy(expandedPatternId = action.patternId) }
            }
            PatternBookAction.CollapseRequested -> {
                stopPronunciation()
                mutableUiState.update { it.copy(expandedPatternId = null) }
            }
            PatternBookAction.RetryRequested -> load()
            PatternBookAction.ReturnHomeRequested -> {
                stopPronunciation()
                send(PatternBookEffect.ReturnHome)
            }
            PatternBookAction.RemoveRequested -> mutableUiState.value.expandedPattern?.let {
                stopPronunciation()
                send(PatternBookEffect.OpenRemovalConfirmation(it.patternId))
            }
            is PatternBookAction.ExamplePronunciationRequested -> requestPronunciation(
                PronunciationSelection(action.patternId, action.exampleIndex),
            )
        }
    }

    private fun requestPronunciation(selection: PronunciationSelection) {
        val state = mutableUiState.value
        if (state.loading || state.loadFailed) return
        val pattern = state.expandedPattern ?: return
        if (pattern.patternId != selection.patternId) return
        val text = pattern.examples.getOrNull(selection.exampleIndex)?.pronunciationText
        if (text.isNullOrBlank() || (state.pronunciationBusy && lastPronunciation == selection)) return
        val requestId = nextPronunciationRequestId++
        lastPronunciation = selection
        activePronunciationRequest = requestId
        mutableUiState.update { it.copy(pronunciationBusy = true, pronunciationFailed = false) }
        send(PatternBookEffect.PlayExample(text, requestId))
    }

    private fun stopPronunciation() {
        val shouldStop = activePronunciationRequest != null
        activePronunciationRequest = null
        lastPronunciation = null
        mutableUiState.update { it.copy(pronunciationBusy = false, pronunciationFailed = false) }
        if (shouldStop) send(PatternBookEffect.StopExample)
    }

    private fun load() {
        if (mutableUiState.value.loading) return
        stopPronunciation()
        mutableUiState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
        val result = try { repository.loadAllPatterns() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PatternLoadResult.Failed }
        when (result) {
            is PatternLoadResult.Ready -> mutableUiState.update { it.copy(patterns = result.patterns, loading = false) }
            PatternLoadResult.Failed -> mutableUiState.update { it.copy(patterns = emptyList(), loading = false, loadFailed = true) }
        }
    } }

    private fun send(effect: PatternBookEffect) { viewModelScope.launch { effectChannel.send(effect) } }
}
