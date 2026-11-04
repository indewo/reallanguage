package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.ChapterAbandonRepository
import com.reallanguage.app.feature.learning.domain.ChapterAbandonResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterAbandonViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun repeatedConfirmDoesNotDeleteTwiceOrReturnToDeletedContent() = runTest(dispatcher) {
        val repository = FakeRepository(ChapterAbandonResult.Abandoned(false))
        val viewModel = createViewModel(repository)
        repeat(2) { viewModel.onAction(ChapterAbandonAction.ConfirmAbandonRequested) }
        viewModel.onAction(ChapterAbandonAction.ContinueLearningRequested)
        advanceUntilIdle()
        assertEquals(1, repository.calls)
        assertEquals(ChapterAbandonEffect.ReturnHome, viewModel.effects.first())
        viewModel.onAction(ChapterAbandonAction.ConfirmAbandonRequested)
        advanceUntilIdle()
        assertEquals(1, repository.calls)
    }

    @Test
    fun thrownStorageFailureIsRetryable() = runTest(dispatcher) {
        val repository = FakeRepository(ChapterAbandonResult.Failed).apply { throwOnDelete = true }
        val viewModel = createViewModel(repository)
        viewModel.onAction(ChapterAbandonAction.ConfirmAbandonRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.deleting)
        assertTrue(viewModel.uiState.value.deleteFailed)
    }

    @Test
    fun continueLearningNeverRunsDelete() = runTest(dispatcher) {
        val repository = FakeRepository(ChapterAbandonResult.Abandoned(false))
        val viewModel = createViewModel(repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ChapterAbandonAction.ContinueLearningRequested)
        advanceUntilIdle()
        assertEquals(ChapterAbandonEffect.ReturnToLearning, effect.await())
        assertEquals(0, repository.calls)
    }

    @Test
    fun confirmedAbandonRoutesToRemainingChapters() = runTest(dispatcher) {
        val repository = FakeRepository(ChapterAbandonResult.Abandoned(true, "source-1"))
        val viewModel = createViewModel(repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ChapterAbandonAction.ConfirmAbandonRequested)
        advanceUntilIdle()
        assertEquals("c1" to "delete-c1", repository.lastCommand)
        assertEquals(ChapterAbandonEffect.OpenChapterSelection("source-1"), effect.await())
    }

    @Test
    fun confirmedAbandonReturnsHomeWhenNoChapterRemains() = runTest(dispatcher) {
        val viewModel = createViewModel(
            FakeRepository(ChapterAbandonResult.Abandoned(false)),
        )
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ChapterAbandonAction.ConfirmAbandonRequested)
        advanceUntilIdle()
        assertEquals(ChapterAbandonEffect.ReturnHome, effect.await())
    }

    @Test
    fun archivedChapterCannotBeAbandoned() = runTest(dispatcher) {
        val repository = FakeRepository(ChapterAbandonResult.AlreadyArchived)
        val viewModel = createViewModel(repository)
        viewModel.onAction(ChapterAbandonAction.ConfirmAbandonRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.alreadyArchived)
        viewModel.onAction(ChapterAbandonAction.ConfirmAbandonRequested)
        advanceUntilIdle()
        assertEquals(1, repository.calls)
    }

    @Test
    fun failedAtomicDeleteKeepsChapterAndAllowsRetry() = runTest(dispatcher) {
        val repository = FakeRepository(ChapterAbandonResult.Failed)
        val viewModel = createViewModel(repository)
        viewModel.onAction(ChapterAbandonAction.ConfirmAbandonRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.deleteFailed)
        assertFalse(viewModel.uiState.value.deleting)
        repository.result = ChapterAbandonResult.Abandoned(false)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ChapterAbandonAction.ConfirmAbandonRequested)
        advanceUntilIdle()
        assertEquals(ChapterAbandonEffect.ReturnHome, effect.await())
    }

    private fun createViewModel(repository: FakeRepository) =
        ChapterAbandonViewModel("c1", "delete-c1", repository)

    private class FakeRepository(
        var result: ChapterAbandonResult,
    ) : ChapterAbandonRepository {
        var calls = 0
        var throwOnDelete = false
        var lastCommand: Pair<String, String>? = null

        override suspend fun abandonUnarchivedChapter(
            chapterId: String,
            commandId: String,
        ): ChapterAbandonResult {
            calls += 1
            if (throwOnDelete) error("Isolated delete failure")
            lastCommand = chapterId to commandId
            return result
        }
    }
}
