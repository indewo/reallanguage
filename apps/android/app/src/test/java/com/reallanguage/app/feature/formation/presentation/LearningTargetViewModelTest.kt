package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetGenerationResult
import com.reallanguage.app.feature.formation.domain.LearningTargetGenerator
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.QueryableExpression
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LearningTargetViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chapters = listOf(
        EnglishChapterDraft(
            "c1",
            listOf(
                EnglishSentenceDraft(
                    "u1", "我写得更清楚了。", true,
                    listOf(EnglishCandidate("e1", "I expressed myself more clearly.")),
                ),
                EnglishSentenceDraft(
                    "u2", "这让我很开心。", false,
                    listOf(EnglishCandidate("e2", "That made me happy.")),
                ),
            ),
        ),
    )
    private val initialTargets = listOf(
        LearningTarget("t1", "u1", "clearly", "清楚地", LearningTargetType.WORD),
        LearningTarget(
            "t2", "u1", "express oneself", "表达自己", LearningTargetType.PATTERN,
            structureParts = listOf("express", "oneself"),
        ),
        LearningTarget("ignored", "u2", "happy", "开心的", LearningTargetType.WORD),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun onlyFocusSentenceTargetsAreShownAndSelectedByDefault() {
        val state = createViewModel().uiState.value
        assertEquals(listOf("t1", "t2"), state.targets.map { it.targetId })
        assertEquals(setOf("t1", "t2"), state.selectedTargetIds)
    }

    @Test fun manualFallbackNeverRepeatsAiCuratedTargetsEvenWhenTheirCardsAreDeselected() {
        val curated = LearningTarget(
            "curated", "u1", "more clearly", "更清楚地", LearningTargetType.FIXED_EXPRESSION,
        )
        val viewModel = LearningTargetViewModel(
            chapters, mapOf("u1" to "e1"), initialTargets + curated,
            listOf(
                QueryableExpression("duplicate", "u1", "  MORE   CLEARLY "),
                QueryableExpression("manual", "u1", "myself"),
            ),
            LearningTargetGenerator { _, _ -> LearningTargetGenerationResult.Failed },
        )

        assertEquals(listOf("myself"), viewModel.uiState.value.currentExpressions.map { it.display })
        viewModel.onAction(LearningTargetAction.TargetToggled("curated"))
        assertEquals(listOf("myself"), viewModel.uiState.value.currentExpressions.map { it.display })
    }

    @Test
    fun wordFromSentenceCanBeRequestedEvenWhenAiDidNotSuggestIt() = runTest(dispatcher) {
        var requested = false
        val viewModel = createViewModel(LearningTargetGenerator { unitId, expressionId ->
            requested = unitId == "u1" && expressionId == "word:2"
            LearningTargetGenerationResult.Added(LearningTarget("word-expressed", "u1", "expressed", "表达了", LearningTargetType.WORD))
        })
        viewModel.onAction(LearningTargetAction.ExpressionRequested("u1", "word:2"))
        advanceUntilIdle()
        assertTrue(requested)
        assertTrue("word-expressed" in viewModel.uiState.value.selectedTargetIds)
        viewModel.onAction(LearningTargetAction.TargetToggled("word-expressed"))
        assertFalse("word-expressed" in viewModel.uiState.value.selectedTargetIds)
    }

    @Test
    fun allTargetsMayBeDeselectedWhileSentenceLearningStillContinues() {
        val viewModel = createViewModel()
        viewModel.onAction(LearningTargetAction.TargetToggled("t1"))
        viewModel.onAction(LearningTargetAction.TargetToggled("t2"))
        assertTrue(viewModel.uiState.value.selectedTargetIds.isEmpty())
        assertTrue(viewModel.uiState.value.continueEnabled)
    }

    @Test
    fun clearAllRemovesEveryOptionalTargetWhileSentenceLearningStillContinues() {
        val viewModel = createViewModel()

        viewModel.onAction(LearningTargetAction.ClearAllRequested)

        assertTrue(viewModel.uiState.value.selectedTargetIds.isEmpty())
        assertTrue(viewModel.uiState.value.continueEnabled)
    }

    @Test
    fun queryingAnotherExpressionAddsItsUniqueContextTargetAndSelectsIt() =
        runTest(dispatcher) {
            val added = LearningTarget(
                "t3", "u1", "more clearly", "更清楚地", LearningTargetType.FIXED_EXPRESSION,
            )
            val viewModel = createViewModel(
                generator = LearningTargetGenerator { _, _ ->
                    LearningTargetGenerationResult.Added(added)
                },
            )
            viewModel.onAction(LearningTargetAction.ExpressionRequested("u1", "q1"))
            advanceUntilIdle()
            assertEquals("t3", viewModel.uiState.value.targets.last().targetId)
            assertTrue("t3" in viewModel.uiState.value.selectedTargetIds)
            val effect = async { viewModel.effects.first() }
            viewModel.onAction(LearningTargetAction.ContinueRequested)
            advanceUntilIdle()
            assertTrue((effect.await() as LearningTargetEffect.OpenFinalConfirmation).targets.contains(added))
        }

    @Test
    fun continueLocksChoicesAndOpensFinalConfirmation() = runTest(dispatcher) {
        val viewModel = createViewModel()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(LearningTargetAction.ContinueRequested)
        viewModel.onAction(LearningTargetAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(
            LearningTargetEffect.OpenFinalConfirmation(setOf("t1", "t2"), initialTargets.take(2)),
            effect.await(),
        )
    }

    @Test
    fun unselectedTargetIdIsNotIncludedInTheDownstreamSelection() = runTest(dispatcher) {
        val viewModel = createViewModel()
        viewModel.onAction(LearningTargetAction.TargetToggled("t2"))
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(LearningTargetAction.ContinueRequested)
        advanceUntilIdle()

        val navigation = effect.await() as LearningTargetEffect.OpenFinalConfirmation
        assertEquals(setOf("t1"), navigation.selectedTargetIds)
        assertFalse("An unselected target must not be marked for downstream learning", "t2" in navigation.selectedTargetIds)
    }

    @Test
    fun multipleFocusSentencesMustBeVisitedBeforeFinalConfirmation() = runTest(dispatcher) {
        val viewModel = LearningTargetViewModel(
            chapters = chapters.map { chapter -> chapter.copy(
                sentences = chapter.sentences.map { it.copy(isFocus = true) },
            ) },
            selectedCandidateIds = mapOf("u1" to "e1", "u2" to "e2"),
            extractedTargets = initialTargets,
            queryableExpressions = emptyList(),
            generator = LearningTargetGenerator { _, _ -> LearningTargetGenerationResult.Failed },
        )
        viewModel.onAction(LearningTargetAction.TargetToggled("t1"))
        viewModel.onAction(LearningTargetAction.ContinueRequested)
        advanceUntilIdle()
        assertFalse("The first sentence must not submit every sentence", viewModel.uiState.value.submitting)
        assertEquals(setOf("t2", "ignored"), viewModel.uiState.value.selectedTargetIds)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(LearningTargetAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(LearningTargetEffect.OpenFinalConfirmation(setOf("t2", "ignored"), initialTargets), effect.await())
    }

    @Test
    fun unexpectedGenerationFailureReleasesBusyStateAndPreservesSelection() = runTest(dispatcher) {
        val viewModel = createViewModel(LearningTargetGenerator { _, _ -> error("transport failed") })
        viewModel.onAction(LearningTargetAction.ExpressionRequested("u1", "q1"))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.generating)
        assertTrue(viewModel.uiState.value.generationFailed)
        assertEquals(setOf("t1", "t2"), viewModel.uiState.value.selectedTargetIds)
    }

    @Test fun repeatedWordActionTogglesSavedTargetWithoutGeneratingAgain() = runTest(dispatcher) {
        var calls = 0
        val viewModel = createViewModel(LearningTargetGenerator { _, _ ->
            calls++
            LearningTargetGenerationResult.Added(LearningTarget("word-expressed", "u1", "expressed", "表达了", LearningTargetType.WORD))
        })
        repeat(2) {
            viewModel.onAction(LearningTargetAction.ExpressionRequested("u1", "word:2"))
            advanceUntilIdle()
        }
        assertEquals(1, calls)
        assertEquals(1, viewModel.uiState.value.targets.count { it.targetId == "word-expressed" })
        assertFalse("word-expressed" in viewModel.uiState.value.selectedTargetIds)
        viewModel.onAction(LearningTargetAction.ExpressionRequested("u1", "word:2"))
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue("word-expressed" in viewModel.uiState.value.selectedTargetIds)
    }

    @Test fun anotherSentenceCannotRequestATargetWhileFirstSentenceIsActive() = runTest(dispatcher) {
        var calls = 0
        val viewModel = LearningTargetViewModel(
            chapters.map { it.copy(sentences = it.sentences.map { sentence -> sentence.copy(isFocus = true) }) },
            mapOf("u1" to "e1", "u2" to "e2"), initialTargets,
            listOf(QueryableExpression("other", "u2", "made")),
            LearningTargetGenerator { _, _ -> calls++; LearningTargetGenerationResult.Failed },
        )
        viewModel.onAction(LearningTargetAction.ExpressionRequested("u2", "other"))
        advanceUntilIdle()
        assertEquals(0, calls)
        assertFalse(viewModel.uiState.value.generationFailed)
    }

    @Test fun wrongReturnedWordCannotEnterTheSelectedList() = runTest(dispatcher) {
        val viewModel = createViewModel(LearningTargetGenerator { _, _ ->
            LearningTargetGenerationResult.Added(LearningTarget("wrong", "u1", "clearly", "清楚地", LearningTargetType.WORD))
        })
        viewModel.onAction(LearningTargetAction.ExpressionRequested("u1", "word:2"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.generationFailed)
        assertEquals(initialTargets.take(2), viewModel.uiState.value.targets)
        assertEquals(setOf("t1", "t2"), viewModel.uiState.value.selectedTargetIds)
    }

    @Test fun reusedTargetIdCannotReplaceADifferentExistingTarget() = runTest(dispatcher) {
        val viewModel = createViewModel(LearningTargetGenerator { _, _ ->
            LearningTargetGenerationResult.Added(LearningTarget("t1", "u1", "expressed", "表达了", LearningTargetType.WORD))
        })
        viewModel.onAction(LearningTargetAction.ExpressionRequested("u1", "word:2"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.generationFailed)
        assertEquals(initialTargets.take(2), viewModel.uiState.value.targets)
    }

    private fun createViewModel(
        generator: LearningTargetGenerator = LearningTargetGenerator { _, _ ->
            LearningTargetGenerationResult.Failed
        },
    ) = LearningTargetViewModel(
        chapters = chapters,
        selectedCandidateIds = mapOf("u1" to "e1", "u2" to "e2"),
        extractedTargets = initialTargets,
        queryableExpressions = listOf(
            QueryableExpression("q1", "u1", "more clearly"),
            QueryableExpression("ignored-q", "u2", "happy"),
        ),
        generator = generator,
    )
}
