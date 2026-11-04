package com.reallanguage.app.feature.review.presentation

import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft
import com.reallanguage.app.feature.review.domain.ReviewAbilityStatus
import com.reallanguage.app.feature.review.domain.ReviewChapterCommitResult
import com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult
import com.reallanguage.app.feature.review.domain.ReviewChapterRepository
import com.reallanguage.app.feature.review.domain.ReviewChapterSnapshot
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import com.reallanguage.app.feature.review.domain.ReviewResultQualification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewChapterViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun supportedWritingAdvancesButRetainsQualificationThroughFailedCommitAndRetry() = runTest(dispatcher) {
        val writing = snapshot.copy(abilities = listOf(ReviewAbility("a1", ReviewAbilityModule.COMPLETE_WRITING, ReviewAbilityStatus.LEARNING)))
        val repository = FakeRepository(loadResult = ReviewChapterLoadResult.Ready(writing), commitResult = ReviewChapterCommitResult.Failed)
        val vm = ReviewChapterViewModel("r1", "cmd", repository)
        advanceUntilIdle()
        vm.onAction(ReviewChapterAction.ModuleCompleted("a1", "supported-evidence", false, ReviewResultQualification.SUPPORTED))
        advanceUntilIdle()
        assertNull(vm.uiState.value.currentAbility)
        assertTrue(vm.uiState.value.commitFailed)
        assertEquals(ReviewResultQualification.SUPPORTED, repository.commits.single().third.single().qualification)
        repository.commitResult = ReviewChapterCommitResult.Committed(null)
        vm.onAction(ReviewChapterAction.RetryCommitRequested)
        advanceUntilIdle()
        assertEquals(repository.commits[0], repository.commits[1])
        assertTrue(vm.uiState.value.completed)
    }

    @Test fun supportedQualificationCannotBypassAnUnrelatedModule() = runTest(dispatcher) {
        val vm = ReviewChapterViewModel("r1", "cmd", FakeRepository())
        advanceUntilIdle()
        vm.onAction(ReviewChapterAction.ModuleCompleted("a1", "unsupported-module", false, ReviewResultQualification.SUPPORTED))
        assertEquals("a1", vm.uiState.value.currentAbility?.abilityId)
        assertTrue(vm.uiState.value.resultDrafts.isEmpty())
    }

    @Test fun supportedWritingCannotClaimIndependentSuccess() = runTest(dispatcher) {
        val writing = snapshot.copy(abilities = listOf(ReviewAbility("a1", ReviewAbilityModule.COMPLETE_WRITING, ReviewAbilityStatus.LEARNING)))
        val repository = FakeRepository(loadResult = ReviewChapterLoadResult.Ready(writing))
        val vm = ReviewChapterViewModel("r1", "invalid-success", repository)
        advanceUntilIdle()
        vm.onAction(ReviewChapterAction.ModuleCompleted("a1", "supported-evidence", true, ReviewResultQualification.SUPPORTED))
        advanceUntilIdle()
        assertEquals("a1", vm.uiState.value.currentAbility?.abilityId)
        assertTrue(repository.commits.isEmpty())
    }

    @Test
    fun repeatedRetryCannotCommitTwiceOrCommitAgainAfterCompletion() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = ReviewChapterViewModel("r1", "cmd-1", repository)
        advanceUntilIdle()
        vm.onAction(ReviewChapterAction.ModuleCompleted("a1", "e1"))
        vm.onAction(ReviewChapterAction.ModuleCompleted("a2", "e2"))
        repeat(3) { vm.onAction(ReviewChapterAction.RetryCommitRequested) }
        advanceUntilIdle()
        assertEquals(1, repository.commits.size)
        vm.onAction(ReviewChapterAction.RetryCommitRequested)
        advanceUntilIdle()
        assertEquals(1, repository.commits.size)
    }

    @Test
    fun blankEvidenceCannotAdvanceOrBecomeAFormalReviewResult() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = ReviewChapterViewModel("r1", "cmd-1", repository)
        advanceUntilIdle()
        vm.onAction(ReviewChapterAction.ModuleCompleted("a1", "  "))
        assertEquals("a1", vm.uiState.value.currentAbility?.abilityId)
        assertTrue(vm.uiState.value.resultDrafts.isEmpty())
    }

    @Test
    fun readExceptionBecomesRetryableAndRapidLoadRetryRunsOnce() = runTest(dispatcher) {
        val repository = FakeRepository(loadThrows = true)
        val vm = ReviewChapterViewModel("r1", "cmd-1", repository)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertTrue(vm.uiState.value.loadFailed)
        repository.loadThrows = false
        repeat(3) { vm.onAction(ReviewChapterAction.RetryLoadRequested) }
        advanceUntilIdle()
        assertEquals(2, repository.loads)
        assertFalse(vm.uiState.value.loadFailed)
    }

    @Test
    fun commitExceptionKeepsTheExactDraftsForLocalRetry() = runTest(dispatcher) {
        val repository = FakeRepository(commitThrows = true)
        val vm = ReviewChapterViewModel("r1", "cmd-1", repository)
        advanceUntilIdle()
        vm.onAction(ReviewChapterAction.ModuleCompleted("a1", "e1", false))
        vm.onAction(ReviewChapterAction.ModuleCompleted("a2", "e2"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.commitFailed)
        assertFalse(vm.uiState.value.committing)
        repository.commitThrows = false
        vm.onAction(ReviewChapterAction.RetryCommitRequested)
        advanceUntilIdle()
        assertEquals(repository.commits[0], repository.commits[1])
    }

    @Test
    fun loadsOriginalContextAndFiltersAlreadyMasteredAbilities() = runTest(dispatcher) {
        val viewModel = ReviewChapterViewModel("r1", "cmd-1", FakeRepository())
        advanceUntilIdle()

        assertEquals(snapshot.originalScene, viewModel.uiState.value.chapter?.originalScene)
        assertEquals(listOf("a1", "a2"), viewModel.uiState.value.pendingAbilities.map { it.abilityId })
        assertEquals("a1", viewModel.uiState.value.currentAbility?.abilityId)
    }

    @Test fun verifiedPatternStillRequiredByItsSuccessCountCanBeOpenedAndCompleted() = runTest(dispatcher) {
        val pattern = ReviewAbility("pattern", ReviewAbilityModule.PATTERN_WRITING,
            ReviewAbilityStatus.MASTERED, "s1", "p1", requiresReview = true)
        val repository = FakeRepository(loadResult = ReviewChapterLoadResult.Ready(snapshot.copy(abilities = listOf(pattern))))
        val vm = ReviewChapterViewModel("r1", "verified-pattern", repository)
        advanceUntilIdle()
        assertEquals(listOf(pattern), vm.uiState.value.pendingAbilities)
        assertEquals(ReviewAbilityStatus.MASTERED, vm.uiState.value.currentAbility?.status)
        val opened = async { vm.effects.first() }
        vm.onAction(ReviewChapterAction.OpenCurrentModuleRequested)
        advanceUntilIdle()
        assertEquals(ReviewChapterEffect.OpenAbilityModule("pattern", ReviewAbilityModule.PATTERN_WRITING), opened.await())
        vm.onAction(ReviewChapterAction.ModuleCompleted("pattern", "independent-evidence"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.completed)
        assertEquals(listOf("pattern"), repository.commits.single().third.map { it.abilityId })
    }

    @Test
    fun currentModuleIsOpenedWithoutSkippingOrder() = runTest(dispatcher) {
        val viewModel = ReviewChapterViewModel("r1", "cmd-1", FakeRepository())
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(ReviewChapterAction.OpenCurrentModuleRequested)
        advanceUntilIdle()

        assertEquals(
            ReviewChapterEffect.OpenAbilityModule("a1", ReviewAbilityModule.FULL_SENTENCE_SPEAKING),
            effect.await(),
        )
    }

    @Test
    fun partialModuleResultsStayLocalUntilEveryPendingAbilityCompletes() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = ReviewChapterViewModel("r1", "cmd-1", repository)
        advanceUntilIdle()

        viewModel.onAction(ReviewChapterAction.ModuleCompleted("a1", "evidence-1"))
        advanceUntilIdle()

        assertTrue(repository.commits.isEmpty())
        assertEquals("a2", viewModel.uiState.value.currentAbility?.abilityId)
    }

    @Test
    fun aHandledAbilityLeavesThePendingListWithoutLosingItsUncommittedResult() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = ReviewChapterViewModel("r1", "pending-display", repository)
        advanceUntilIdle()
        vm.onAction(ReviewChapterAction.ModuleCompleted("a1", "handled-evidence", false))
        advanceUntilIdle()

        assertEquals(listOf("a2"), vm.uiState.value.pendingAbilities.map { it.abilityId })
        assertEquals("a2", vm.uiState.value.currentAbility?.abilityId)
        assertEquals(listOf(ReviewAbilityResultDraft("a1", "handled-evidence", false)), vm.uiState.value.resultDrafts)
        assertTrue(repository.commits.isEmpty())
        assertEquals(snapshot, vm.uiState.value.chapter)
    }

    @Test
    fun failedWholeChapterCommitShowsNoPendingModulesButKeepsEveryResultForExactRetry() = runTest(dispatcher) {
        val repository = FakeRepository(commitResult = ReviewChapterCommitResult.Failed)
        val vm = ReviewChapterViewModel("r1", "pending-commit-retry", repository)
        advanceUntilIdle()
        vm.onAction(ReviewChapterAction.ModuleCompleted("a1", "handled-first", false))
        vm.onAction(ReviewChapterAction.ModuleCompleted("a2", "handled-second"))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.commitFailed)
        assertTrue(vm.uiState.value.pendingAbilities.isEmpty())
        assertEquals(2, vm.uiState.value.resultDrafts.size)
        repository.commitResult = ReviewChapterCommitResult.Committed(null)
        vm.onAction(ReviewChapterAction.RetryCommitRequested)
        advanceUntilIdle()
        assertEquals(repository.commits[0], repository.commits[1])
        assertTrue(vm.uiState.value.completed)
        assertEquals(snapshot, vm.uiState.value.chapter)
    }

    @Test
    fun finalModuleAtomicallyCommitsWholeChapterAndOpensNext() = runTest(dispatcher) {
        val repository = FakeRepository(
            commitResult = ReviewChapterCommitResult.Committed("r2"),
        )
        val viewModel = ReviewChapterViewModel("r1", "cmd-1", repository)
        advanceUntilIdle()
        viewModel.onAction(ReviewChapterAction.ModuleCompleted("a1", "evidence-1"))
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(ReviewChapterAction.ModuleCompleted("a2", "evidence-2"))
        advanceUntilIdle()

        assertEquals(1, repository.commits.size)
        assertEquals(
            listOf("a1", "a2"),
            repository.commits.single().third.map(ReviewAbilityResultDraft::abilityId),
        )
        assertEquals(ReviewChapterEffect.OpenNextReviewChapter("r2"), effect.await())
    }

    @Test
    fun commitFailureKeepsDraftsForExplicitRetryAndNoPartialSuccess() = runTest(dispatcher) {
        val repository = FakeRepository(commitResult = ReviewChapterCommitResult.Failed)
        val viewModel = ReviewChapterViewModel("r1", "cmd-1", repository)
        advanceUntilIdle()
        viewModel.onAction(ReviewChapterAction.ModuleCompleted("a1", "evidence-1"))
        viewModel.onAction(ReviewChapterAction.ModuleCompleted("a2", "evidence-2"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.commitFailed)
        assertFalse(viewModel.uiState.value.committing)

        repository.commitResult = ReviewChapterCommitResult.Committed(null)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ReviewChapterAction.RetryCommitRequested)
        advanceUntilIdle()

        assertEquals(ReviewChapterEffect.OpenReviewResult, effect.await())
        assertNull(viewModel.uiState.value.currentAbility)
    }

    private class FakeRepository(
        var loadResult: ReviewChapterLoadResult = ReviewChapterLoadResult.Ready(snapshot),
        var commitResult: ReviewChapterCommitResult = ReviewChapterCommitResult.Committed(null),
        var loadThrows: Boolean = false,
        var commitThrows: Boolean = false,
    ) : ReviewChapterRepository {
        val commits = mutableListOf<Triple<String, String, List<ReviewAbilityResultDraft>>>()

        var loads = 0
        override suspend fun loadChapter(reviewChapterId: String): ReviewChapterLoadResult {
            loads++
            if (loadThrows) error("Isolated read failure")
            return loadResult
        }

        override suspend fun commitChapter(
            reviewChapterId: String,
            commandId: String,
            results: List<ReviewAbilityResultDraft>,
        ): ReviewChapterCommitResult {
            commits += Triple(reviewChapterId, commandId, results)
            if (commitThrows) error("Isolated storage failure")
            return commitResult
        }
    }

    companion object {
        private val snapshot = ReviewChapterSnapshot(
            "r1",
            "original-c1",
            ReviewSourceType.DIARY,
            "昨晚的日记",
            listOf("I hesitated at first.", "I finally spoke up."),
            "I finally spoke up.",
            listOf(
                ReviewAbility("a1", ReviewAbilityModule.FULL_SENTENCE_SPEAKING, ReviewAbilityStatus.LEARNING),
                ReviewAbility("a2", ReviewAbilityModule.WORD_MEANING, ReviewAbilityStatus.TENTATIVE),
                ReviewAbility("done", ReviewAbilityModule.COMPLETE_WRITING, ReviewAbilityStatus.MASTERED),
            ),
        )
    }
}
