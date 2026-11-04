package com.reallanguage.app.feature.review.presentation

import com.reallanguage.app.feature.review.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewChapterNoJudgmentTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun unavailablePatternAdvancesWithoutInventedEvidenceAndKeepsItsQualificationOnRetry() = runTest(dispatcher) {
        val repository = Repository(ReviewAbilityModule.PATTERN_SPEAKING).apply { fail = true }
        val vm = ReviewChapterViewModel("review", "command", repository)
        advanceUntilIdle()
        repeat(2) { vm.onAction(ReviewChapterAction.ModuleCompleted("ability", "", false,
            ReviewResultQualification.NOT_NATURALLY_VERIFIABLE)) }
        advanceUntilIdle()
        assertNull(vm.uiState.value.currentAbility)
        assertTrue(vm.uiState.value.commitFailed)
        val expected = listOf(ReviewAbilityResultDraft("ability", "", false,
            ReviewResultQualification.NOT_NATURALLY_VERIFIABLE))
        assertEquals(listOf(expected), repository.commits)
        repository.fail = false
        vm.onAction(ReviewChapterAction.RetryCommitRequested)
        advanceUntilIdle()
        assertEquals(listOf(expected, expected), repository.commits)
        assertTrue(vm.uiState.value.completed)
    }

    @Test fun unavailableQualificationCannotSupplyFakeAudioOrClaimPassOrSkipAnotherModule() = runTest(dispatcher) {
        val invalid = listOf(
            Triple(ReviewAbilityModule.PATTERN_SPEAKING, "fake-audio", false),
            Triple(ReviewAbilityModule.PATTERN_SPEAKING, "", true),
            Triple(ReviewAbilityModule.PATTERN_WRITING, "", false),
            Triple(ReviewAbilityModule.FULL_SENTENCE_SPEAKING, "fake-audio", false),
        )
        for ((module, evidence, passed) in invalid) {
            val repository = Repository(module)
            val vm = ReviewChapterViewModel("review", "command", repository)
            advanceUntilIdle()
            vm.onAction(ReviewChapterAction.ModuleCompleted("ability", evidence, passed,
                ReviewResultQualification.NOT_NATURALLY_VERIFIABLE))
            advanceUntilIdle()
            assertEquals("$module/$evidence/$passed", "ability", vm.uiState.value.currentAbility?.abilityId)
            assertTrue(repository.commits.isEmpty())
        }
    }

    @Test fun realIndependentPassAndFailContinueRequiringEvidenceAndKeepTheirOutcome() = runTest(dispatcher) {
        for (passed in listOf(true, false)) {
            val repository = Repository(ReviewAbilityModule.PATTERN_SPEAKING)
            val vm = ReviewChapterViewModel("review", "command", repository)
            advanceUntilIdle()
            vm.onAction(ReviewChapterAction.ModuleCompleted("ability", "", passed))
            assertTrue(repository.commits.isEmpty())
            val receipt = PatternIndependentReceipt(1,
                PatternIndependentReceiptIdentity("isolated-unit", 1, "review", "entry", "original", "sentence", "ability", "target"),
                "example", "isolated-command", "a".repeat(64), "b".repeat(64),
                if (passed) PatternIndependentDecision.PASS else PatternIndependentDecision.FAIL,
                passed, true, true, .9)
            vm.onAction(ReviewChapterAction.ModuleCompleted("ability", receipt.evidenceId, passed, patternReceipt = receipt))
            advanceUntilIdle()
            assertEquals(listOf(ReviewAbilityResultDraft("ability", receipt.evidenceId, passed, patternReceipt = receipt)),
                repository.commits.single())
        }
    }

    private class Repository(module: ReviewAbilityModule) : ReviewChapterRepository {
        var fail = false
        val commits = mutableListOf<List<ReviewAbilityResultDraft>>()
        private val chapter = ReviewChapterSnapshot("review", "original", ReviewSourceType.DIARY, "隔离场景",
            listOf("I used to walk."), "I used to walk.",
            listOf(ReviewAbility("ability", module, ReviewAbilityStatus.LEARNING)))
        override suspend fun loadChapter(reviewChapterId: String) = ReviewChapterLoadResult.Ready(chapter)
        override suspend fun commitChapter(reviewChapterId: String, commandId: String, results: List<ReviewAbilityResultDraft>): ReviewChapterCommitResult {
            commits += results
            return if (fail) ReviewChapterCommitResult.Failed else ReviewChapterCommitResult.Committed("next")
        }
    }
}
