package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.HandwritingCompletionRepository
import com.reallanguage.app.feature.learning.domain.HandwritingCompletionResult
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
class HandwritingCopyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chapter = FixedLearningChapter(
        "c1", 1,
        listOf(
            FixedLearningSentence(
                "s1", "I expressed myself clearly.", "我清楚地表达了自己。", true,
            ),
        ),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun aConfirmedCopyIsRestoredInsteadOfRequiringTwoMoreCopies() = runTest(dispatcher) {
        var saved: Map<String, String>? = null
        val store = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
            override suspend fun read() = saved
            override suspend fun write(values: Map<String, String>): Boolean { saved = values; return true }
        }
        fun create() = HandwritingCopyViewModel(chapter, "s1", HandwritingCompletionRepository { _, _ -> HandwritingCompletionResult.Saved }, store)
        val first = create(); advanceUntilIdle()
        first.onAction(HandwritingCopyAction.WrittenFinished); advanceUntilIdle()
        val restored = create(); advanceUntilIdle()
        assertEquals(1, restored.uiState.value.confirmedCopyCount)
        restored.onAction(HandwritingCopyAction.WrittenFinished); advanceUntilIdle()
        assertTrue(restored.uiState.value.minimumCompleted)
        assertFalse(restored.uiState.value.copyInProgress)
    }

    @Test fun failedConfirmationDoesNotClearTheCanvasOrCountAnUnsavedCopy() = runTest(dispatcher) {
        var writable = false
        val store = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
            override suspend fun read(): Map<String, String>? = null
            override suspend fun write(values: Map<String, String>) = writable
        }
        val model = HandwritingCopyViewModel(chapter, "s1", HandwritingCompletionRepository { _, _ -> HandwritingCompletionResult.Saved }, store)
        advanceUntilIdle()
        model.onAction(HandwritingCopyAction.WrittenFinished); advanceUntilIdle()
        assertEquals(0, model.uiState.value.confirmedCopyCount)
        assertEquals(0, model.uiState.value.canvasRevision)
        assertTrue(model.uiState.value.saveFailed)
        writable = true
        model.onAction(HandwritingCopyAction.WrittenFinished); advanceUntilIdle()
        assertEquals(1, model.uiState.value.confirmedCopyCount)
        assertFalse(model.uiState.value.saveFailed)
    }

    @Test
    fun exactlyTwoSelfConfirmationsAreRequiredBeforeContinuing() = runTest(dispatcher) {
        val viewModel = createViewModel()
        viewModel.onAction(HandwritingCopyAction.WrittenFinished)
        assertEquals(1, viewModel.uiState.value.confirmedCopyCount)
        assertFalse(viewModel.uiState.value.minimumCompleted)
        viewModel.onAction(HandwritingCopyAction.ContinueRequested)

        viewModel.onAction(HandwritingCopyAction.WrittenFinished)
        assertEquals(2, viewModel.uiState.value.confirmedCopyCount)
        assertTrue(viewModel.uiState.value.minimumCompleted)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(HandwritingCopyAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(HandwritingCopyEffect.OpenAudioDictation, effect.await())
    }

    @Test
    fun additionalCopiesAreUnlimitedAndDoNotCreateCorrectnessState() {
        val viewModel = createViewModel()
        repeat(2) { viewModel.onAction(HandwritingCopyAction.WrittenFinished) }
        repeat(5) {
            viewModel.onAction(HandwritingCopyAction.CopyAgainRequested)
            assertTrue(viewModel.uiState.value.copyInProgress)
            viewModel.onAction(HandwritingCopyAction.WrittenFinished)
        }
        assertEquals(7, viewModel.uiState.value.confirmedCopyCount)
        assertFalse(viewModel.uiState.value.copyInProgress)
    }

    @Test
    fun actionsOutsideTheirAllowedPhaseAreIgnoredAndBackReturnsToReadSpeak() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(HandwritingCopyAction.CopyAgainRequested)
            viewModel.onAction(HandwritingCopyAction.ContinueRequested)
            assertEquals(0, viewModel.uiState.value.confirmedCopyCount)
            repeat(2) { viewModel.onAction(HandwritingCopyAction.WrittenFinished) }
            viewModel.onAction(HandwritingCopyAction.WrittenFinished)
            assertEquals(2, viewModel.uiState.value.confirmedCopyCount)
            viewModel.onAction(HandwritingCopyAction.CopyAgainRequested)
            viewModel.onAction(HandwritingCopyAction.CopyAgainRequested)
            viewModel.onAction(HandwritingCopyAction.ContinueRequested)
            assertTrue(viewModel.uiState.value.copyInProgress)

            val effect = async { viewModel.effects.first() }
            viewModel.onAction(HandwritingCopyAction.BackRequested)
            advanceUntilIdle()
            assertEquals(HandwritingCopyEffect.ReturnToReadSpeak, effect.await())
        }

    @Test
    fun localSaveFailureKeepsTheStepAndAllowsTheSameContinueActionToRetry() =
        runTest(dispatcher) {
            var attempts = 0
            val viewModel = createViewModel(
                HandwritingCompletionRepository { _, _ ->
                    attempts += 1
                    if (attempts == 1) HandwritingCompletionResult.Failed
                    else HandwritingCompletionResult.Saved
                },
            )
            repeat(2) { viewModel.onAction(HandwritingCopyAction.WrittenFinished) }
            viewModel.onAction(HandwritingCopyAction.ContinueRequested)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.saveFailed)
            assertFalse(viewModel.uiState.value.saving)
            val effect = async { viewModel.effects.first() }
            viewModel.onAction(HandwritingCopyAction.ContinueRequested)
            advanceUntilIdle()
            assertEquals(2, attempts)
            assertEquals(HandwritingCopyEffect.OpenAudioDictation, effect.await())
        }

    private fun createViewModel(
        repository: HandwritingCompletionRepository = HandwritingCompletionRepository { _, _ ->
            HandwritingCompletionResult.Saved
        },
    ) = HandwritingCopyViewModel(chapter, "s1", repository)
}
