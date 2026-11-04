package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompleteWritingRestoreAudioTest {
    private val dispatcher = StandardTestDispatcher()
    private val chapter = FixedLearningChapter("restore-writing", 1,
        listOf(FixedLearningSentence("s1", "I write every day.", "我每天写字。", true)))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun pendingRestoreCannotPlayTheDefaultFirstSentenceBeforeTheSavedSilentBatchIsKnown() = runTest(dispatcher) {
        val response = CompletableDeferred<Map<String, String>>()
        val saved = mapOf("version" to "1", "phase" to "FINAL_BATCH", "sentenceIndex" to "0", "input" to "",
            "helpUsed" to "false", "attemptCorrect" to "", "answer:s1" to "unfinished retained answer")
        val model = model(object : LearningProgressStore {
            override suspend fun read() = response.await()
            override suspend fun write(values: Map<String, String>) = true
        })
        try {
            runCurrent()
            assertTrue(model.uiState.value.saving)
            assertFalse("Unrestored default content must not expose the answer through audio", model.uiState.value.audioPlaying)
        } finally { response.complete(saved) }
        advanceUntilIdle()
        assertEquals(CompleteWritingPhase.FINAL_BATCH, model.uiState.value.phase)
        assertEquals("unfinished retained answer", model.uiState.value.finalInputs["s1"])
        assertFalse(model.uiState.value.audioPlaying)
        assertFalse(model.uiState.value.helpUsed)
    }

    @Test fun aFailedRestoreRemainsSilentAndCannotExposeAnUnconfirmedAnswerOrOverwriteProgress() = runTest(dispatcher) {
        var writes = 0
        val model = model(object : LearningProgressStore {
            override suspend fun read(): Map<String, String>? = error("isolated restore failure")
            override suspend fun write(values: Map<String, String>): Boolean { writes++; return true }
        })
        advanceUntilIdle()
        assertTrue(model.uiState.value.saveFailed)
        assertFalse(model.uiState.value.saving)
        assertFalse(model.uiState.value.audioPlaying)
        model.onAction(CompleteWritingAction.AudioToggled)
        assertFalse(model.uiState.value.audioPlaying)
        assertFalse(model.uiState.value.helpUsed)
        assertEquals(0, writes)
        assertNull(model.uiState.value.finalResult)
    }

    private fun model(store: LearningProgressStore): CompleteWritingViewModel {
        val comparator = FixedWritingComparator()
        return CompleteWritingViewModel(chapter, emptyList(), comparator, CompleteWritingEvaluator(comparator),
            CompleteWritingRepository { _, _ -> error("Restore cannot submit a score") }, store)
    }
}
