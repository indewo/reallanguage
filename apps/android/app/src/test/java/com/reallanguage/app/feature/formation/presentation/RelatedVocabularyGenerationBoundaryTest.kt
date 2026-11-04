package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelatedVocabularyGenerationBoundaryTest {
    private val dispatcher = StandardTestDispatcher()
    private val evidence = SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea"))
    private val item = RelatedVocabularyItem("tea-sense", "s1", "tea", "茶", LearningTargetType.WORD, evidence)
    private val pattern = LearningTarget("pattern", "s1", "subject + verb + object", "主谓宾结构", LearningTargetType.PATTERN,
        structureParts = listOf("subject", "verb", "object"), relatedVocabulary = listOf("tea"),
        relatedVocabularyItems = listOf(item), relatedVocabularyItemRefs = listOf(item.itemRef))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun newWordCannotContradictThePatternExplanationAtTheSameSourceOccurrence() = runTest(dispatcher) {
        val vm = viewModel(LearningTarget("new-word", "s1", "tea", "晚餐", LearningTargetType.WORD, sourceEvidence = evidence))
        vm.onAction(LearningTargetAction.ExpressionRequested("s1", "word:8"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.generationFailed)
        assertEquals(listOf(pattern), vm.uiState.value.targets)
        assertEquals(setOf("pattern"), vm.uiState.value.selectedTargetIds)
    }

    @Test fun typedGeneratorCannotSmuggleADanglingExplanationGroupPastTheHttpBoundary() = runTest(dispatcher) {
        val vm = viewModel(LearningTarget("new-word", "s1", "tea", "茶", LearningTargetType.WORD, sourceEvidence = evidence,
            relatedVocabularyItems = emptyList(), relatedVocabularyItemRefs = listOf("missing")))
        vm.onAction(LearningTargetAction.ExpressionRequested("s1", "word:8"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.generationFailed)
        assertEquals(listOf(pattern), vm.uiState.value.targets)
        assertEquals(setOf("pattern"), vm.uiState.value.selectedTargetIds)
    }

    @Test fun consistentWordCanBeSelectedAndCancelledWithoutDeletingItsPatternsExplanation() = runTest(dispatcher) {
        val word = LearningTarget("new-word", "s1", "tea", "茶", LearningTargetType.WORD, sourceEvidence = evidence)
        val vm = viewModel(word)
        vm.onAction(LearningTargetAction.ExpressionRequested("s1", "word:8"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.generationFailed)
        assertEquals(setOf("pattern", "new-word"), vm.uiState.value.selectedTargetIds)
        vm.onAction(LearningTargetAction.ExpressionRequested("s1", "word:8"))
        assertEquals(setOf("pattern"), vm.uiState.value.selectedTargetIds)
        assertEquals(listOf(item), vm.uiState.value.targets.first().relatedVocabularyItems)
    }

    private fun viewModel(returned: LearningTarget) = LearningTargetViewModel(
        listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("s1", "我喝茶。", true,
            listOf(EnglishCandidate("e1", "I drink tea.")))))), mapOf("s1" to "e1"), listOf(pattern), emptyList(),
        LearningTargetGenerator { _, _ -> LearningTargetGenerationResult.Added(returned) })
}
