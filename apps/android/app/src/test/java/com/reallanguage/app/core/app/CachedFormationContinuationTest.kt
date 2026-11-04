package com.reallanguage.app.core.app

import com.reallanguage.app.feature.formation.domain.*
import org.junit.Assert.*
import org.junit.Test

class CachedFormationContinuationTest {
    private val unit = SemanticUnit("s", 0, "我喝茶。")
    private val english = listOf(EnglishChapterDraft("c", listOf(EnglishSentenceDraft("s", unit.textZh, true,
        listOf(EnglishCandidate("e", "I drink tea."))))))
    private val selection = HomeDiaryRoute.LearningTargetSelection(unit.textZh, listOf(unit), english,
        mapOf("s" to "e"), emptyList(), emptyList(), entryId = "target-page", sourceEntryId = "source")
    private val saved = requireNotNull(selection.toFormationRecoverySnapshot(0, "日记"))

    @Test fun reopeningPaidLoadingStepsReusesOnlyTheirAlreadySavedCompleteResult() {
        val loading = HomeDiaryRoute.LearningTargets(unit.textZh, listOf(unit), english, mapOf("s" to "e"), sourceEntryId = "source")
        val prepared = requireNotNull(loading.toFormationRecoverySnapshot(0, "日记", saved))
        assertEquals(selection.copy(sourceReturnRoute = HomeDiaryRoute.Input(unit.textZh), initialSelectedTargetIds = emptySet()), cachedFormationContinuation(loading, prepared))
        val generation = HomeDiaryRoute.EnglishGeneration(unit.textZh, listOf(unit), listOf("s"),
            listOf(ChapterDraft("c", listOf("s"))), sourceEntryId = "source")
        val generated = cachedFormationContinuation(generation, requireNotNull(generation.toFormationRecoverySnapshot(0, "日记", saved)))
        assertTrue(generated is HomeDiaryRoute.EnglishConfirmation)
        assertEquals(english, (generated as HomeDiaryRoute.EnglishConfirmation).chapters)
        assertEquals(mapOf("s" to "e"), generated.initialSelectedCandidateIds)
    }

    @Test fun changedConfirmedEnglishDoesNotReuseOldTargetsOrSilentlyRestoreAnotherSentence() {
        val changed = english.map { it.copy(sentences = it.sentences.map { s -> s.copy(candidates = listOf(EnglishCandidate("e", "I am drinking tea."))) }) }
        val route = HomeDiaryRoute.LearningTargets(unit.textZh, listOf(unit), changed, mapOf("s" to "e"), sourceEntryId = "source")
        val prepared = requireNotNull(route.toFormationRecoverySnapshot(0, "日记", saved))
        assertNull(cachedFormationContinuation(route, prepared))
        assertNull(cachedFormationContinuation(selection, saved))
    }

    @Test fun cachedLearningTargetsRestoreTheActualSecondSentenceAndItsSavedSelection() {
        val second = SemanticUnit("s2", 1, "她工作。")
        val units = listOf(unit, second)
        val text = units.joinToString("\n") { it.textZh }
        val chapters = listOf(EnglishChapterDraft("c", english.single().sentences +
            EnglishSentenceDraft("s2", second.textZh, true, listOf(EnglishCandidate("e2", "She works.")))))
        val choices = mapOf("s" to "e", "s2" to "e2")
        val target = LearningTarget("work", "s2", "works", "工作", LearningTargetType.WORD)
        val selectedRoute = HomeDiaryRoute.LearningTargetSelection(text, units, chapters, choices,
            listOf(target), emptyList(), emptySet(), entryId = "target-page", sourceEntryId = "source",
            initialSentenceIndex = 1)
        val savedSecond = requireNotNull(selectedRoute.toFormationRecoverySnapshot(0, "日记", targetSentenceIndex = 1))
        val loading = HomeDiaryRoute.LearningTargets(text, units, chapters, choices, sourceEntryId = "source")
        val prepared = requireNotNull(loading.toFormationRecoverySnapshot(0, "日记", savedSecond))

        val restored = cachedFormationContinuation(loading, prepared) as? HomeDiaryRoute.LearningTargetSelection
        assertNotNull(restored)
        assertEquals(1, restored!!.initialSentenceIndex)
        assertEquals(listOf(target), restored.targets)
        assertEquals(emptySet<String>(), restored.initialSelectedTargetIds)
        assertEquals(choices, restored.selections)
        assertEquals("target-page", restored.entryId)
    }

    @Test fun savedSemanticUnitsAndChapterGroupingAreReadWithoutRepeatingThoseRequests() {
        val split = HomeDiaryRoute.SemanticSplit(unit.textZh, entryId = "source")
        assertTrue(cachedFormationContinuation(split, requireNotNull(split.toFormationRecoverySnapshot(0, "日记", saved))) is HomeDiaryRoute.FocusSelection)
        val grouping = HomeDiaryRoute.ChapterFormation(unit.textZh, listOf(unit), listOf("s"), sourceEntryId = "source")
        assertTrue(cachedFormationContinuation(grouping, requireNotNull(grouping.toFormationRecoverySnapshot(0, "日记", saved))) is HomeDiaryRoute.EnglishGeneration)
    }
}
