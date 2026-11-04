package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.SemanticUnit
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import org.junit.Assert.*
import org.junit.Test

class FormationSourceNavigationTest {
    @Test fun passedRecordingCarriesItsOriginalEditorAndCheckIdentityIntoSplitAndFocus() {
        val editor = HomeDiaryRoute.TranscriptResult("recording-current", "transcript-current")
        val safety = HomeDiaryRoute.SafetyCheck("确认后的原文。", SourceType.RECORDING, returnRoute = editor, entryId = "checked-source")
        val split = homeDiaryRoute(safety, HomeDiaryEvent.SafetyPassed(safety.text)) as HomeDiaryRoute.SemanticSplit
        assertEquals(editor, split.sourceReturnRoute)
        assertEquals(safety.entryId, split.entryId)
        val focus = homeDiaryRoute(split, HomeDiaryEvent.SplitReady(split.text, units)) as HomeDiaryRoute.FocusSelection
        assertEquals(editor, focus.sourceReturnRoute)
        assertEquals(FormationReturnTarget(editor), formationReturnTarget(focus))
    }

    @Test fun diaryBackReturnsEditableCurrentContentNotAnAutomaticSafetyOrSplitLoop() {
        val split = HomeDiaryRoute.SemanticSplit("用户确认的脱敏内容。", sourceReturnRoute = HomeDiaryRoute.Input("原输入。"))
        val expected = FormationReturnTarget(HomeDiaryRoute.Input(split.text))
        assertEquals(expected, formationReturnTarget(split))
        assertEquals(expected, formationReturnTarget(HomeDiaryRoute.FocusSelection(split.text, units, sourceReturnRoute = split.sourceReturnRoute)))
    }

    @Test fun approvedReviewOriginRemainsAccessibleWithoutGuessingRecordingOrSceneIdentity() {
        val origin = HomeDiaryRoute.ManualReviewResult("review-current", "result-entry")
        for (type in listOf(SourceType.RECORDING, SourceType.AI_SCENE)) {
            val split = HomeDiaryRoute.SemanticSplit("通过审核的内容。", type, sourceReturnRoute = origin)
            assertEquals(FormationReturnTarget(origin), formationReturnTarget(split))
        }
    }

    @Test fun unknownSourceDoesNotPretendToBeADiaryAndOffersAnExplicitMessagesFallback() {
        val unknown = HomeDiaryRoute.FocusSelection("旧版场景。", units, SourceType.AI_SCENE, SourceContentShape.DIALOGUE)
        val target = formationReturnTarget(unknown)
        assertTrue(target.editorUnavailable)
        assertTrue(target.route is HomeDiaryRoute.Messages)
        assertFalse(target.route is HomeDiaryRoute.Input)
        assertFalse(target.route is HomeDiaryRoute.SafetyCheck)
    }

    @Test fun everyFormationStageRetainsTheExactSourceNavigationMetadata() {
        val origin = HomeDiaryRoute.TranscriptResult("recording-a", "transcript-a")
        val routes = listOf(
            HomeDiaryRoute.SemanticSplit("正文"),
            HomeDiaryRoute.FocusSelection("正文", units),
            HomeDiaryRoute.ChapterFormation("正文", units, listOf("u1")),
            HomeDiaryRoute.EnglishGeneration("正文", units, listOf("u1"), emptyList()),
            HomeDiaryRoute.EnglishConfirmation("正文", units, emptyList()),
            HomeDiaryRoute.LearningTargets("正文", units, emptyList(), emptyMap()),
            HomeDiaryRoute.LearningTargetSelection("正文", units, emptyList(), emptyMap(), emptyList(), emptyList()),
            HomeDiaryRoute.FinalFix("正文", units, emptyList(), emptyMap(), emptyList(), emptySet()),
        )
        for (route in routes) {
            val preserved = withFormationSourceReturnRoute(route, origin)
            assertEquals("Lost origin for ${route::class.simpleName}", origin, formationSourceReturnRoute(preserved))
            assertEquals(preserved, withFormationSourceReturnRoute(preserved, origin))
        }
    }

    @Test fun semanticSplitRetryUsesStableFullSourceIdentityButANewConfirmedSubmissionDoesNot() {
        val route = HomeDiaryRoute.SemanticSplit("FB", entryId = "passed-source")
        assertEquals(semanticSplitCommandId(route), semanticSplitCommandId(route.copy()))
        assertEquals(semanticSplitCommandId(route), semanticSplitCommandId(route.copy(sourceReturnRoute = HomeDiaryRoute.Input("FB"))))
        assertNotEquals(semanticSplitCommandId(route), semanticSplitCommandId(route.copy(text = "Ea")))
        assertNotEquals(semanticSplitCommandId(route), semanticSplitCommandId(route.copy(entryId = "new-source")))
        assertNotEquals(HomeDiaryRoute.SemanticSplit("同文").entryId, HomeDiaryRoute.SemanticSplit("同文").entryId)
    }

    @Test fun everyGeneratedStageCanWalkBackToFocusAndThenTheOriginalEditor() {
        val editor = HomeDiaryRoute.Input("原文。")
        val chapters = listOf(EnglishChapterDraft("c1", listOf(
            EnglishSentenceDraft("u1", "第一句。", true, listOf(EnglishCandidate("e1", "First."))),
        )))
        val routes = listOf<HomeDiaryRoute>(
            HomeDiaryRoute.ChapterFormation("原文。", units, listOf("u1"), sourceReturnRoute = editor, sourceEntryId = "source"),
            HomeDiaryRoute.EnglishGeneration("原文。", units, listOf("u1"), emptyList(), sourceReturnRoute = editor, sourceEntryId = "source"),
            HomeDiaryRoute.EnglishConfirmation("原文。", units, chapters, sourceReturnRoute = editor, sourceEntryId = "source"),
            HomeDiaryRoute.LearningTargets("原文。", units, chapters, mapOf("u1" to "e1"), sourceReturnRoute = editor, sourceEntryId = "source"),
            HomeDiaryRoute.LearningTargetSelection("原文。", units, chapters, mapOf("u1" to "e1"), emptyList(), emptyList(), sourceReturnRoute = editor, sourceEntryId = "source"),
            HomeDiaryRoute.FinalFix("原文。", units, chapters, mapOf("u1" to "e1"), emptyList(), emptySet(), sourceReturnRoute = editor, sourceEntryId = "source"),
        )
        for (start in routes) {
            var current = start
            repeat(3) {
                current = formationPreviousRoute(current) ?: current
            }
            val focus = current as HomeDiaryRoute.FocusSelection
            assertEquals("source", focus.sourceEntryId)
            assertEquals(FormationReturnTarget(HomeDiaryRoute.Input(focus.text)), formationReturnTarget(focus))
        }
    }

    private val units = listOf(SemanticUnit("u1", 0, "第一句。"), SemanticUnit("u2", 1, "第二句。"))
}
