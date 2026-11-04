package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.ChapterArchiveRepository
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.feature.learning.domain.ChapterArchiveSummary
import com.reallanguage.app.feature.learning.domain.ChapterResultCategory
import com.reallanguage.app.feature.learning.domain.ChapterResultSummaryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterResultViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun aDifferentChaptersReceiptCannotUnlockNavigation() = runTest(dispatcher) {
        val viewModel = ChapterResultViewModel("c1", "cmd-1", FakeRepository(
            ChapterArchiveResult.Archived(summary.copy(chapterId = "other-chapter")),
        ))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.archiveFailed)
        assertNull(viewModel.uiState.value.summary)
    }

    @Test fun repeatedNextAndHomeClicksEmitOnlyOneNavigation() = runTest(dispatcher) {
        val viewModel = ChapterResultViewModel("c1", "cmd-1", FakeRepository(ChapterArchiveResult.Archived(summary)))
        advanceUntilIdle()
        val effects = mutableListOf<ChapterResultEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
        repeat(3) { viewModel.onAction(ChapterResultAction.EnterNextChapterRequested) }
        viewModel.onAction(ChapterResultAction.ReturnHomeRequested)
        advanceUntilIdle()
        assertEquals(listOf(ChapterResultEffect.OpenChapterSelection), effects)
    }

    @Test
    fun repeatedRetryBeforeDispatcherRunsMakesOnlyOneArchiveCall() = runTest(dispatcher) {
        val repository = FakeRepository(ChapterArchiveResult.Failed)
        val viewModel = ChapterResultViewModel("c1", "cmd-1", repository)
        repeat(3) { viewModel.onAction(ChapterResultAction.RetryArchiveRequested) }
        advanceUntilIdle()
        assertEquals(1, repository.calls.size)
    }

    @Test
    fun unexpectedStorageFailureStopsLoadingAndAllowsRetry() = runTest(dispatcher) {
        val repository = FakeRepository(ChapterArchiveResult.Failed).apply { throwOnArchive = true }
        val viewModel = ChapterResultViewModel("c1", "cmd-1", repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.archiveFailed)
        assertFalse(viewModel.uiState.value.archiving)
        repository.throwOnArchive = false
        repository.result = ChapterArchiveResult.Archived(summary)
        viewModel.onAction(ChapterResultAction.RetryArchiveRequested)
        advanceUntilIdle()
        assertEquals(summary, viewModel.uiState.value.summary)
    }

    @Test
    fun pageOnlyBecomesReadyAfterAtomicArchiveSucceeds() = runTest(dispatcher) {
        val repository = FakeRepository(ChapterArchiveResult.Archived(summary))
        val viewModel = ChapterResultViewModel("c1", "cmd-1", repository)
        advanceUntilIdle()
        assertEquals(summary, viewModel.uiState.value.summary)
        assertFalse(viewModel.uiState.value.archiving)
        assertEquals(listOf("c1" to "cmd-1"), repository.calls)
    }

    @Test
    fun idempotentRepeatedArchiveReturnsTheOriginalSummary() = runTest(dispatcher) {
        val viewModel = ChapterResultViewModel(
            "c1",
            "cmd-1",
            FakeRepository(ChapterArchiveResult.AlreadyArchived(summary)),
        )
        advanceUntilIdle()
        assertEquals(summary, viewModel.uiState.value.summary)
    }

    @Test
    fun failedArchiveShowsRetryWithoutPartialSummary() = runTest(dispatcher) {
        val repository = FakeRepository(ChapterArchiveResult.Failed)
        val viewModel = ChapterResultViewModel("c1", "cmd-1", repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.archiveFailed)
        assertNull(viewModel.uiState.value.summary)
        repository.result = ChapterArchiveResult.Archived(summary)
        viewModel.onAction(ChapterResultAction.RetryArchiveRequested)
        advanceUntilIdle()
        assertEquals(summary, viewModel.uiState.value.summary)
    }

    @Test
    fun nextChapterRequiresExplicitUserChoice() = runTest(dispatcher) {
        val viewModel = ChapterResultViewModel(
            "c1",
            "cmd-1",
            FakeRepository(ChapterArchiveResult.Archived(summary)),
        )
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ChapterResultAction.EnterNextChapterRequested)
        advanceUntilIdle()
        assertEquals(ChapterResultEffect.OpenChapterSelection, effect.await())
    }

    @Test
    fun laterAndNoNextChapterBothReturnHomeOnlyAfterArchive() = runTest(dispatcher) {
        val withNext = ChapterResultViewModel(
            "c1",
            "cmd-1",
            FakeRepository(ChapterArchiveResult.Archived(summary)),
        )
        advanceUntilIdle()
        val later = async { withNext.effects.first() }
        withNext.onAction(ChapterResultAction.LearnLaterRequested)
        advanceUntilIdle()
        assertEquals(ChapterResultEffect.ReturnHome, later.await())

        val withoutNext = ChapterResultViewModel(
            "c1",
            "cmd-2",
            FakeRepository(
                ChapterArchiveResult.Archived(summary.copy(hasNextChapter = false)),
            ),
        )
        advanceUntilIdle()
        val home = async { withoutNext.effects.first() }
        withoutNext.onAction(ChapterResultAction.ReturnHomeRequested)
        advanceUntilIdle()
        assertEquals(ChapterResultEffect.ReturnHome, home.await())
    }

    private class FakeRepository(
        var result: ChapterArchiveResult,
    ) : ChapterArchiveRepository {
        val calls = mutableListOf<Pair<String, String>>()
        var throwOnArchive = false

        override suspend fun archive(
            chapterId: String,
            commandId: String,
        ): ChapterArchiveResult {
            calls += chapterId to commandId
            if (throwOnArchive) error("Isolated storage failure")
            return result
        }
    }

    companion object {
        private val summary = ChapterArchiveSummary(
            chapterId = "c1",
            items = listOf(
                ChapterResultSummaryItem(ChapterResultCategory.SENTENCE, "完整句", 2, 2),
                ChapterResultSummaryItem(ChapterResultCategory.PATTERN, "句式", 1, 2),
                ChapterResultSummaryItem(ChapterResultCategory.INCOMPLETE, "未完成", 1, 1),
            ),
            hasNextChapter = true,
        )
    }
}
