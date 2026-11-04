package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import com.reallanguage.app.feature.learning.domain.FixedChapterRepository
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChapterEntryViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("u1", "I was happy.", "我很高兴。", true)))
    @Test fun `loads the immutable local chapter and opens its first focus`() = runTest {
        val viewModel = ChapterEntryViewModel("c1", FixedChapterRepository { FixedChapterLoadResult.Loaded(chapter) })
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(chapter, viewModel.uiState.value.chapter)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ChapterEntryAction.StartRequested)
        assertEquals(ChapterEntryEffect.OpenExplanation(chapter, "u1"), effect.await())
    }
    @Test fun `shows a retryable failure without inventing chapter content`() = runTest {
        val viewModel = ChapterEntryViewModel("missing", FixedChapterRepository { FixedChapterLoadResult.Failed })
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.failed)
        assertEquals(null, viewModel.uiState.value.chapter)
    }

    @Test fun `storage exception becomes retryable failure rather than leaving spinner or crashing`() = runTest {
        var unavailable = true
        val viewModel = ChapterEntryViewModel("c1", FixedChapterRepository {
            if (unavailable) error("database temporarily unavailable") else FixedChapterLoadResult.Loaded(chapter)
        })
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.failed)
        assertEquals(false, viewModel.uiState.value.loading)
        unavailable = false
        viewModel.onAction(ChapterEntryAction.RetryRequested)
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(chapter, viewModel.uiState.value.chapter)
    }

    @Test fun `wrong chapter missing focus or duplicate sentence cannot be offered for learning`() = runTest {
        for (invalid in listOf(chapter.copy(chapterId = "other"),
            chapter.copy(sentences = chapter.sentences.map { it.copy(isFocus = false) }),
            chapter.copy(sentences = chapter.sentences + chapter.sentences))) {
            val viewModel = ChapterEntryViewModel("c1", FixedChapterRepository { FixedChapterLoadResult.Loaded(invalid) })
            dispatcher.dispatcher.scheduler.advanceUntilIdle()
            assertEquals(true, viewModel.uiState.value.failed)
            assertEquals(null, viewModel.uiState.value.chapter)
        }
    }

    @Test fun `repeated start emits only one learning transition`() = runTest {
        val viewModel = ChapterEntryViewModel("c1", FixedChapterRepository { FixedChapterLoadResult.Loaded(chapter) })
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        val effects = mutableListOf<ChapterEntryEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
        viewModel.onAction(ChapterEntryAction.StartRequested)
        viewModel.onAction(ChapterEntryAction.StartRequested)
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, effects.size)
    }

    @Test fun `return during loading ignores late chapter and repeated navigation`() = runTest {
        val result = CompletableDeferred<FixedChapterLoadResult>()
        val viewModel = ChapterEntryViewModel("c1", FixedChapterRepository { result.await() })
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        val effects = mutableListOf<ChapterEntryEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect { effects += it } }
        viewModel.onAction(ChapterEntryAction.BackRequested)
        result.complete(FixedChapterLoadResult.Loaded(chapter))
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        viewModel.onAction(ChapterEntryAction.StartRequested)
        viewModel.onAction(ChapterEntryAction.BackRequested)
        dispatcher.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(ChapterEntryEffect.ReturnToPrevious), effects)
        assertEquals(null, viewModel.uiState.value.chapter)
    }
}
