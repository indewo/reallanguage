package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.*
import org.junit.Assert.*
import org.junit.Test

class FormationOperationIdentityTest {
    @Test fun sourceSessionSurvivesEveryForwardAndBackwardFormationStage() {
        val split = HomeDiaryRoute.SemanticSplit("原文", entryId = "source-session")
        val focus = homeDiaryRoute(split, HomeDiaryEvent.SplitReady(split.text, units))
        assertEquals(split.entryId, formationSourceEntryId(focus))
        var current = focus
        for (next in stages()) {
            current = preserveFormationOrigin(current, next)
            assertEquals("Lost identity at ${next::class.simpleName}", "source-session", formationSourceEntryId(current))
        }
        assertEquals("source-session", formationSourceEntryId(preserveFormationOrigin(current, focus)))
    }

    @Test fun chapterRequestReconstructionRetainsCommandAndCanonicalUnitOrder() {
        val route = chapter()
        assertEquals(chapterFormationCommandId(route), chapterFormationCommandId(route.copy()))
        assertEquals(chapterFormationCommandId(route), chapterFormationCommandId(route.copy(units = units.reversed())))
        assertEquals(chapterFormationCommandId(route), chapterFormationCommandId(route.copy(sourceReturnRoute = HomeDiaryRoute.Input("原文"))))
        assertEquals(chapterFormationCommandId(route), chapterFormationCommandId(route.copy(units = units.map { it.copy(recommendationReasonZh = "另一个推荐说明") })))
    }

    @Test fun completeChapterInputAndNewSourceSessionCannotShareAnOperation() {
        val route = chapter()
        val changed = listOf(route.copy(sourceEntryId = "new-submission"), route.copy(selectedUnitIds = listOf("u2")),
            route.copy(units = units.map { if (it.unitId == "u2") it.copy(textZh = "修改后的第二句。") else it }))
        for (next in changed) assertNotEquals(chapterFormationCommandId(route), chapterFormationCommandId(next))
        assertEquals(chapterFormationCommandId(route), chapterFormationCommandId(route.copy()))
    }

    @Test fun englishGenerationIncludesActualChapterMembershipAndKeepsRetryStable() {
        val route = english()
        assertEquals(englishGenerationCommandId(route), englishGenerationCommandId(route.copy()))
        assertNotEquals(englishGenerationCommandId(route), englishGenerationCommandId(route.copy(chapters = listOf(ChapterDraft("chapter-new", listOf("u1", "u2"))))))
        assertNotEquals(englishGenerationCommandId(route), englishGenerationCommandId(route.copy(chapters = listOf(ChapterDraft("chapter", listOf("u2", "u1"))))))
        assertNotEquals(englishGenerationCommandId(route), englishGenerationCommandId(route.copy(sourceEntryId = "new-submission")))
    }

    @Test fun targetRequestIgnoresMapInsertionOrderAndUnusedCandidateAlternatives() {
        val route = targets()
        val reordered = route.copy(selections = linkedMapOf("u2" to "c2", "u1" to "c1"))
        assertEquals(learningTargetExtractionCommandId(route), learningTargetExtractionCommandId(reordered))
        val unselectedChanged = route.copy(chapters = route.chapters.map { chapter -> chapter.copy(sentences = chapter.sentences.map { sentence ->
            sentence.copy(candidates = sentence.candidates + EnglishCandidate("unused", "Unused alternative."))
        }) })
        assertEquals(learningTargetExtractionCommandId(route), learningTargetExtractionCommandId(unselectedChanged))
    }

    @Test fun targetRequestBindsSelectedEnglishFocusAndCurrentSourceSession() {
        val route = targets()
        val changedEnglish = route.copy(chapters = route.chapters.map { chapter -> chapter.copy(sentences = chapter.sentences.map { sentence ->
            if (sentence.unitId == "u1") sentence.copy(candidates = listOf(EnglishCandidate("c1", "New confirmed English."))) else sentence
        }) })
        assertNotEquals(learningTargetExtractionCommandId(route), learningTargetExtractionCommandId(changedEnglish))
        assertNotEquals(learningTargetExtractionCommandId(route), learningTargetExtractionCommandId(route.copy(sourceEntryId = "new-submission")))
        assertEquals(learningTargetExtractionCommandId(route), learningTargetExtractionCommandId(route.copy()))
    }

    private val units = listOf(SemanticUnit("u1", 0, "第一句。"), SemanticUnit("u2", 1, "第二句。"))
    private val chapters = listOf(EnglishChapterDraft("chapter", listOf(
        EnglishSentenceDraft("u1", "第一句。", true, listOf(EnglishCandidate("c1", "First sentence."))),
        EnglishSentenceDraft("u2", "第二句。", false, listOf(EnglishCandidate("c2", "Second sentence."))),
    )))
    private fun chapter() = HomeDiaryRoute.ChapterFormation("原文", units, listOf("u1"), sourceEntryId = "source-session")
    private fun english() = HomeDiaryRoute.EnglishGeneration("原文", units, listOf("u1"), listOf(ChapterDraft("chapter", listOf("u1", "u2"))), sourceEntryId = "source-session")
    private fun targets() = HomeDiaryRoute.LearningTargets("原文", units, chapters, linkedMapOf("u1" to "c1", "u2" to "c2"), sourceEntryId = "source-session")
    private fun stages(): List<HomeDiaryRoute> = listOf(chapter(), english(), HomeDiaryRoute.EnglishConfirmation("原文", units, chapters), targets(),
        HomeDiaryRoute.LearningTargetSelection("原文", units, chapters, emptyMap(), emptyList(), emptyList()),
        HomeDiaryRoute.FinalFix("原文", units, chapters, emptyMap(), emptyList(), emptySet()))
}
