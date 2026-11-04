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
class RepeatedWordTargetSelectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val sentence = EnglishSentenceDraft("s1", "我看见了一把锯子。", true,
        listOf(EnglishCandidate("e1", "I saw a saw.")))
    private val first = word("first", 2, "看见了")
    private val second = word("second", 8, "锯子")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun secondOccurrenceGetsItsOwnContextualSenseInsteadOfTogglingTheFirstWord() = runTest(dispatcher) {
        val calls = mutableListOf<Pair<String, String>>()
        val vm = vm(listOf(first)) { unit, expression ->
            calls += unit to expression
            LearningTargetGenerationResult.Added(second)
        }
        vm.onAction(LearningTargetAction.ExpressionRequested("s1", "word:8"))
        advanceUntilIdle()
        assertEquals(listOf("s1" to "word:8"), calls)
        assertEquals(listOf(first, second), vm.uiState.value.targets)
        assertEquals(setOf("first", "second"), vm.uiState.value.selectedTargetIds)
    }

    @Test fun repeatedTapOnlyTogglesTheTargetAtThatExactPositionWithoutCallingAiAgain() = runTest(dispatcher) {
        var calls = 0
        val vm = vm(listOf(first, second)) { _, _ -> calls++; LearningTargetGenerationResult.Failed }
        vm.onAction(LearningTargetAction.ExpressionRequested("s1", "word:8"))
        advanceUntilIdle()
        assertEquals(setOf("first"), vm.uiState.value.selectedTargetIds)
        vm.onAction(LearningTargetAction.ExpressionRequested("s1", "word:8"))
        vm.onAction(LearningTargetAction.ExpressionRequested("s1", "word:2"))
        advanceUntilIdle()
        assertEquals(setOf("second"), vm.uiState.value.selectedTargetIds)
        assertEquals(0, calls)
    }

    @Test fun legacyAmbiguousWordDoesNotClaimEitherOccurrenceOrLoseItsExistingSelection() = runTest(dispatcher) {
        val legacy = first.copy(sourceEvidence = null)
        var calls = 0
        val vm = vm(listOf(legacy)) { _, _ -> calls++; LearningTargetGenerationResult.Added(second) }
        vm.onAction(LearningTargetAction.ExpressionRequested("s1", "word:8"))
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(setOf("first", "second"), vm.uiState.value.selectedTargetIds)
        assertNull(vm.uiState.value.targets.first().sourceEvidence)
    }

    @Test fun generatorCannotBindARepeatedWordToTheOtherPositionOrLeaveItsIdentityUnknown() = runTest(dispatcher) {
        for (returned in listOf(first.copy(targetId = "wrong"), second.copy(sourceEvidence = null))) {
            val vm = vm(emptyList()) { _, _ -> LearningTargetGenerationResult.Added(returned) }
            vm.onAction(LearningTargetAction.ExpressionRequested("s1", "word:8"))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.generationFailed)
            assertTrue(vm.uiState.value.targets.isEmpty())
            assertTrue(vm.uiState.value.selectedTargetIds.isEmpty())
        }
    }

    private fun vm(targets: List<LearningTarget>, generate: suspend (String, String) -> LearningTargetGenerationResult) =
        LearningTargetViewModel(listOf(EnglishChapterDraft("c1", listOf(sentence))), mapOf("s1" to "e1"),
            targets, emptyList(), LearningTargetGenerator { unit, expression -> generate(unit, expression) })

    private fun word(id: String, start: Int, meaning: String) = LearningTarget(id, "s1", "saw", meaning,
        LearningTargetType.WORD, sourceEvidence = SourceEvidence(1, start, start + 3, "saw", "saw", listOf("saw")))
}
