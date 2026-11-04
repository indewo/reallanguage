package com.reallanguage.app.core.app

import com.reallanguage.app.feature.learning.domain.*
import org.junit.Assert.*
import org.junit.Test

class LearningResumeRouteTest {
    @Test fun chapterEntryStartContinuesTheSavedStepInsteadOfRestartingTheFirstSentence() {
        assertEquals(HomeDiaryRoute.ResumeLearning("chapter", "LR-05", "flow:s2"), chapterEntryStartRoute(chapter, OrdinaryLearningCheckpoint("chapter", "LR-05", "flow:s2")))
        assertTrue(chapterEntryStartRoute(chapter, null) is HomeDiaryRoute.ChapterExplanation)
        assertNull(chapterEntryStartRoute(chapter, OrdinaryLearningCheckpoint("other", "LR-05", "flow:s2")))
        assertNull(chapterEntryStartRoute(chapter, OrdinaryLearningCheckpoint("chapter", "LR-05", "missing")))
    }
    private val chapter = FixedLearningChapter("chapter", 1, listOf(
        FixedLearningSentence("flow:s1", "First.", "第一句。", true),
        FixedLearningSentence("flow:s2", "Second.", "第二句。", true),
        FixedLearningSentence("flow:context", "Context.", "背景。", false),
    ))
    private val explanation = ExplanationPreparationResult.Ready("已保存的基础讲解", listOf(SelectableExpression("word-0", "Second", 0, 6)))

    @Test fun scopedSentenceIdentityIsNotMistakenForAStepPrefix() {
        val route = learningResumeRoute(chapter, "LR-03", "flow:s2", explanation) as HomeDiaryRoute.ReadSpeak
        assertEquals("flow:s2", route.focusSentenceId)
        assertEquals(explanation.baseExplanationZh, route.baseExplanationZh)
        assertEquals(explanation.expressions, route.expressions)
    }

    @Test fun restoringCorrectionKeepsTheOriginalFailedInputForItsReturnPath() {
        val route = learningResumeRoute(chapter, "LR-05", "correction:flow:s2", explanation, "unfinished dictation") as HomeDiaryRoute.ReadWriteCorrection
        assertEquals("flow:s2", route.focusSentenceId)
        assertEquals("unfinished dictation", route.dictationInput)
    }

    @Test fun unknownAndContextOnlyPointersDoNotSilentlyOpenTheFirstFocus() {
        assertNull(learningResumeRoute(chapter, "LR-03", "missing", explanation))
        assertNull(learningResumeRoute(chapter, "LR-03", "flow:context", explanation))
    }

    @Test fun recognizedPrefixPreservesTheEntireScopedSentenceId() {
        val route = learningResumeRoute(chapter, "LR-02", "explanation:flow:s2", null) as HomeDiaryRoute.ChapterExplanation
        assertEquals("flow:s2", route.focusSentenceId)
    }

    @Test fun missingExplanationCannotPretendToBeACompleteRestoredContext() {
        assertNull(learningResumeRoute(chapter, "LR-03", "flow:s2", null))
        assertTrue(learningResumeRoute(chapter, "LR-01", "entry", null) is HomeDiaryRoute.ChapterLearning)
    }
}
