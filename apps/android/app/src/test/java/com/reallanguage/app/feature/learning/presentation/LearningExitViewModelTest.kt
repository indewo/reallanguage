package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.CheckpointSaveResult
import com.reallanguage.app.feature.learning.domain.LearningCheckpointRepository
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
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
class LearningExitViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val checkpoint = OrdinaryLearningCheckpoint("c1", "LR-09", "round-2")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun thrownSaveRetainsCheckpointAndAllowsOneExplicitRetry() = runTest(dispatcher) {
        var calls = 0
        val repository = object : LearningCheckpointRepository {
            override suspend fun save(checkpoint: OrdinaryLearningCheckpoint): CheckpointSaveResult {
                if (++calls == 1) throw java.io.IOException("interrupted")
                return CheckpointSaveResult.Saved
            }
        }
        val model = LearningExitViewModel(checkpoint, repository)
        model.onAction(LearningExitAction.PauseLearningRequested)
        model.onAction(LearningExitAction.PauseLearningRequested)
        assertTrue(model.uiState.value.saving)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(model.uiState.value.saveFailed)
        assertFalse(model.uiState.value.saving)
        assertEquals(checkpoint, model.uiState.value.checkpoint)
        val effect = async { model.effects.first() }
        model.onAction(LearningExitAction.PauseLearningRequested)
        model.onAction(LearningExitAction.ContinueLearningRequested)
        model.onAction(LearningExitAction.AbandonChapterRequested)
        advanceUntilIdle()
        assertEquals(LearningExitEffect.ReturnHome, effect.await())
        assertEquals(2, calls)
    }

    @Test
    fun continueLearningOnlyClosesDialog() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = LearningExitViewModel(checkpoint, repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(LearningExitAction.ContinueLearningRequested)
        advanceUntilIdle()
        assertEquals(LearningExitEffect.CloseDialog, effect.await())
        assertEquals(0, repository.saveCalls)
    }

    @Test
    fun pauseSavesExactCheckpointBeforeReturningHome() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = LearningExitViewModel(checkpoint, repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(LearningExitAction.PauseLearningRequested)
        advanceUntilIdle()
        assertEquals(checkpoint, repository.savedCheckpoint)
        assertEquals(LearningExitEffect.ReturnHome, effect.await())
        assertFalse(viewModel.uiState.value.saving)
    }

    @Test
    fun failedSaveKeepsDialogOpenForRetry() = runTest(dispatcher) {
        val repository = FakeRepository(CheckpointSaveResult.Failed)
        val viewModel = LearningExitViewModel(checkpoint, repository)
        viewModel.onAction(LearningExitAction.PauseLearningRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.saveFailed)
        assertFalse(viewModel.uiState.value.saving)
    }

    @Test
    fun abandonOnlyOpensConfirmationAndNeverDeletesDirectly() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = LearningExitViewModel(checkpoint, repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(LearningExitAction.AbandonChapterRequested)
        advanceUntilIdle()
        assertEquals(LearningExitEffect.OpenAbandonConfirmation, effect.await())
        assertEquals(0, repository.saveCalls)
    }

    private class FakeRepository(
        var result: CheckpointSaveResult = CheckpointSaveResult.Saved,
    ) : LearningCheckpointRepository {
        var saveCalls = 0
        var savedCheckpoint: OrdinaryLearningCheckpoint? = null

        override suspend fun save(
            checkpoint: OrdinaryLearningCheckpoint,
        ): CheckpointSaveResult {
            saveCalls += 1
            savedCheckpoint = checkpoint
            return result
        }
    }
}
