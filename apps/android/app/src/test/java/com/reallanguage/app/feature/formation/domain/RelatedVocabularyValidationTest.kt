package com.reallanguage.app.feature.formation.domain

import com.reallanguage.app.feature.safety.domain.*
import org.junit.Assert.*
import org.junit.Test

class RelatedVocabularyValidationTest {
    private val english = "I drink tea."
    private val item = RelatedVocabularyItem("r-tea", "u1", "tea", "茶", LearningTargetType.WORD,
        SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea")))
    private val pattern = LearningTarget("p1", "u1", "drink + noun", "喝某物", LearningTargetType.PATTERN,
        structureParts = listOf("drink", "noun"), relatedVocabulary = listOf("tea"),
        sourceEvidence = SourceEvidence(1, 2, 11, "drink tea", "drink + noun", listOf("drink tea")),
        relatedVocabularyItems = listOf(item), relatedVocabularyItemRefs = listOf(item.itemRef))

    @Test fun completeCurrentSentenceGroupAndLegacyDoNotCreateAnotherLearningTarget() {
        assertTrue(pattern.hasValidRelatedVocabularyItems(english))
        assertTrue(pattern.copy(relatedVocabularyItems = null, relatedVocabularyItemRefs = null)
            .hasValidRelatedVocabularyItems(english))
        val state = snapshot(listOf(pattern))
        assertTrue(state.isValidForRecovery())
        assertEquals(setOf("p1"), state.selectedTargetIds)
        assertEquals(1, state.targets.size)
    }

    @Test fun partialDuplicateOrOrphanedGroupsCannotPretendToBeComplete() {
        val invalid = listOf(
            pattern.copy(relatedVocabularyItems = null), pattern.copy(relatedVocabularyItemRefs = null),
            pattern.copy(relatedVocabularyItemRefs = emptyList()),
            pattern.copy(relatedVocabularyItemRefs = listOf("r-tea", "r-tea")),
            pattern.copy(relatedVocabularyItemRefs = listOf("unknown")),
            pattern.copy(relatedVocabularyItems = listOf(item, item)),
            pattern.copy(relatedVocabulary = listOf("another word")),
        )
        invalid.forEach { assertFalse(it.toString(), it.hasValidRelatedVocabularyItems(english)) }
    }

    @Test fun referencesPreserveTheirDeclaredOrderAndExactDisplayedValues() {
        val drink = RelatedVocabularyItem("r-drink", "u1", "drink", "喝", LearningTargetType.WORD,
            SourceEvidence(1, 2, 7, "drink", "drink", listOf("drink")))
        val target = pattern.copy(relatedVocabularyItems = listOf(item, drink),
            relatedVocabularyItemRefs = listOf(drink.itemRef, item.itemRef), relatedVocabulary = listOf("drink", "tea"))
        assertTrue(target.hasValidRelatedVocabularyItems(english))
        assertFalse(target.copy(relatedVocabulary = listOf("tea", "drink")).hasValidRelatedVocabularyItems(english))
    }

    @Test fun explanationRequiresCurrentUnitExactWholeTokenEvidenceAndVocabularyType() {
        val invalid = listOf(item.copy(itemRef = " "), item.copy(unitId = "u2"), item.copy(display = " "),
            item.copy(senseZh = "\u3000"), item.copy(unitType = LearningTargetType.PATTERN), item.copy(display = "drink"),
            item.copy(sourceEvidence = item.sourceEvidence.copy(startUtf16 = 9, surfaceForm = "ea", allowedForms = listOf("ea"))),
            item.copy(sourceEvidence = item.sourceEvidence.copy(allowedForms = listOf("tea", "water"))))
        invalid.forEach { bad ->
            val target = pattern.copy(relatedVocabulary = listOf(bad.display), relatedVocabularyItems = listOf(bad),
                relatedVocabularyItemRefs = listOf(bad.itemRef))
            assertFalse(bad.toString(), target.hasValidRelatedVocabularyItems(english))
        }
        assertFalse(pattern.hasValidRelatedVocabularyItems("I drink sea."))
    }

    @Test fun nonPatternNewGroupsMustBeEmptyButLegacyTextRemainsReadable() {
        val word = pattern.copy(type = LearningTargetType.WORD)
        assertFalse(word.hasValidRelatedVocabularyItems(english))
        assertTrue(word.copy(relatedVocabulary = emptyList(), relatedVocabularyItems = emptyList(),
            relatedVocabularyItemRefs = emptyList()).hasValidRelatedVocabularyItems(english))
        assertTrue(word.copy(relatedVocabularyItems = null, relatedVocabularyItemRefs = null)
            .hasValidRelatedVocabularyItems(english))
    }

    @Test fun sameOccurrenceCannotHaveConflictingMeaningDisplayOrCategoryAcrossTargets() {
        val word = LearningTarget("w1", "u1", "tea", "茶", LearningTargetType.WORD, sourceEvidence = item.sourceEvidence)
        assertTrue(listOf(pattern, word).hasConsistentVocabularyExplanations())
        listOf(word.copy(explanationZh = "晚餐"), word.copy(display = "Tea"),
            word.copy(type = LearningTargetType.FIXED_EXPRESSION)).forEach {
            assertFalse(it.toString(), listOf(pattern, it).hasConsistentVocabularyExplanations())
        }
        val otherPattern = pattern.copy(targetId = "p2", relatedVocabularyItems = listOf(item.copy(senseZh = "晚餐")))
        assertFalse(listOf(pattern, otherPattern).hasConsistentVocabularyExplanations())
    }

    @Test fun repeatedWordAtADifferentSpanIsNotSilentlyMergedIntoTheFirstMeaning() {
        val first = item.copy(display = "Tea", sourceEvidence = SourceEvidence(1, 0, 3, "Tea", "tea", listOf("Tea")))
        val second = item.copy(itemRef = "r-tea-second", senseZh = "茶点",
            sourceEvidence = SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea")))
        val target = pattern.copy(relatedVocabulary = listOf("Tea", "tea"), relatedVocabularyItems = listOf(first, second),
            relatedVocabularyItemRefs = listOf(first.itemRef, second.itemRef))
        assertTrue(target.hasValidRelatedVocabularyItems("Tea and tea."))
        assertTrue(listOf(target).hasConsistentVocabularyExplanations())
    }

    @Test fun actualRecoveryValidationRejectsInvalidOrContradictoryExplanationEvenWhenWordIsUnselected() {
        assertFalse(snapshot(listOf(pattern.copy(relatedVocabularyItems = listOf(item.copy(unitId = "u2"))))).isValidForRecovery())
        val word = LearningTarget("w1", "u1", "tea", "晚餐", LearningTargetType.WORD, sourceEvidence = item.sourceEvidence)
        assertFalse(snapshot(listOf(pattern, word)).isValidForRecovery())
        assertTrue(snapshot(listOf(pattern, word.copy(explanationZh = "茶"))).isValidForRecovery())
    }

    @Test fun localReferencesHaveTheSame128Utf16LimitAsTheWireContract() {
        val maximum = "r".repeat(128)
        val valid = pattern.copy(relatedVocabularyItems = listOf(item.copy(itemRef = maximum)),
            relatedVocabularyItemRefs = listOf(maximum))
        assertTrue(valid.hasValidRelatedVocabularyItems(english))
        val tooLong = "r".repeat(129)
        assertFalse(valid.copy(relatedVocabularyItems = listOf(item.copy(itemRef = tooLong)),
            relatedVocabularyItemRefs = listOf(tooLong)).hasValidRelatedVocabularyItems(english))
    }

    @Test fun sameTargetCannotListTheSameOccurrenceTwiceUnderDifferentLocalRefs() {
        assertTrue(listOf(pattern, pattern.copy(targetId = "p2")).hasConsistentVocabularyExplanations())
        val duplicate = item.copy(itemRef = "r-tea-duplicate")
        assertFalse(pattern.copy(relatedVocabulary = listOf("tea", "tea"), relatedVocabularyItems = listOf(item, duplicate),
            relatedVocabularyItemRefs = listOf(item.itemRef, duplicate.itemRef)).hasValidRelatedVocabularyItems(english))
    }

    @Test fun sameOccurrenceCannotBecomeTwoActualVocabularyTargetsEvenWhenMeaningsAgree() {
        val word = LearningTarget("w1", "u1", "tea", "茶", LearningTargetType.WORD, sourceEvidence = item.sourceEvidence)
        assertTrue(listOf(pattern, word).hasConsistentVocabularyExplanations())
        assertFalse(listOf(pattern, word, word.copy(targetId = "w2")).hasConsistentVocabularyExplanations())
    }

    @Test fun legacyWhitespaceLabelsArePreservedWithoutBecomingMeaningEvidence() {
        val legacy = pattern.copy(relatedVocabulary = listOf(" "), relatedVocabularyItems = null,
            relatedVocabularyItemRefs = null)
        assertTrue(legacy.hasValidRelatedVocabularyItems(english))
        assertTrue(snapshot(listOf(legacy)).isValidForRecovery())
        assertNull(legacy.relatedVocabularyItems)
        assertEquals(listOf(" "), legacy.relatedVocabulary)
    }

    private fun snapshot(targets: List<LearningTarget>) = FormationRecoverySnapshot(
        "related-source", 0, FormationRecoveryPhase.LEARNING_TARGET_SELECTION,
        ConfirmedSource("我喝茶。", SourceType.DIARY, SourceContentShape.ARTICLE), "隔离关联词", FormationSourceOrigin.Diary,
        units = listOf(SemanticUnit("u1", 0, "我喝茶。")), selectedUnitIds = listOf("u1"),
        chapters = listOf(ChapterDraft("c1", listOf("u1"))),
        englishChapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "我喝茶。", true,
            listOf(EnglishCandidate("e1", english)))))),
        selectedCandidateIds = mapOf("u1" to "e1"), targets = targets,
        selectedTargetIds = setOf("p1"), targetSelectionEntryId = "related-selection")
}
