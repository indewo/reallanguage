package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class LockedExplanationProgressTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()
    private val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I drink tea.", "我喝茶。", true)))
    private val expressions = listOf(SelectableExpression("word-2", "tea", 8, 11))
    private fun explanation(meaning: String) = ExpressionExplanation("word-2", "原说明", contextualMeaningZh = meaning,
        usageZh = "用于当前句的饮品", expressionHintZh = null)
    private fun saved(meaning: String) = mapOf("version" to "1", "reads" to "1", "explaining" to "false",
        "expressionId" to "word-2", "expressionZh" to "原说明", "expressionContextualMeaningZh" to meaning,
        "expressionUsageZh" to "用于当前句的饮品", "expressionHintZh" to "")

    @Test fun restoringAConflictingStoredMeaningDoesNotDisplayOrRewriteItAndDoesNotRegenerate() = runTest(dispatcher) {
        val store = Store(saved("茶树"))
        val provider = Provider()
        val model = model(provider, store)
        advanceUntilIdle()
        assertTrue(model.uiState.value.progressFailed)
        assertNull(model.uiState.value.selectedExpression)
        assertEquals(0, provider.generations)
        assertEquals(0, store.writes)
        assertEquals(saved("茶树"), store.value)
    }

    @Test fun validReadOnlyRestoreRetainsExactMeaningAndProgressWithZeroGeneration() = runTest(dispatcher) {
        val provider = Provider()
        val store = Store(saved(" 茶（饮品） "))
        val model = model(provider, store)
        advanceUntilIdle()
        assertFalse(model.uiState.value.progressFailed)
        assertEquals(" 茶（饮品） ", model.uiState.value.selectedExpression?.contextualMeaningZh)
        assertEquals(1, model.uiState.value.completedReadCount)
        assertEquals(1, provider.validations)
        assertEquals(0, provider.generations)
        assertEquals(0, store.writes)
    }

    @Test fun invalidFreshExplanationCannotBeCommittedEvenIfProviderLabelsItReady() = runTest(dispatcher) {
        val provider = Provider(explanation("茶树"))
        val store = Store(null)
        val model = model(provider, store)
        advanceUntilIdle()
        model.onAction(ExplanationReadingAction.ExpressionRequested("word-2"))
        advanceUntilIdle()
        assertNull(model.uiState.value.selectedExpression)
        assertTrue(model.uiState.value.loadingFailed)
        assertEquals(0, store.writes)
        assertEquals(1, provider.generations)
    }

    private fun model(provider: Provider, store: Store) = ExplanationReadingViewModel(chapter, "s1", "完整基础讲解", expressions, provider, store)
    private class Store(var value: Map<String, String>?) : LearningProgressStore {
        var writes = 0
        override suspend fun read() = value
        override suspend fun write(values: Map<String, String>): Boolean { writes++; value = values; return true }
    }
    private class Provider(private val fresh: ExpressionExplanation? = null) : ExplanationReadingProvider {
        var generations = 0
        var validations = 0
        override suspend fun validateSavedExpression(sentenceId: String, explanation: ExpressionExplanation): Boolean {
            validations++
            return sentenceId == "s1" && explanation.contextualMeaningZh == " 茶（饮品） "
        }
        override suspend fun incrementalHint(sentenceId: String, round: Int, baseExplanationZh: String) = error("No hint request in recovery")
        override suspend fun explainExpression(sentenceId: String, expressionId: String): ExplanationContentResult {
            generations++
            return ExplanationContentResult.ExpressionReady(requireNotNull(fresh))
        }
    }
}
