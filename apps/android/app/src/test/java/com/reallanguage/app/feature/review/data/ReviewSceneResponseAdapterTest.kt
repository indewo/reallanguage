package com.reallanguage.app.feature.review.data

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SceneEvaluationResult
import com.reallanguage.app.feature.learning.domain.SceneResponseRepository
import com.reallanguage.app.feature.learning.domain.SceneResponseSaveResult
import com.reallanguage.app.feature.learning.domain.SceneTaskGenerationResult
import com.reallanguage.app.feature.learning.domain.SceneTrainingFeedbackResult
import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure adapter boundary: no Android context, database, audio, credentials or HTTP. */
class ReviewSceneResponseAdapterTest {
    @Test fun transcriptionRetryFlagTracksTheCurrentDelegateRatherThanTheInitialValue() {
        val delegate = MemorySceneRepository()
        val adapter = ReviewSceneResponseAdapter(delegate)
        assertFalse(adapter.hasPendingTranscriptionRetry())
        delegate.transcriptionPending = true
        assertTrue(adapter.hasPendingTranscriptionRetry())
        assertFalse(adapter.hasPendingEvaluationRetry())
        assertFalse(adapter.technicalRetryBlocked())
        delegate.transcriptionPending = false
        assertFalse(adapter.hasPendingTranscriptionRetry())
    }

    @Test fun evaluationRetryFlagTracksTheCurrentDelegateRatherThanTheInitialValue() {
        val delegate = MemorySceneRepository()
        val adapter = ReviewSceneResponseAdapter(delegate)
        assertFalse(adapter.hasPendingEvaluationRetry())
        delegate.evaluationPending = true
        assertTrue(adapter.hasPendingEvaluationRetry())
        assertFalse(adapter.hasPendingTranscriptionRetry())
        assertFalse(adapter.technicalRetryBlocked())
        delegate.evaluationPending = false
        assertFalse(adapter.hasPendingEvaluationRetry())
    }

    @Test fun terminalRetryFlagTracksTheCurrentDelegateRatherThanTheInitialValue() {
        val delegate = MemorySceneRepository()
        val adapter = ReviewSceneResponseAdapter(delegate)
        assertFalse(adapter.technicalRetryBlocked())
        delegate.blocked = true
        assertTrue(adapter.technicalRetryBlocked())
        assertFalse(adapter.hasPendingTranscriptionRetry())
        assertFalse(adapter.hasPendingEvaluationRetry())
        delegate.blocked = false
        assertFalse(adapter.technicalRetryBlocked())
    }

    @Test fun bothTaskEntryPointsOnlyRestoreFixedTasksAndNeverGenerateOnMissingTasks() = runTest {
        val chapter = FixedLearningChapter("chapter", 1, listOf(
            FixedLearningSentence("sentence", "Please wait here.", "请在这里等。", true),
        ))
        val fixed = SceneTaskGenerationResult.Ready(listOf(
            FixedSceneResponseTask("fixed-task", "sentence", null, emptyList(), "Please wait here."),
        ))
        val delegate = MemorySceneRepository().apply { restored = fixed }
        val adapter = ReviewSceneResponseAdapter(delegate)

        assertSame(fixed, adapter.generateTasks(chapter))
        assertSame(fixed, adapter.restoreTasks(chapter))
        delegate.restored = SceneTaskGenerationResult.Failed
        assertSame(SceneTaskGenerationResult.Failed, adapter.generateTasks(chapter))
        assertSame(SceneTaskGenerationResult.Failed, adapter.restoreTasks(chapter))
        assertEquals(0, delegate.generations)
        assertEquals(4, delegate.restoredChapters.size)
        delegate.restoredChapters.forEach { assertSame(chapter, it) }
    }

    @Test fun reviewDecisionStaysInThisAdapterAndNeverSavesAnOrdinaryLearningResult() = runTest {
        val delegate = MemorySceneRepository()
        val adapter = ReviewSceneResponseAdapter(delegate)
        assertNull(adapter.decision)
        assertSame(SceneResponseSaveResult.Saved,
            adapter.saveFinalDecision("chapter", "sentence", SceneEvaluationDecision.PASS))
        assertEquals(SceneEvaluationDecision.PASS, adapter.decision)
        assertSame(SceneResponseSaveResult.Saved,
            adapter.saveFinalDecision("chapter", "sentence", SceneEvaluationDecision.FAIL))
        assertEquals(SceneEvaluationDecision.FAIL, adapter.decision)
        assertEquals(0, delegate.ordinarySaves)
        assertNull(ReviewSceneResponseAdapter(delegate).decision)
        assertEquals(SceneEvaluationDecision.FAIL, adapter.decision)
    }

    private class MemorySceneRepository : SceneResponseRepository {
        var transcriptionPending = false
        var evaluationPending = false
        var blocked = false
        var restored: SceneTaskGenerationResult = SceneTaskGenerationResult.Failed
        val restoredChapters = mutableListOf<FixedLearningChapter>()
        var generations = 0
        var ordinarySaves = 0

        override fun hasPendingTranscriptionRetry() = transcriptionPending
        override fun hasPendingEvaluationRetry() = evaluationPending
        override fun technicalRetryBlocked() = blocked
        override suspend fun generateTasks(chapter: FixedLearningChapter): SceneTaskGenerationResult {
            generations++
            error("Review must not generate replacement tasks")
        }
        override suspend fun restoreTasks(chapter: FixedLearningChapter): SceneTaskGenerationResult {
            restoredChapters += chapter
            return restored
        }
        override suspend fun saveFinalDecision(
            chapterId: String,
            sentenceId: String,
            decision: SceneEvaluationDecision,
        ): SceneResponseSaveResult {
            ordinarySaves++
            error("Review must not save ordinary learning decisions")
        }
        override fun startCapture(): Boolean = error("No capture in this adapter test")
        override fun cancelCapture() = Unit
        override suspend fun transcribeResponse(taskId: String): SpeakingTranscriptionResult =
            error("No transcription in this adapter test")
        override suspend fun trainingFeedback(
            taskId: String,
            roundIndex: Int,
            confirmedTranscript: String,
        ): SceneTrainingFeedbackResult = error("No feedback in this adapter test")
        override suspend fun evaluateFinalRound(
            taskId: String,
            confirmedTranscript: String,
        ): SceneEvaluationResult = error("No evaluation in this adapter test")
    }
}
