package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
import com.reallanguage.app.feature.learning.domain.ReadSpeakRepository
import com.reallanguage.app.feature.learning.domain.LearningProgressStore
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
class ReadSpeakViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sentence = FixedLearningSentence(
        "s1", "I expressed myself clearly.", "我清楚地表达了自己。", true,
    )
    private val chapter = FixedLearningChapter("c1", 1, listOf(sentence))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun failedOriginalRequestRetriesItsSignalWithoutStartingAnotherRecording() = runTest(dispatcher) {
        val signals = mutableListOf<ReadSpeakCompletionSignal>()
        val repository = object : ReadSpeakRepository {
            override fun hasPendingFeedbackRetry() = signals.size == 1
            override suspend fun feedback(sentenceId: String, completionSignal: ReadSpeakCompletionSignal): ReadSpeakFeedbackResult {
                signals += completionSignal
                return if (signals.size == 1) ReadSpeakFeedbackResult.Failed else ReadSpeakFeedbackResult.Ready("已有原音已完成。")
            }
        }
        val model = ReadSpeakViewModel(chapter, "s1", repository)
        model.onAction(ReadSpeakAction.StartRequested)
        model.onAction(ReadSpeakAction.RecognitionCompleted)
        advanceUntilIdle()
        model.onAction(ReadSpeakAction.RetryRequested)
        assertFalse(model.uiState.value.listening)
        model.onAction(ReadSpeakAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(listOf(ReadSpeakCompletionSignal.RECOGNITION_COMPLETE, ReadSpeakCompletionSignal.RECOGNITION_COMPLETE), signals)
        assertTrue(model.uiState.value.completed)
    }

    @Test fun failedFeedbackWithoutRetainedOriginalRestartsCaptureInsteadOfReplaying() = runTest(dispatcher) {
        var requests = 0
        val model = ReadSpeakViewModel(chapter, "s1", ReadSpeakRepository { _, _ -> requests++; ReadSpeakFeedbackResult.Failed })
        model.onAction(ReadSpeakAction.StartRequested)
        model.onAction(ReadSpeakAction.RecognitionCompleted)
        advanceUntilIdle()
        model.onAction(ReadSpeakAction.RetryRequested)
        assertTrue(model.uiState.value.listening)
        assertEquals(1, requests)
    }

    @Test fun completedTrainingRestoresFeedbackWithoutAnotherRequest() = runTest(dispatcher) {
        val store = MemoryProgress()
        var requests = 0
        val repository = ReadSpeakRepository { _, _ -> requests++; ReadSpeakFeedbackResult.Ready("已有反馈") }
        val model = ReadSpeakViewModel(chapter, "s1", repository, store)
        advanceUntilIdle()
        model.onAction(ReadSpeakAction.StartRequested)
        model.onAction(ReadSpeakAction.ManualFinished)
        advanceUntilIdle()
        model.onAction(ReadSpeakAction.BackRequested)
        advanceUntilIdle()
        val restored = ReadSpeakViewModel(chapter, "s1", repository, store)
        advanceUntilIdle()
        assertTrue(restored.uiState.value.completed)
        assertEquals("已有反馈", restored.uiState.value.feedbackZh)
        restored.onAction(ReadSpeakAction.StartRequested)
        restored.onAction(ReadSpeakAction.ManualFinished)
        advanceUntilIdle()
        assertEquals(1, requests)
    }

    @Test fun failedRestoreDoesNotStartOrOverwriteSavedTraining() = runTest(dispatcher) {
        var requests = 0
        val store = object : LearningProgressStore {
            override suspend fun read(): Map<String, String>? = error("read failed")
            override suspend fun write(values: Map<String, String>): Boolean = error("must not overwrite")
        }
        val model = ReadSpeakViewModel(chapter, "s1", ReadSpeakRepository { _, _ -> requests++; ReadSpeakFeedbackResult.CompletedWithoutAudio }, store)
        advanceUntilIdle()
        assertTrue(model.uiState.value.progressFailed)
        model.onAction(ReadSpeakAction.StartRequested)
        model.onAction(ReadSpeakAction.ManualFinished)
        advanceUntilIdle()
        assertEquals(0, requests)
        assertFalse(model.uiState.value.listening)
    }

    @Test fun saveFailureDoesNotOpenTheNextModuleOrRepeatFeedbackOnRetry() = runTest(dispatcher) {
        val store = MemoryProgress()
        var requests = 0
        val model = ReadSpeakViewModel(chapter, "s1", ReadSpeakRepository { _, _ -> requests++; ReadSpeakFeedbackResult.Ready("已有反馈") }, store)
        advanceUntilIdle()
        store.writable = false
        model.onAction(ReadSpeakAction.StartRequested)
        model.onAction(ReadSpeakAction.ManualFinished)
        advanceUntilIdle()
        val effect = async { model.effects.first() }
        model.onAction(ReadSpeakAction.ContinueRequested)
        advanceUntilIdle()
        assertFalse(effect.isCompleted)
        store.writable = true
        model.onAction(ReadSpeakAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(ReadSpeakEffect.OpenHandwriting, effect.await())
        assertEquals(1, requests)
    }

    private class MemoryProgress : LearningProgressStore {
        var payload: Map<String, String>? = null
        var writable = true
        override suspend fun read() = payload
        override suspend fun write(values: Map<String, String>): Boolean {
            if (!writable) return false
            payload = values.toMap()
            return true
        }
    }

    @Test fun failedCaptureStillAllowsExplicitUserConfirmationWithoutAnAudioScore() = runTest(dispatcher) {
        val signals = mutableListOf<ReadSpeakCompletionSignal>()
        val model = ReadSpeakViewModel(chapter, "s1", ReadSpeakRepository { _, signal -> signals += signal; ReadSpeakFeedbackResult.CompletedWithoutAudio })
        model.onAction(ReadSpeakAction.PermissionOrRecognitionFailed)
        model.onAction(ReadSpeakAction.ManualFinished)
        advanceUntilIdle()
        assertEquals(listOf(ReadSpeakCompletionSignal.MANUAL_CONFIRMATION), signals)
        assertTrue(model.uiState.value.completed)
        assertFalse(model.uiState.value.captureFailed)
        assertEquals(null, model.uiState.value.feedbackZh)
    }

    @Test fun oldGenericNoAudioCopyIsRemovedWhenSavedProgressIsRestored() = runTest(dispatcher) {
        val store = MemoryProgress().apply {
            payload = mapOf(
                "version" to "1",
                "completed" to "true",
                "highlightedWordCount" to "4",
                "feedbackZh" to READ_SPEAK_NO_AUDIO_COMPLETION_COPY,
                "interrupted" to "false",
            )
        }
        val model = ReadSpeakViewModel(
            chapter,
            "s1",
            ReadSpeakRepository { _, _ -> error("A restored completion must not request feedback again") },
            store,
        )
        advanceUntilIdle()
        assertTrue(model.uiState.value.completed)
        assertEquals(null, model.uiState.value.feedbackZh)
        assertEquals("", store.payload?.get("feedbackZh"))
    }

    @Test
    fun touchAtSentenceEndWaitsForExplicitRecordedConfirmation() =
        runTest(dispatcher) {
            val signals = mutableListOf<ReadSpeakCompletionSignal>()
            val viewModel = createViewModel(
                repository = ReadSpeakRepository { _, signal ->
                    signals += signal
                    ReadSpeakFeedbackResult.Ready("内容完整，节奏可以更连贯。")
                },
            )
            val effect = async { viewModel.effects.first() }
            viewModel.onAction(ReadSpeakAction.StartRequested)
            viewModel.onAction(ReadSpeakAction.WordReached(3))
            advanceUntilIdle()
            assertTrue(signals.isEmpty())
            assertEquals(4, viewModel.uiState.value.highlightedWordCount)
            assertTrue(viewModel.uiState.value.listening)
            assertFalse(viewModel.uiState.value.completed)
            viewModel.onAction(ReadSpeakAction.ManualFinished)
            advanceUntilIdle()
            assertEquals(listOf(ReadSpeakCompletionSignal.MANUAL_CONFIRMATION), signals)
            assertTrue(viewModel.uiState.value.completed)
            viewModel.onAction(ReadSpeakAction.ContinueRequested)
            advanceUntilIdle()
            assertEquals(ReadSpeakEffect.OpenHandwriting, effect.await())
        }

    @Test
    fun recognitionCanCompleteBeforeTouchAndReturnsObjectiveFeedback() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(ReadSpeakAction.StartRequested)
            viewModel.onAction(ReadSpeakAction.WordReached(1))
            viewModel.onAction(ReadSpeakAction.RecognitionCompleted)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.completed)
            assertEquals("内容完整。", viewModel.uiState.value.feedbackZh)
            assertFalse(viewModel.uiState.value.listening)
        }

    @Test
    fun permissionFailureKeepsStepAndRetryStartsAgain() {
        val viewModel = createViewModel()
        viewModel.onAction(ReadSpeakAction.StartRequested)
        viewModel.onAction(ReadSpeakAction.PermissionOrRecognitionFailed)
        assertTrue(viewModel.uiState.value.captureFailed)
        assertFalse(viewModel.uiState.value.completed)
        viewModel.onAction(ReadSpeakAction.RetryRequested)
        assertTrue(viewModel.uiState.value.listening)
        assertFalse(viewModel.uiState.value.captureFailed)
    }

    @Test
    fun manualCompletionUsesItsOwnSignalWhenBothAutomaticSignalsFail() =
        runTest(dispatcher) {
            var signal: ReadSpeakCompletionSignal? = null
            val viewModel = createViewModel(
                repository = ReadSpeakRepository { _, received ->
                    signal = received
                    ReadSpeakFeedbackResult.Ready("已记录本次朗读。")
                },
            )
            viewModel.onAction(ReadSpeakAction.StartRequested)
            viewModel.onAction(ReadSpeakAction.ManualFinished)
            advanceUntilIdle()
            assertEquals(ReadSpeakCompletionSignal.MANUAL_CONFIRMATION, signal)
        }

    @Test
    fun incompleteRecognitionStaysOnTheStepWithoutGrantingCompletion() =
        runTest(dispatcher) {
            val viewModel = createViewModel(
                repository = ReadSpeakRepository { _, _ ->
                    ReadSpeakFeedbackResult.Ready("请把整句读完整。", contentComplete = false)
                },
            )
            viewModel.onAction(ReadSpeakAction.StartRequested)
            viewModel.onAction(ReadSpeakAction.RecognitionCompleted)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.completed)
            assertTrue(viewModel.uiState.value.captureFailed)
            assertEquals("请把整句读完整。", viewModel.uiState.value.feedbackZh)
        }

    private fun createViewModel(
        repository: ReadSpeakRepository = ReadSpeakRepository { _, _ ->
            ReadSpeakFeedbackResult.Ready("内容完整。")
        },
    ) = ReadSpeakViewModel(chapter, "s1", repository)
}
