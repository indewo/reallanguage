package com.reallanguage.app.feature.formation.domain

import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import org.junit.Assert.*
import org.junit.Test

class FormationRecoverySnapshotTest {
    @Test fun `candidate example with blank English cannot become accepted recovered content`() {
        for (text in listOf("", " ", "\n\t", "\u00a0\u3000")) {
            val snapshot = sample().let { it.copy(targets = it.targets.map { target ->
                target.copy(candidateExample = CandidateExample(text, "她喝茶。"))
            }) }
            assertFalse(snapshot.isValidForRecovery())
        }
    }

    @Test fun `candidate example with blank Chinese cannot become accepted recovered content`() {
        for (text in listOf("", " ", "\n\t", "\u00a0\u3000")) {
            val snapshot = sample().let { it.copy(targets = it.targets.map { target ->
                target.copy(candidateExample = CandidateExample("She drinks tea.", text))
            }) }
            assertFalse(snapshot.isValidForRecovery())
        }
    }

    @Test fun `candidate illustration is optional and never changes source evidence or selected answers`() {
        val old = sample()
        val illustrated = old.copy(targets = old.targets.map {
            it.copy(candidateExample = CandidateExample("She drinks tea at home.", "她在家喝茶。"))
        })
        assertTrue(old.isValidForRecovery())
        assertTrue(illustrated.isValidForRecovery())
        assertEquals(old.source, illustrated.source)
        assertEquals(old.targets.single().sourceEvidence, illustrated.targets.single().sourceEvidence)
        assertEquals(listOf("tea"), illustrated.targets.single().sourceEvidence?.allowedForms)
        assertEquals(old.selectedTargetIds, illustrated.selectedTargetIds)
    }

    @Test fun `explicit structure parts cannot contain blank entries even in a legacy snapshot`() {
        val original = sample()
        for (parts in listOf(listOf(""), listOf(" "), listOf("drink", "\n\t"), listOf("\u00a0\u3000"))) {
            for (evidence in listOf(null, patternEvidence())) {
                val target = patternTarget(parts).copy(sourceEvidence = evidence)
                assertFalse("Explicit blank structure parts are not absent legacy data: $parts",
                    original.copy(targets = listOf(target)).isValidForRecovery())
            }
        }
    }

    @Test fun `new evidenced pattern cannot resume with no structure while legacy absence is not invented`() {
        val original = sample()
        val newTarget = patternTarget(emptyList())
        assertFalse(original.copy(targets = listOf(newTarget)).isValidForRecovery())
        val legacy = original.copy(targets = listOf(newTarget.copy(sourceEvidence = null)))
        assertTrue("A legacy absence remains explicit instead of inventing a decomposition", legacy.isValidForRecovery())
        assertEquals(emptyList<String>(), legacy.targets.single().structureParts)
    }

    @Test fun `valid structure part order and spelling survive without reformatting or imposing a quota`() {
        val original = sample()
        for (parts in listOf(listOf("drink + 名词"), listOf(" drink ", "名词"))) {
            val snapshot = original.copy(targets = listOf(patternTarget(parts)))
            assertTrue(snapshot.isValidForRecovery())
            assertEquals(parts, snapshot.targets.single().structureParts)
        }
        for (type in listOf(LearningTargetType.WORD, LearningTargetType.FIXED_EXPRESSION)) {
            assertTrue(original.copy(targets = original.targets.map { it.copy(type = type, structureParts = emptyList()) }).isValidForRecovery())
        }
    }

    @Test fun `confirmed source can resume split without creating fake chapters`() {
        val snapshot = sample().copy(phase = FormationRecoveryPhase.SEMANTIC_SPLIT, units = emptyList(),
            selectedUnitIds = emptyList(), chapters = emptyList(), englishChapters = emptyList(), selectedCandidateIds = emptyMap(),
            targets = emptyList(), queryableExpressions = emptyList(), selectedTargetIds = emptySet(), targetSelectionEntryId = null, fixEntryId = null)
        assertTrue(snapshot.isValidForRecovery())
        assertFalse(snapshot.copy(source = snapshot.source.copy(text = "")).isValidForRecovery())
    }

    @Test fun `final snapshot retains all original units not only chapter content and permits zero selected targets`() {
        val snapshot = sample()
        assertTrue(snapshot.isValidForRecovery())
        assertEquals(2, snapshot.units.size)
        assertEquals(1, snapshot.englishChapters.single().sentences.size)
        assertTrue(snapshot.copy(selectedTargetIds = emptySet()).isValidForRecovery())
    }

    @Test fun `unknown source identity remains explicitly unavailable instead of becoming diary`() {
        val snapshot = sample()
        assertTrue(snapshot.copy(source = snapshot.source.copy(sourceType = SourceType.RECORDING),
            origin = FormationSourceOrigin.Unavailable("review-isolated")).isValidForRecovery())
        assertFalse(snapshot.copy(source = snapshot.source.copy(sourceType = SourceType.RECORDING),
            origin = FormationSourceOrigin.Diary).isValidForRecovery())
        assertFalse(snapshot.copy(origin = FormationSourceOrigin.Recording("audio", "transcript")).isValidForRecovery())
    }

    @Test fun `unknown selected unit candidate target and cross sentence evidence are refused`() {
        val snapshot = sample()
        assertTrue(snapshot.isValidForRecovery())
        listOf(snapshot.copy(selectedUnitIds = listOf("missing")),
            snapshot.copy(selectedCandidateIds = mapOf("u1" to "missing")),
            snapshot.copy(selectedTargetIds = setOf("missing")),
            snapshot.copy(targets = snapshot.targets.map { it.copy(unitId = "u2") }),
            snapshot.copy(targets = snapshot.targets.map { it.copy(sourceEvidence = it.sourceEvidence!!.copy(surfaceForm = "milk")) }))
            .forEach { assertFalse(it.isValidForRecovery()) }
    }

    @Test fun `returning upstream preserves unchanged results but rejects stale source content and malformed refs`() {
        val snapshot = sample()
        assertTrue(snapshot.isValidForRecovery())
        assertTrue(snapshot.copy(phase = FormationRecoveryPhase.FOCUS_SELECTION).isValidForRecovery())
        assertFalse(snapshot.copy(phase = FormationRecoveryPhase.FOCUS_SELECTION,
            units = snapshot.units.map { if (it.unitId == "u1") it.copy(textZh = "新确认的不同内容。") else it }).isValidForRecovery())
        assertFalse(snapshot.copy(fixEntryId = null).isValidForRecovery())
        assertFalse(snapshot.copy(englishChapters = snapshot.englishChapters.map { chapter -> chapter.copy(
            sentences = chapter.sentences.map { sentence -> sentence.copy(candidates = sentence.candidates.map {
                it.copy(reusedKnowledgeRefs = listOf("VOCABULARY:a", "VOCABULARY:a"))
            }) },
        ) }).isValidForRecovery())
    }

    private fun patternEvidence() = SourceEvidence(1, 2, 11, "drink tea", "drink + noun", listOf("drink tea"))
    private fun patternTarget(parts: List<String>) = LearningTarget("t1", "u1", "drink + noun", "喝某物", LearningTargetType.PATTERN,
        structureParts = parts, sourceEvidence = patternEvidence())

    private fun sample(): FormationRecoverySnapshot = FormationRecoverySnapshot(
        sourceEntryId = "source-isolated", sourceRevision = 0, phase = FormationRecoveryPhase.FINAL_FIX,
        source = ConfirmedSource("今天喝茶。\n还有一句未入章。", SourceType.DIARY, SourceContentShape.ARTICLE),
        sourceTitle = "隔离日记", origin = FormationSourceOrigin.Diary,
        units = listOf(SemanticUnit("u1", 0, "今天喝茶。"), SemanticUnit("u2", 1, "还有一句未入章。")),
        selectedUnitIds = listOf("u1"), chapters = listOf(ChapterDraft("c1", listOf("u1"))),
        englishChapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "今天喝茶。", true,
            listOf(EnglishCandidate("e1", "I drink tea.", listOf("VOCABULARY:tea"))))))),
        selectedCandidateIds = mapOf("u1" to "e1"),
        targets = listOf(LearningTarget("t1", "u1", "tea", "茶", LearningTargetType.WORD,
            sourceEvidence = SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea")))),
        queryableExpressions = listOf(QueryableExpression("word:8", "u1", "tea")), selectedTargetIds = setOf("t1"),
        targetSelectionEntryId = "targets-isolated", fixEntryId = "fix-isolated",
    )
}
