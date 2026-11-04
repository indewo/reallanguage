package com.reallanguage.app.feature.formation.domain

import com.reallanguage.app.feature.safety.domain.*
import org.junit.Assert.*
import org.junit.Test

class FormationRecoveryPositionTest {
    @Test fun `second focus position is preserved with its actual current choices`() {
        assertTrue(sample().isValidForRecovery())
        assertEquals("u2", sample().englishChapters.flatMap { it.sentences }.filter { it.isFocus }[sample().targetSentenceIndex].unitId)
    }
    @Test fun `negative or nonexistent focus position is rejected rather than silently clamped`() {
        assertTrue(sample().isValidForRecovery())
        assertFalse(sample().copy(targetSentenceIndex = -1).isValidForRecovery())
        assertFalse(sample().copy(targetSentenceIndex = 2).isValidForRecovery())
    }
    @Test fun `unchanged return can retain actual target position for later forward reuse`() {
        assertTrue(sample().copy(phase = FormationRecoveryPhase.ENGLISH_CONFIRMATION).isValidForRecovery())
    }
    private fun sample() = FormationRecoverySnapshot("position-source", 0, FormationRecoveryPhase.LEARNING_TARGET_SELECTION,
        ConfirmedSource("第一句。第二句。", SourceType.DIARY, SourceContentShape.ARTICLE), "隔离位置", FormationSourceOrigin.Diary,
        units = listOf(SemanticUnit("u1", 0, "第一句。"), SemanticUnit("u2", 1, "第二句。")),
        selectedUnitIds = listOf("u1", "u2"), chapters = listOf(ChapterDraft("c1", listOf("u1", "u2"))),
        englishChapters = listOf(EnglishChapterDraft("c1", listOf(
            EnglishSentenceDraft("u1", "第一句。", true, listOf(EnglishCandidate("e1", "I drink tea."))),
            EnglishSentenceDraft("u2", "第二句。", true, listOf(EnglishCandidate("e2", "I read."))),
        ))), selectedCandidateIds = mapOf("u1" to "e1", "u2" to "e2"), targetSelectionEntryId = "position-targets", targetSentenceIndex = 1)
}
