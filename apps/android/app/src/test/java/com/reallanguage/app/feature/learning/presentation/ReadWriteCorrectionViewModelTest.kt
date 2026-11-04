package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionRepository
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionResult
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
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
class ReadWriteCorrectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chapter = FixedLearningChapter(
        "c1", 1,
        listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true)),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun storageExceptionStopsSavingAndKeepsTheCorrectionRetryable() = runTest(dispatcher) {
        var fail = true
        val model = createViewModel(AudioDictationCompletionRepository {
            if (fail) error("Isolated storage failure")
            AudioDictationCompletionResult.Saved
        })
        model.onAction(ReadWriteCorrectionAction.WrittenFinished)
        advanceUntilIdle()
        assertFalse(model.uiState.value.saving)
        assertTrue(model.uiState.value.saveFailed)
        fail = false
        model.onAction(ReadWriteCorrectionAction.WrittenFinished)
        advanceUntilIdle()
        assertFalse(model.uiState.value.saveFailed)
    }

    @Test fun savingAndSuccessfulNavigationCannotDispatchConflictingActions() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val effects = mutableListOf<ReadWriteCorrectionEffect>()
        val model = createViewModel(AudioDictationCompletionRepository {
            calls++; gate.await(); AudioDictationCompletionResult.Saved
        })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
        model.onAction(ReadWriteCorrectionAction.WrittenFinished)
        runCurrent()
        model.onAction(ReadWriteCorrectionAction.BackRequested)
        gate.complete(Unit)
        advanceUntilIdle()
        model.onAction(ReadWriteCorrectionAction.WrittenFinished)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(listOf(ReadWriteCorrectionEffect.OpenFullSentenceSpeaking), effects)
    }

    @Test fun playbackFailureDoesNotConfirmCorrectionAndAllowsPronunciationRetry() = runTest(dispatcher) {
        var saves = 0
        val model = createViewModel(AudioDictationCompletionRepository { saves++; AudioDictationCompletionResult.Saved })
        model.onAction(ReadWriteCorrectionAction.AudioPlaybackFailed)
        assertFalse(model.uiState.value.audioLooping)
        assertTrue(model.uiState.value.audioFailed)
        assertEquals(0, saves)
        model.onAction(ReadWriteCorrectionAction.AudioToggled)
        assertTrue(model.uiState.value.audioLooping)
        assertFalse(model.uiState.value.audioFailed)
    }

    @Test fun `user confirmation saves correction without recognizing handwriting`() =
        runTest(dispatcher) {
            var savedSentence: String? = null
            val viewModel = createViewModel(
                AudioDictationCompletionRepository {
                    savedSentence = it
                    AudioDictationCompletionResult.Saved
                },
            )
            val effect = async { viewModel.effects.first() }
            viewModel.onAction(ReadWriteCorrectionAction.WrittenFinished)
            advanceUntilIdle()
            assertEquals("s1", savedSentence)
            assertFalse(viewModel.uiState.value.audioLooping)
            assertEquals(ReadWriteCorrectionEffect.OpenFullSentenceSpeaking, effect.await())
        }

    @Test fun `failed save remains retryable and back returns to dictation`() =
        runTest(dispatcher) {
            val viewModel = createViewModel(
                AudioDictationCompletionRepository { AudioDictationCompletionResult.Failed },
            )
            viewModel.onAction(ReadWriteCorrectionAction.WrittenFinished)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.saveFailed)
            val effect = async { viewModel.effects.first() }
            viewModel.onAction(ReadWriteCorrectionAction.BackRequested)
            advanceUntilIdle()
            assertEquals(ReadWriteCorrectionEffect.ReturnToDictation, effect.await())
        }

    private fun createViewModel(repository: AudioDictationCompletionRepository) =
        ReadWriteCorrectionViewModel(chapter, "s1", listOf("felt：感到"), repository)
}
