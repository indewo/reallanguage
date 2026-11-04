package com.reallanguage.app.feature.knowledge.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.knowledge.domain.VocabularyBookRepository
import com.reallanguage.app.feature.knowledge.domain.VocabularyEntry
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import com.reallanguage.app.core.vocabulary.VocabularyPool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class VocabularyBookViewModel(
    private val repository: VocabularyBookRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(VocabularyBookUiState(loading = false))
    val uiState: StateFlow<VocabularyBookUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<VocabularyBookEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private data class PronunciationSelection(val senseId: String, val exampleIndex: Int?)
    private var lastPronunciation: PronunciationSelection? = null
    private var activePronunciationRequest: Long? = null
    private var nextPronunciationRequestId = 0L
    private var ambiguousNativeWords: Set<Pair<String, String>> = emptySet()
    init { load() }

    fun onAction(action: VocabularyBookAction) {
        when (action) {
            is VocabularyBookAction.QueryChanged -> mutableUiState.update { it.copy(query = action.value) }
            VocabularyBookAction.SearchRequested -> load(reset = true, query = mutableUiState.value.query)
            VocabularyBookAction.ClearSearchRequested -> {
                mutableUiState.update { it.copy(query = "") }
                load(reset = true, query = "")
            }
            VocabularyBookAction.LoadMoreRequested -> loadMore()
            is VocabularyBookAction.InitialSelected -> {
                val initial = action.initial.uppercaseChar()
                if (initial in 'A'..'Z') {
                    mutableUiState.update { it.copy(query = "") }
                    load(reset = true, query = "", initial = initial)
                }
            }
            is VocabularyBookAction.SensePronunciationRequested -> requestPronunciation(PronunciationSelection(action.senseId, null))
            is VocabularyBookAction.ExamplePronunciationRequested -> requestPronunciation(PronunciationSelection(action.senseId, action.exampleIndex))
            is VocabularyBookAction.PronunciationFinished -> if (
                activePronunciationRequest == action.requestId && mutableUiState.value.pronunciationBusy
            ) mutableUiState.update { it.copy(pronunciationBusy = false, pronunciationFailed = !action.succeeded) }
            VocabularyBookAction.PronunciationInterrupted -> stopPronunciation()
            VocabularyBookAction.RetryPronunciationRequested -> if (mutableUiState.value.pronunciationFailed) {
                lastPronunciation?.let(::requestPronunciation)
            }
            is VocabularyBookAction.SenseSelected -> if (
                mutableUiState.value.entries.any { entry -> entry.senses.any { it.senseId == action.senseId } }
            ) {
                if (mutableUiState.value.expandedSenseId != action.senseId) stopPronunciation()
                mutableUiState.update { it.copy(expandedSenseId = action.senseId) }
            }
            VocabularyBookAction.CollapseRequested -> {
                stopPronunciation()
                mutableUiState.update { it.copy(expandedSenseId = null) }
            }
            VocabularyBookAction.RetryRequested -> mutableUiState.value.let { state ->
                load(
                    reset = true,
                    query = state.appliedQuery,
                    initial = state.selectedInitial.takeIf { state.appliedQuery.isBlank() },
                )
            }
            VocabularyBookAction.ReturnHomeRequested -> {
                stopPronunciation()
                send(VocabularyBookEffect.ReturnHome)
            }
            VocabularyBookAction.PronunciationRequested -> {
                val state = mutableUiState.value
                (state.expandedSense ?: state.entries.firstOrNull()?.senses?.firstOrNull())?.let {
                    requestPronunciation(PronunciationSelection(it.senseId, null))
                }
            }
        }
    }

    private fun requestPronunciation(selection: PronunciationSelection) {
        val state = mutableUiState.value
        if (state.loading || state.loadFailed) return
        val sense = if (selection.exampleIndex == null) {
            state.entries.asSequence().flatMap { it.senses.asSequence() }
                .firstOrNull { it.senseId == selection.senseId }
        } else state.expandedSense
        if (sense == null || sense.senseId != selection.senseId) return
        val text = if (selection.exampleIndex == null) sense.pronunciationText
            else sense.examples.getOrNull(selection.exampleIndex)?.pronunciationText
        if (text.isNullOrBlank() || (state.pronunciationBusy && lastPronunciation == selection)) return
        if (selection.exampleIndex == null &&
            (sense.englishForm.lowercase() to text.trim()) in ambiguousNativeWords) {
            // Text-only TTS cannot distinguish different saved pronunciations
            // of the same spoken word. Never guess, read IPA, or substitute an
            // example. Keep this exact failed selection available for retry.
            stopPronunciation()
            lastPronunciation = selection
            mutableUiState.update { it.copy(pronunciationBusy = false, pronunciationFailed = true) }
            return
        }
        val requestId = nextPronunciationRequestId++
        lastPronunciation = selection
        activePronunciationRequest = requestId
        mutableUiState.update { it.copy(pronunciationBusy = true, pronunciationFailed = false) }
        send(VocabularyBookEffect.PlayPronunciation(text, requestId))
    }

    private fun stopPronunciation() {
        val shouldStop = activePronunciationRequest != null
        activePronunciationRequest = null
        lastPronunciation = null
        mutableUiState.update { it.copy(pronunciationBusy = false, pronunciationFailed = false) }
        if (shouldStop) send(VocabularyBookEffect.StopPronunciation)
    }

    private fun load(reset: Boolean = true, query: String = "", initial: Char? = null) {
        if (mutableUiState.value.loading) return
        stopPronunciation()
        ambiguousNativeWords = emptySet()
        mutableUiState.update {
            it.copy(
                loading = true,
                loadingMore = false,
                loadFailed = false,
                entries = if (reset) emptyList() else it.entries,
                expandedSenseId = null,
            )
        }
        viewModelScope.launch {
            val result = try {
                if (initial == null) repository.loadPage(query.trim(), PAGE_SIZE, 0)
                else repository.loadFromInitial(initial, PAGE_SIZE, 0)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { VocabularyLoadResult.Failed }
            when (result) {
                is VocabularyLoadResult.Ready -> {
                    // Scope changes hide entries, not already loaded evidence.
                    // This stores only conflict keys; the visible list remains
                    // restricted to the user's current learning scopes below.
                    ambiguousNativeWords = result.records.filter { !it.pronunciationText.isNullOrBlank() }
                        .groupBy { it.englishForm.lowercase() to it.pronunciationText.orEmpty().trim() }
                        .filterValues { records ->
                            records.mapNotNull { it.phonetic?.trim()?.takeIf(String::isNotEmpty) }.distinct().size > 1
                        }.keys
                    val entries = result.records.toEntries(result.currentScopes)
                    mutableUiState.update {
                        it.copy(
                            currentScope = result.currentScopes.sorted().joinToString(" / ") { scope -> scope.displayScope() },
                            entries = entries,
                            appliedQuery = query.trim(),
                            selectedInitial = initial ?: query.trim().firstOrNull()?.uppercaseChar()
                                ?.takeIf { it in 'A'..'Z' } ?: entries.firstOrNull()?.englishForm
                                ?.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: 'A',
                            hasMore = result.hasMore,
                            longestHeadword = result.longestHeadword.ifBlank {
                                entries.maxByOrNull { entry -> entry.englishForm.length }?.englishForm.orEmpty()
                            },
                            loading = false,
                        )
                    }
                }
                VocabularyLoadResult.Failed -> mutableUiState.update { it.copy(entries = emptyList(), loading = false, loadFailed = true) }
            }
        }
    }

    private fun loadMore() {
        val state = mutableUiState.value
        if (state.loading || state.loadingMore || !state.hasMore || state.expandedSenseId != null) return
        mutableUiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val result = try {
                if (state.appliedQuery.isNotBlank()) repository.loadPage(state.appliedQuery, PAGE_SIZE, state.entries.size)
                else repository.loadFromInitial(state.selectedInitial, PAGE_SIZE, state.entries.size)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { VocabularyLoadResult.Failed }
            when (result) {
                is VocabularyLoadResult.Ready -> {
                    val appended = result.records.toEntries(result.currentScopes)
                    ambiguousNativeWords = (state.entries + appended)
                        .flatMap(VocabularyEntry::senses)
                        .filter { !it.pronunciationText.isNullOrBlank() }
                        .groupBy { it.englishForm.lowercase() to it.pronunciationText.orEmpty().trim() }
                        .filterValues { records ->
                            records.mapNotNull { it.phonetic?.trim()?.takeIf(String::isNotEmpty) }.distinct().size > 1
                        }.keys
                    mutableUiState.update {
                        it.copy(entries = it.entries + appended, hasMore = result.hasMore, loadingMore = false)
                    }
                }
                VocabularyLoadResult.Failed -> mutableUiState.update { it.copy(loadingMore = false, loadFailed = true) }
            }
        }
    }

    private fun send(effect: VocabularyBookEffect) { viewModelScope.launch { effectChannel.send(effect) } }

    private fun List<VocabularySenseRecord>.toEntries(currentScopes: Set<String>): List<VocabularyEntry> =
        filter { it.learningScopes.any(currentScopes::contains) }
            .groupBy { it.englishForm.lowercase() }
            .values
            .map { records -> VocabularyEntry(records.first().englishForm, records.sortedBy { it.enteredOrder }) }

    private fun String.displayScope(): String = runCatching {
        VocabularyPool.valueOf(this).displayNameZh + "单词"
    }.getOrDefault(this)

    private companion object { const val PAGE_SIZE = 200 }
}
