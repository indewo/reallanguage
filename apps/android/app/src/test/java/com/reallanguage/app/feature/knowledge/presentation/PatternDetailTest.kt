package com.reallanguage.app.feature.knowledge.presentation

import com.reallanguage.app.feature.knowledge.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*

@OptIn(ExperimentalCoroutinesApi::class)
class PatternDetailTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun selectedPatternExposesFullInlineDetailAndThreeExamples() = runTest(dispatcher) {
        val viewModel = PatternBookViewModel(Repository)
        advanceUntilIdle()
        viewModel.onAction(PatternBookAction.PatternSelected("p1"))
        val detail = viewModel.uiState.value.expandedPattern!!
        Assert.assertEquals("第一次独立做某件事", detail.representativeScene)
        Assert.assertEquals(3, detail.examples.size)
        Assert.assertEquals(2, detail.consecutiveSuccessCount)
        Assert.assertEquals(5, detail.occurrenceCount)
    }

    @Test fun exampleAudioBelongsToExpandedPattern() = runTest(dispatcher) {
        val viewModel = PatternBookViewModel(Repository)
        advanceUntilIdle()
        viewModel.onAction(PatternBookAction.PatternSelected("p1"))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PatternBookAction.ExamplePronunciationRequested("p1", 0))
        advanceUntilIdle()
        Assert.assertEquals(PatternBookEffect.PlayExample("I cooked alone for the first time."), effect.await())
    }

    @Test fun removeOnlyOpensConfirmationAndDoesNotMutateList() = runTest(dispatcher) {
        val viewModel = PatternBookViewModel(Repository)
        advanceUntilIdle()
        viewModel.onAction(PatternBookAction.PatternSelected("p1"))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PatternBookAction.RemoveRequested)
        advanceUntilIdle()
        Assert.assertEquals(PatternBookEffect.OpenRemovalConfirmation("p1"), effect.await())
        Assert.assertEquals(1, viewModel.uiState.value.patterns.size)
    }

    private object Repository : PatternBookRepository {
        override suspend fun loadAllPatterns() = PatternLoadResult.Ready(listOf(detail))
    }
    companion object {
        private val detail = PatternRecord(
            "p1", "for the first time", "表示第一次经历", KnowledgeStatus.MASTERED,
            KnowledgeStatus.LEARNING, 2, 5, "第一次独立做某件事",
            listOf(
                PatternExample("I cooked alone for the first time.", "我第一次独自做饭。", "I cooked alone for the first time."),
                PatternExample("She traveled alone for the first time.", "她第一次独自旅行。", "She traveled alone for the first time."),
                PatternExample("We met there for the first time.", "我们第一次在那里见面。", "We met there for the first time."),
            ),
        )
    }
}
