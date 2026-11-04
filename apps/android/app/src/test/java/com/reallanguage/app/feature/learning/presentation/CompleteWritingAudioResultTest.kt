package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompleteWritingAudioResultTest {
    private val dispatcher = StandardTestDispatcher()
    private val first = FixedLearningSentence("s1", "I write every day.", "我每天写字。", true)
    private val second = FixedLearningSentence("s2", "I read every night.", "我每晚读书。", true)
    private var saves = 0
    private var savedResult: FinalWritingBatchResult? = null

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun failedAudiblePlaybackStopsShowsFailureAndCanRetryWithoutInventingAResult() = runTest(dispatcher) {
        val model = model()
        val initialRequest = model.uiState.value.audioRequestId
        model.onAction(CompleteWritingAction.AudioPlaybackFinished(initialRequest, successful = false))
        assertFalse(model.uiState.value.audioPlaying)
        assertTrue(model.uiState.value.audioFailed)
        assertFalse(model.uiState.value.helpUsed)
        assertNull(model.uiState.value.currentAttemptCorrect)
        assertNull(model.uiState.value.finalResult)
        assertEquals(0, saves)
        model.onAction(CompleteWritingAction.AudioToggled)
        assertTrue(model.uiState.value.audioPlaying)
        assertFalse(model.uiState.value.audioFailed)
        assertTrue(model.uiState.value.audioRequestId > initialRequest)
        model.onAction(CompleteWritingAction.AudioPlaybackFinished(model.uiState.value.audioRequestId, successful = true))
        assertFalse(model.uiState.value.audioPlaying)
        assertFalse(model.uiState.value.audioFailed)
        assertEquals(0, saves)
    }

    @Test fun failedFinalAudioDoesNotUndoTheDocumentedHelpClickOrSaveAnIndependentResult() = runTest(dispatcher) {
        val model = model()
        trainAndNext(model, first.textEn); trainAndNext(model, first.textEn)
        assertEquals(CompleteWritingPhase.FINAL_BATCH, model.uiState.value.phase)
        assertFalse(model.uiState.value.audioPlaying)
        model.onAction(CompleteWritingAction.FinalAudioRequested("s1"))
        assertTrue(model.uiState.value.helpUsed)
        model.onAction(CompleteWritingAction.AudioPlaybackFinished(model.uiState.value.audioRequestId, successful = false))
        assertFalse(model.uiState.value.audioPlaying)
        assertTrue(model.uiState.value.audioFailed)
        assertTrue(model.uiState.value.helpUsed)
        assertNull(model.uiState.value.finalResult)
        assertEquals(0, saves)
        model.onAction(CompleteWritingAction.FinalInputChanged("s1", first.textEn))
        model.onAction(CompleteWritingAction.SubmitRequested)
        advanceUntilIdle()
        assertEquals(1, saves)
        assertEquals(FinalWritingBatchResult.SupportedLearningOnly, savedResult)
    }

    @Test fun aCancelledPriorPlayCannotStopOrMarkTheNewAttemptAsFailed() {
        val model = model()
        val previous = model.uiState.value.audioRequestId
        model.onAction(CompleteWritingAction.AudioToggled)
        model.onAction(CompleteWritingAction.AudioToggled)
        val current = model.uiState.value.audioRequestId
        assertTrue(current > previous)
        model.onAction(CompleteWritingAction.AudioPlaybackFinished(previous, successful = false))
        assertTrue(model.uiState.value.audioPlaying)
        assertFalse(model.uiState.value.audioFailed)
        model.onAction(CompleteWritingAction.AudioPlaybackFinished(current, successful = true))
        assertFalse(model.uiState.value.audioPlaying)
        assertFalse(model.uiState.value.audioFailed)
    }

    @Test fun thePreviousSentenceOutcomeCannotStopTheNextSentencesPlayback() {
        val model = model(listOf(first, second))
        val previous = model.uiState.value.audioRequestId
        trainAndNext(model, first.textEn); trainAndNext(model, first.textEn)
        assertEquals("s2", model.uiState.value.currentFocusSentenceId)
        assertTrue(model.uiState.value.audioPlaying)
        assertTrue(model.uiState.value.audioRequestId > previous)
        model.onAction(CompleteWritingAction.AudioPlaybackFinished(previous, successful = false))
        assertTrue(model.uiState.value.audioPlaying)
        assertFalse(model.uiState.value.audioFailed)
        assertFalse(model.uiState.value.helpUsed)
    }

    private fun model(sentences: List<FixedLearningSentence> = listOf(first)): CompleteWritingViewModel {
        val comparator = FixedWritingComparator()
        return CompleteWritingViewModel(FixedLearningChapter("audio-result", 1, sentences), emptyList(), comparator,
            CompleteWritingEvaluator(comparator), CompleteWritingRepository { _, result ->
                saves++; savedResult = result; CompleteWritingSaveResult.Saved
            })
    }
    private fun trainAndNext(model: CompleteWritingViewModel, input: String) {
        model.onAction(CompleteWritingAction.InputChanged(input))
        model.onAction(CompleteWritingAction.SubmitRequested)
        model.onAction(CompleteWritingAction.NextRequested)
    }
}
