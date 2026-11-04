package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.SemanticUnit
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.feature.billing.domain.BalanceOriginState
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDiarySubflowTest {
    @Test fun reopeningExplanationDoesNotReuseAnAlreadyConsumedPreparation() {
        val chapter = FixedLearningChapter("chapter", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true)))
        org.junit.Assert.assertNotEquals(HomeDiaryRoute.ChapterExplanation(chapter, "s1"), HomeDiaryRoute.ChapterExplanation(chapter, "s1"))
        org.junit.Assert.assertNotEquals(HomeDiaryRoute.ExplanationReading(chapter, "s1", "基础", emptyList()), HomeDiaryRoute.ExplanationReading(chapter, "s1", "基础", emptyList()))
    }
    @Test fun reenteringTargetSelectionDoesNotReuseSubmittedScreenIdentity() {
        fun selection() = HomeDiaryRoute.LearningTargetSelection("同一内容", emptyList(), emptyList(), emptyMap(), emptyList(), emptyList())
        org.junit.Assert.assertNotEquals(selection(), selection())
    }
    @Test fun submitsDiaryDirectlyToSafetyWithoutAnIntermediateProcessingPage() {
        assertEquals(
            HomeDiaryRoute.Input(),
            homeDiaryRoute(HomeDiaryRoute.Home, HomeDiaryEvent.OpenDiary),
        )
        assertEquals(
            HomeDiaryRoute.SafetyCheck("保留的日记。", entryId = "ignored"),
            (homeDiaryRoute(HomeDiaryRoute.Input(), HomeDiaryEvent.Submit("保留的日记。")) as HomeDiaryRoute.SafetyCheck)
                .copy(entryId = "ignored"),
        )
        val safety = homeDiaryRoute(HomeDiaryRoute.Input(), HomeDiaryEvent.Submit("保留的日记。")) as HomeDiaryRoute.SafetyCheck
        assertEquals(HomeDiaryRoute.SafetyCheck("保留的日记。", entryId = safety.entryId), safety)
        org.junit.Assert.assertTrue(safety.entryId.isNotBlank())
        assertEquals(
            HomeDiaryRoute.Home,
            homeDiaryRoute(HomeDiaryRoute.Input("保留的日记。"), HomeDiaryEvent.ReturnHome),
        )
    }

    @Test fun routesPassedSafetyThroughSplitIntoFocusSelection() {
        val source = "保留的日记。"
        val units = listOf(SemanticUnit("unit-1", 0, source))
        val safety = HomeDiaryRoute.SafetyCheck(source, entryId = "confirmed-source")
        assertEquals(
            HomeDiaryRoute.SemanticSplit(source, sourceReturnRoute = HomeDiaryRoute.Input(source), entryId = safety.entryId),
            homeDiaryRoute(safety, HomeDiaryEvent.SafetyPassed(source)),
        )
        val split = HomeDiaryRoute.SemanticSplit(source, entryId = "split-source")
        assertEquals(HomeDiaryRoute.FocusSelection(source, units, sourceEntryId = split.entryId),
            homeDiaryRoute(split, HomeDiaryEvent.SplitReady(source, units)))
    }

    @Test fun everyProductionLearningRouteProducesAnExplicitCheckpoint() {
        val chapter = FixedLearningChapter(
            "chapter-1",
            1,
            listOf(FixedLearningSentence("sentence-1", "English.", "中文。", true)),
        )
        assertEquals(
            OrdinaryLearningCheckpoint("chapter-1", "LR-01", "entry"),
            learningCheckpoint(HomeDiaryRoute.ChapterLearning("chapter-1")),
        )
        assertEquals(
            OrdinaryLearningCheckpoint("chapter-1", "LR-08", "sentence-1"),
            learningCheckpoint(
                HomeDiaryRoute.CompleteWriting(chapter, "sentence-1", "", emptyList()),
            ),
        )
        assertEquals(
            OrdinaryLearningCheckpoint("chapter-1", "LR-09", "sentence-1"),
            learningCheckpoint(
                HomeDiaryRoute.SceneResponse(chapter, "sentence-1", "", emptyList()),
            ),
        )
        assertEquals(null, learningCheckpoint(HomeDiaryRoute.Home))
    }

    @Test fun completedStepSnapshotUsesOnlyTheLockedFocusSentence() {
        val chapter = FixedLearningChapter(
            "chapter-1",
            1,
            listOf(
                FixedLearningSentence("context", "Context.", "语境。", false),
                FixedLearningSentence("focus", "I can explain it.", "我能解释它。", true),
            ),
        )

        val snapshot = completedStepSnapshot(
            chapter,
            "focus",
            "LR-02",
            "基础讲解与三轮朗读",
            "这是已交付的讲解。",
        )

        assertEquals("I can explain it.", snapshot.contentEn)
        assertEquals("我能解释它。", snapshot.contentZh)
        assertEquals("这是已交付的讲解。", snapshot.explanationZh)
    }

    @Test fun balanceFailuresUseTheConfirmedFiveOriginPolicies() {
        val chapter = FixedLearningChapter(
            "chapter-1",
            1,
            listOf(FixedLearningSentence("sentence-1", "English.", "中文。", true)),
        )
        assertEquals(
            BalanceOriginState.RECORDING_AWAITING_TRANSCRIPTION,
            balanceOriginState(HomeDiaryRoute.RecordingTranscription("recording-1", 30)),
        )
        assertEquals(
            BalanceOriginState.UNIFIED_REVIEW,
            balanceOriginState(HomeDiaryRoute.ReviewChapter("review-1")),
        )
        assertEquals(
            BalanceOriginState.FIXED_UNSTARTED_CHAPTER,
            balanceOriginState(HomeDiaryRoute.ChapterExplanation(chapter, "sentence-1")),
        )
        assertEquals(
            BalanceOriginState.NORMAL_LEARNING,
            balanceOriginState(HomeDiaryRoute.ReadSpeak(chapter, "sentence-1", "", emptyList())),
        )
        assertEquals(
            BalanceOriginState.UNFIXED_DIARY_OR_AI_SCENE,
            balanceOriginState(HomeDiaryRoute.SceneHome()),
        )
    }
}
