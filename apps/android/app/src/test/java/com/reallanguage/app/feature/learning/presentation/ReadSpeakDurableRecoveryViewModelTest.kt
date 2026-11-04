package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReadSpeakDurableRecoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I drink tea.", "我喝茶。", true)))
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun restoredUnknownRequestWaitsForExplicitRetryAndKeepsOriginalSignal() = runTest(dispatcher) {
        val repository = RecoveryRepository(ReadSpeakFeedbackRecovery.Pending(ReadSpeakCompletionSignal.TOUCH_REACHED_END))
        val model = ReadSpeakViewModel(chapter, "s1", repository)
        advanceUntilIdle()
        assertTrue(model.uiState.value.captureFailed)
        assertFalse(model.uiState.value.listening)
        assertEquals(0, repository.signals.size)
        model.onAction(ReadSpeakAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(listOf(ReadSpeakCompletionSignal.TOUCH_REACHED_END), repository.signals)
        assertTrue(model.uiState.value.completed)
        assertFalse("The recovered success must expose Continue, not the failed-capture controls", model.uiState.value.captureFailed)
        assertFalse(model.uiState.value.listening)
    }

    @Test fun committedFeedbackRepairsMissingProgressWithoutRequestOrRecording() = runTest(dispatcher) {
        val repository = RecoveryRepository(ReadSpeakFeedbackRecovery.Completed(ReadSpeakCompletionSignal.RECOGNITION_COMPLETE,
            ReadSpeakFeedbackResult.Ready("已保存的真实反馈")))
        val saved = mutableListOf<Map<String, String>>()
        val progress = object : LearningProgressStore {
            override suspend fun read(): Map<String, String>? = null
            override suspend fun write(values: Map<String, String>): Boolean { saved += values; return true }
        }
        val model = ReadSpeakViewModel(chapter, "s1", repository, progress)
        advanceUntilIdle()
        assertTrue(model.uiState.value.completed)
        assertEquals("已保存的真实反馈", model.uiState.value.feedbackZh)
        assertEquals("true", saved.last()["completed"])
        assertEquals(0, repository.signals.size)
    }

    @Test fun unreadableOriginalCannotBecomeMissingAndStartNewRecording() = runTest(dispatcher) {
        val repository = RecoveryRepository(ReadSpeakFeedbackRecovery.Unavailable)
        val model = ReadSpeakViewModel(chapter, "s1", repository)
        advanceUntilIdle()
        assertTrue(model.uiState.value.progressFailed)
        model.onAction(ReadSpeakAction.StartRequested)
        advanceUntilIdle()
        assertFalse(model.uiState.value.listening)
        assertFalse(model.uiState.value.completed)
        assertEquals(0, repository.signals.size)
    }

    @Test fun restoredBlockedAttemptCannotBecomeNewRecordingThroughRepeatedTechnicalRetry() = runTest(dispatcher) {
        val repository = RecoveryRepository(ReadSpeakFeedbackRecovery.Blocked)
        val model = ReadSpeakViewModel(chapter, "s1", repository)
        advanceUntilIdle()
        assertTrue(model.uiState.value.captureFailed)
        assertFalse(model.uiState.value.listening)
        assertFalse(model.uiState.value.completed)
        repeat(2) { model.onAction(ReadSpeakAction.RetryRequested) }
        advanceUntilIdle()
        assertFalse("A terminal original request requires an explicit new-recording intent", model.uiState.value.listening)
        assertTrue(model.uiState.value.captureFailed)
        assertFalse(model.uiState.value.completed)
        assertEquals("Old Retry must not submit or evaluate anything", 0, repository.signals.size)
    }

    @Test fun restoredBlockedAttemptStillAllowsAnExplicitNewRecording() = runTest(dispatcher) {
        val repository = RecoveryRepository(ReadSpeakFeedbackRecovery.Blocked)
        val model = ReadSpeakViewModel(chapter, "s1", repository)
        advanceUntilIdle()
        assertFalse(model.uiState.value.listening)
        assertEquals(0, repository.signals.size)
        model.onAction(ReadSpeakAction.StartRequested)
        advanceUntilIdle()
        assertTrue(model.uiState.value.listening)
        assertFalse(model.uiState.value.captureFailed)
        assertFalse(model.uiState.value.completed)
        assertEquals("Starting a new recording does not itself submit feedback", 0, repository.signals.size)
        model.onAction(ReadSpeakAction.WordReached(2))
        advanceUntilIdle()
        assertTrue(repository.signals.isEmpty())
        assertTrue(model.uiState.value.listening)
        model.onAction(ReadSpeakAction.ManualFinished)
        advanceUntilIdle()
        assertEquals(listOf(ReadSpeakCompletionSignal.MANUAL_CONFIRMATION), repository.signals)
        assertTrue(model.uiState.value.completed)
    }

    @Test fun ordinaryMicrophoneFailureKeepsTheExistingRecordingRetryAction() = runTest(dispatcher) {
        val repository = RecoveryRepository(ReadSpeakFeedbackRecovery.None)
        val model = ReadSpeakViewModel(chapter, "s1", repository)
        advanceUntilIdle()
        model.onAction(ReadSpeakAction.StartRequested)
        model.onAction(ReadSpeakAction.PermissionOrRecognitionFailed)
        assertTrue(model.uiState.value.captureFailed)
        assertFalse(model.uiState.value.listening)
        model.onAction(ReadSpeakAction.RetryRequested)
        advanceUntilIdle()
        assertTrue("No submitted terminal request exists in an ordinary microphone failure", model.uiState.value.listening)
        assertFalse(model.uiState.value.captureFailed)
        assertFalse(model.uiState.value.completed)
        assertEquals(0, repository.signals.size)
    }

    private class RecoveryRepository(private val recovery: ReadSpeakFeedbackRecovery) : ReadSpeakRepository {
        val signals = mutableListOf<ReadSpeakCompletionSignal>()
        override val requiresFeedbackRecovery = true
        override suspend fun restoreFeedback() = recovery
        override fun hasPendingFeedbackRetry() = recovery is ReadSpeakFeedbackRecovery.Pending
        override suspend fun feedback(sentenceId: String, completionSignal: ReadSpeakCompletionSignal): ReadSpeakFeedbackResult {
            signals += completionSignal
            return ReadSpeakFeedbackResult.Ready("已保存的真实反馈")
        }
    }
}
