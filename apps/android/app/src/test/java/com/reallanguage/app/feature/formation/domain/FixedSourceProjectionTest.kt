package com.reallanguage.app.feature.formation.domain

import com.reallanguage.app.feature.safety.domain.*
import org.junit.Assert.*
import org.junit.Test

class FixedSourceProjectionTest {
    @Test fun `fixed projection retains full original text and outside chapter units but no unselected output`() {
        val source = requireNotNull(snapshot().toFixedSourceProjection("flow"))
        assertEquals("喝茶。\n这句不在学习章里。", source.source.text)
        assertEquals(listOf("u1", "u2"), source.units.map { it.unitId })
        assertEquals(mapOf("u1" to "e1"), source.selectedCandidateIds)
        assertFalse(source.toString().contains("UNSELECTED ENGLISH"))
        assertFalse(source.toString().contains("AI recommendation only"))
        assertTrue(source.isValidFor(request(source)))
    }
    @Test fun `projection cannot change confirmed flow source sentence or selected identities`() {
        val source = requireNotNull(snapshot().toFixedSourceProjection("flow"))
        val request = request(source)
        assertTrue(source.isValidFor(request))
        listOf(source.copy(formationFlowId = "other-flow"), source.copy(selectedCandidateIds = mapOf("u1" to "e2")),
            source.copy(selectedTargetIds = setOf("not-selected")), source.copy(units = source.units.map { if (it.unitId == "u1") it.copy(textZh = "另一件事。") else it }))
            .forEach { assertFalse(it.isValidFor(request)) }
    }
    @Test fun `cannot turn an unfinished non confirmation stage into formally fixed source`() {
        assertNotNull(snapshot().toFixedSourceProjection("flow"))
        assertNull(snapshot().copy(phase = FormationRecoveryPhase.FOCUS_SELECTION).toFixedSourceProjection("flow"))
        assertNull(snapshot().toFixedSourceProjection(""))
    }
    @Test fun `missing old editor identity remains explicit and is never reconstructed from chapter sentences`() {
        val unknown = snapshot().copy(source = snapshot().source.copy(sourceType = SourceType.RECORDING),
            origin = FormationSourceOrigin.Unavailable("actual-review"))
        val projection = requireNotNull(unknown.toFixedSourceProjection("flow"))
        assertEquals(FixedSourceOrigin.Unavailable("actual-review"), projection.origin)
        assertEquals(unknown.source, projection.source)
    }
    private fun snapshot() = FormationRecoverySnapshot("original-source", 0, FormationRecoveryPhase.FINAL_FIX,
        ConfirmedSource("喝茶。\n这句不在学习章里。", SourceType.DIARY, SourceContentShape.ARTICLE), "日记", FormationSourceOrigin.Diary,
        units = listOf(SemanticUnit("u1", 0, "喝茶。", "AI recommendation only"), SemanticUnit("u2", 1, "这句不在学习章里。")),
        selectedUnitIds = listOf("u1"), chapters = listOf(ChapterDraft("c1", listOf("u1"))),
        englishChapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "喝茶。", true,
            listOf(EnglishCandidate("e1", "I drink tea."), EnglishCandidate("e2", "UNSELECTED ENGLISH")))))),
        selectedCandidateIds = mapOf("u1" to "e1"), targetSelectionEntryId = "targets", fixEntryId = "fix")
    private fun request(source: FixedSourceProjection) = ContentFixRequest("flow", "command", snapshot().englishChapters,
        snapshot().selectedCandidateIds, emptyList(), emptySet(), sourceProjection = source)
}
