package com.reallanguage.app.core.app

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.junit.Assert.*
import org.junit.Test

class LearningJourneyTest {
    private val chapter = FixedLearningChapter("chapter", 1, listOf(
        FixedLearningSentence("context", "Background.", "背景。", false),
        FixedLearningSentence("flow:s1", "First.", "第一句。", true),
        FixedLearningSentence("context-middle", "Context.", "语境。", false),
        FixedLearningSentence("flow:s2", "Second.", "第二句。", true),
        FixedLearningSentence("flow:s3", "Third.", "第三句。", true),
    ))

    @Test fun completedReviewRetainsActualChapterAndReadOnlySourceContext() {
        val actualChapter = chapter.copy(chapterNumber = 3)
        val snapshot = completedStepSnapshot(actualChapter, "flow:s2", "LR-02", "基础讲解", "已保存讲解")
        assertEquals("第 3 章 · 基础讲解", snapshot.titleZh)
        assertEquals(actualChapter.sentences, snapshot.contextSentences)
        assertEquals("flow:s2", snapshot.activeSentenceId)
        assertEquals("Second.", snapshot.contentEn)
        assertEquals("第二句。", snapshot.contentZh)
        assertNull("Read-only viewing must not overwrite the current learning pointer", learningCheckpoint(HomeDiaryRoute.CompletedStepReview(snapshot, HomeDiaryRoute.Home)))
    }

    @Test fun everyFocusCompletesEachRequiredStageBeforeChapterWriting() {
        val expected = listOf(
            "LR-02:flow:s1", "LR-02:flow:s2", "LR-02:flow:s3",
            "LR-03:flow:s1", "LR-04:flow:s1", "LR-03:flow:s2", "LR-04:flow:s2", "LR-03:flow:s3", "LR-04:flow:s3",
            "LR-05:flow:s1", "LR-05:flow:s2", "LR-05:flow:s3",
            "LR-06:flow:s1", "LR-06:flow:s2", "LR-06:flow:s3",
            "LR-07:flow:s1", "LR-07:flow:s2", "LR-07:flow:s3",
            "LR-08:flow:s1",
        )
        var current = HomeDiaryRoute.ResumeLearning(chapter.chapterId, "LR-02", "flow:s1")
        val actual = mutableListOf("${current.moduleId}:${current.stepId}")
        repeat(30) {
            if (current.moduleId == "LR-08") return@repeat
            current = requireNotNull(nextLearningStep(chapter, requireNotNull(current.stepId), current.moduleId))
            actual.add("${current.moduleId}:${current.stepId}")
        }
        assertEquals(expected, actual)
    }

    @Test fun contextUnknownAndEmptyChaptersCannotAdvanceAsFocusWork() {
        assertNull(nextLearningStep(chapter, "context", "LR-02"))
        assertNull(nextLearningStep(chapter, "missing", "LR-04"))
        assertNull(nextLearningStep(chapter.copy(sentences = emptyList()), "flow:s1", "LR-06"))
        assertNull(nextLearningStep(chapter, "flow:s1", "invalid"))
    }

    @Test fun oneFocusKeepsTheExistingModuleSequence() {
        val single = chapter.copy(sentences = chapter.sentences.take(2))
        for (module in 2..7) {
            assertEquals(HomeDiaryRoute.ResumeLearning("chapter", "LR-0${module + 1}", "flow:s1"), nextLearningStep(single, "flow:s1", "LR-0$module"))
        }
    }
}
