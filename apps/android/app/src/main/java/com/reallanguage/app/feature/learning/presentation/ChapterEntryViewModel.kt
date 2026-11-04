package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import com.reallanguage.app.feature.learning.domain.FixedChapterRepository
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

data class ChapterEntryUiState(val loading: Boolean = false, val failed: Boolean = false, val chapter: FixedLearningChapter? = null)
sealed interface ChapterEntryAction { data object RetryRequested : ChapterEntryAction; data object StartRequested : ChapterEntryAction; data object BackRequested : ChapterEntryAction }
sealed interface ChapterEntryEffect { data class OpenExplanation(val chapter: FixedLearningChapter, val focusSentenceId: String) : ChapterEntryEffect; data object ReturnToPrevious : ChapterEntryEffect }
class ChapterEntryViewModel(private val chapterId: String, private val repository: FixedChapterRepository) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ChapterEntryUiState())
    val uiState = mutableUiState.asStateFlow()
    private val effectsChannel = Channel<ChapterEntryEffect>(Channel.BUFFERED)
    val effects = effectsChannel.receiveAsFlow()
    private var navigationCommitted = false
    private var loadJob: Job? = null
    init { load() }
    fun onAction(action: ChapterEntryAction) = when (action) {
        ChapterEntryAction.RetryRequested -> load()
        ChapterEntryAction.BackRequested -> navigate(ChapterEntryEffect.ReturnToPrevious)
        ChapterEntryAction.StartRequested -> mutableUiState.value.chapter?.let { chapter ->
            chapter.sentences.firstOrNull { it.isFocus }?.let { focus ->
                navigate(ChapterEntryEffect.OpenExplanation(chapter, focus.sentenceId))
            }
        }
    }
    private fun navigate(effect: ChapterEntryEffect) {
        if (navigationCommitted) return
        navigationCommitted = true
        loadJob?.cancel()
        viewModelScope.launch { effectsChannel.send(effect) }
    }
    private fun load() {
        if (navigationCommitted || mutableUiState.value.loading) return
        mutableUiState.update { ChapterEntryUiState(loading = true) }
        loadJob = viewModelScope.launch {
            val result = try { repository.load(chapterId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { FixedChapterLoadResult.Failed }
            if (navigationCommitted) return@launch
            val chapter = (result as? FixedChapterLoadResult.Loaded)?.chapter?.takeIf {
                it.chapterId == chapterId && it.sentences.any { sentence -> sentence.isFocus } &&
                    it.sentences.map { sentence -> sentence.sentenceId }.distinct().size == it.sentences.size
            }
            mutableUiState.value = if (chapter == null) ChapterEntryUiState(failed = true)
                else ChapterEntryUiState(chapter = chapter)
        }
    }
}
