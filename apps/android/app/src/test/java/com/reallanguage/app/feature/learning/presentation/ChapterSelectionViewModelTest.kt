package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.ChapterSelectionLoadResult
import com.reallanguage.app.feature.learning.domain.ChapterSelectionRepository
import com.reallanguage.app.feature.learning.domain.ChapterSelectionBoundary
import com.reallanguage.app.feature.learning.domain.RemainingChapterSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterSelectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chapters = listOf(
        RemainingChapterSummary("c2", 2, "继续表达这次经历"),
        RemainingChapterSummary("c3", 3, "总结自己的感受"),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun selectingARemainingChapterOpensOnlyThatChapter() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ChapterSelectionAction.ChapterSelected("c3"))
        advanceUntilIdle()
        assertEquals(ChapterSelectionEffect.OpenChapter("c3"), effect.await())
    }

    @Test
    fun unknownChapterCannotBeOpened() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.onAction(ChapterSelectionAction.ChapterSelected("other-source"))
        advanceUntilIdle()
        assertEquals(chapters, viewModel.uiState.value.chapters)
    }

    @Test
    fun learnLaterReturnsHomeWithoutOpeningAChapter() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ChapterSelectionAction.LearnLaterRequested)
        advanceUntilIdle()
        assertEquals(ChapterSelectionEffect.ReturnHome, effect.await())
    }

    @Test
    fun backReturnsToPreviousNodeWithoutSharingLearnLaterHomeBehavior() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ChapterSelectionAction.BackRequested)
        advanceUntilIdle()
        assertEquals(ChapterSelectionEffect.ReturnToPrevious, effect.await())
    }

    @Test
    fun loadFailureCanRetryWithoutInventingChapters() = runTest(dispatcher) {
        val repository = FakeRepository(ChapterSelectionLoadResult.Failed)
        val boundary = ChapterSelectionBoundary.ArchivedChapter("c1")
        val viewModel = ChapterSelectionViewModel(boundary, repository)
        advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.loadFailed)
        assertEquals(emptyList<RemainingChapterSummary>(), viewModel.uiState.value.chapters)
        repository.result = ChapterSelectionLoadResult.Ready("source-1", chapters)
        viewModel.onAction(ChapterSelectionAction.RetryLoadRequested)
        advanceUntilIdle()
        assertEquals(chapters, viewModel.uiState.value.chapters)
        assertEquals(listOf(boundary, boundary), repository.calls)
    }

    private fun viewModel() = ChapterSelectionViewModel(
        ChapterSelectionBoundary.ArchivedChapter("c1"),
        FakeRepository(ChapterSelectionLoadResult.Ready("source-1", chapters)),
    )

    private class FakeRepository(
        var result: ChapterSelectionLoadResult,
    ) : ChapterSelectionRepository {
        val calls = mutableListOf<ChapterSelectionBoundary>()
        override suspend fun loadRemaining(boundary: ChapterSelectionBoundary): ChapterSelectionLoadResult {
            calls += boundary
            return result
        }
    }
}
