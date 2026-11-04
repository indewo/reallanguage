package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.ExplanationContentResult
import com.reallanguage.app.feature.learning.domain.ExplanationReadingProvider
import com.reallanguage.app.feature.learning.domain.ExpressionExplanation
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.SelectableExpression
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
class ExplanationReadingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val sentence = FixedLearningSentence(
        "s1", "I expressed myself more clearly.", "我表达得更清楚了。", true,
    )
    private val chapter = FixedLearningChapter("c1", 1, listOf(sentence))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun selectedNaturalExampleSurvivesProgressRestoreWithoutNewRequest() = runTest(dispatcher) {
        var saved: Map<String, String>? = null
        val store = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
            override suspend fun read() = saved
            override suspend fun write(values: Map<String, String>): Boolean { saved = values; return true }
        }
        val example = com.reallanguage.app.feature.learning.domain.NaturalExpressionExample("She expressed her idea clearly.", "她清楚地表达了自己的想法。")
        val first = createViewModel(provider(expression = { id ->
            ExplanationContentResult.ExpressionReady(ExpressionExplanation(id, "表达", naturalExample = example))
        }), store)
        advanceUntilIdle()
        first.onAction(ExplanationReadingAction.ExpressionRequested("x1"))
        advanceUntilIdle()
        val reopened = createViewModel(provider(expression = { error("No new expression request on restore") }), store)
        advanceUntilIdle()
        assertEquals(example, reopened.uiState.value.selectedExpression?.naturalExample)
    }

    @Test fun structuredExpressionFieldsSaveAndRestoreWithoutReplacingTheRoundHint() = runTest(dispatcher) {
        var saved: Map<String, String>? = null
        val store = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
            override suspend fun read() = saved
            override suspend fun write(values: Map<String, String>): Boolean { saved = values; return true }
        }
        val explanation = ExpressionExplanation("x1", "完整原解释", contextualMeaningZh = "在这里指表达想法", usageZh = "express + 内容", expressionHintZh = "注意宾语。")
        val first = createViewModel(provider(expression = { ExplanationContentResult.ExpressionReady(explanation) }), store)
        advanceUntilIdle()
        first.onAction(ExplanationReadingAction.ExplanationFinished)
        first.onAction(ExplanationReadingAction.ReadFinished)
        advanceUntilIdle()
        first.onAction(ExplanationReadingAction.ExpressionRequested("x1"))
        advanceUntilIdle()
        assertEquals("在这里指表达想法", saved!!["expressionContextualMeaningZh"])
        assertEquals("express + 内容", saved!!["expressionUsageZh"])
        assertEquals("注意宾语。", saved!!["expressionHintZh"])
        assertEquals("新提示 2", saved!!["hint"])
        val reopened = createViewModel(provider(expression = { error("Restore must not generate") }), store)
        advanceUntilIdle()
        assertEquals(explanation, reopened.uiState.value.selectedExpression)
        assertEquals("新提示 2", reopened.uiState.value.currentIncrementalHint)
    }

    @Test fun partiallyStoredStructuredGroupCannotRestoreAsSuccessfulExplanation() = runTest(dispatcher) {
        val base = mapOf("version" to "1", "reads" to "0", "explaining" to "false", "expressionId" to "x1", "expressionZh" to "原解释")
        val group = mapOf("expressionContextualMeaningZh" to "此处意思", "expressionUsageZh" to "实际用法", "expressionHintZh" to "")
        for (missing in group.keys) {
            val model = createViewModel(progressStore = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
                override suspend fun read() = base + (group - missing)
                override suspend fun write(values: Map<String, String>) = error("Restore is read-only")
            })
            advanceUntilIdle()
            assertTrue(model.uiState.value.progressFailed)
            assertNull(model.uiState.value.selectedExpression)
        }
    }

    @Test fun storageRetrySavesTheDeliveredStructuredGroupWithoutAnotherExpressionRequest() = runTest(dispatcher) {
        var allowWrite = false
        var saved: Map<String, String>? = null
        var requests = 0
        val store = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
            override suspend fun read() = saved
            override suspend fun write(values: Map<String, String>): Boolean {
                if (!allowWrite) return false
                saved = values; return true
            }
        }
        val expected = ExpressionExplanation("x1", "原解释", contextualMeaningZh = "当前句语义", usageZh = "明确用法", expressionHintZh = null)
        val model = createViewModel(provider(expression = { requests++; ExplanationContentResult.ExpressionReady(expected) }), store)
        advanceUntilIdle()
        model.onAction(ExplanationReadingAction.ExpressionRequested("x1"))
        advanceUntilIdle()
        assertTrue(model.uiState.value.progressFailed)
        assertNull(saved)
        allowWrite = true
        model.onAction(ExplanationReadingAction.RetryProgressRequested)
        advanceUntilIdle()
        assertFalse(model.uiState.value.progressFailed)
        assertEquals(expected, model.uiState.value.selectedExpression)
        assertEquals("", saved!!["expressionHintZh"])
        assertEquals(1, requests)
        val restored = createViewModel(provider(expression = { error("No new generation") }), store)
        advanceUntilIdle()
        assertEquals(expected, restored.uiState.value.selectedExpression)
    }

    @Test fun selectingAnotherWordDoesNotWaitForOrDisplayThePreviousSlowAnswer() = runTest(dispatcher) {
        val oldReply = kotlinx.coroutines.CompletableDeferred<Unit>()
        val requested = mutableListOf<String>()
        val model = createViewModel(provider(expression = { id ->
            requested += id
            if (id == "x1") kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldReply.await() }
            ExplanationContentResult.ExpressionReady(ExpressionExplanation(id, "解释 $id",
                contextualMeaningZh = "语境 $id", usageZh = "用法 $id", expressionHintZh = "提示 $id"))
        }))
        model.onAction(ExplanationReadingAction.ExpressionRequested("x1"))
        advanceUntilIdle()
        model.onAction(ExplanationReadingAction.ExpressionRequested("x2"))
        advanceUntilIdle()
        val selectedBeforeOldReply = model.uiState.value.selectedExpression?.expressionId
        oldReply.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("x1", "x2"), requested)
        assertEquals("x2", selectedBeforeOldReply)
        assertEquals("x2", model.uiState.value.selectedExpression?.expressionId)
        assertEquals("语境 x2", model.uiState.value.selectedExpression?.contextualMeaningZh)
        assertEquals("用法 x2", model.uiState.value.selectedExpression?.usageZh)
        assertEquals("提示 x2", model.uiState.value.selectedExpression?.expressionHintZh)
        assertEquals("more clearly", model.uiState.value.pronunciationText)
    }

    @Test fun mutingStopsCurrentExplanationAndLaterHintsStayTextOnlyForThisSentence() = runTest(dispatcher) {
        val model = createViewModel()
        model.onAction(ExplanationReadingAction.AudioToggled)
        assertFalse(model.uiState.value.explaining)
        model.onAction(ExplanationReadingAction.ReadFinished)
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.completedReadCount)
        assertFalse(model.uiState.value.audioPlaying)
        assertFalse(model.uiState.value.explaining)
        assertTrue(model.uiState.value.currentIncrementalHint != null)
        model.onAction(ExplanationReadingAction.AudioToggled)
        assertTrue(model.uiState.value.audioPlaying)
        assertEquals(sentence.textEn, model.uiState.value.pronunciationText)
    }

    @Test fun playbackFailureDoesNotCountAReadAndManualRetryPlaysOnlyTheTargetSentence() {
        val model = createViewModel()
        model.onAction(ExplanationReadingAction.AudioPlaybackFailed)
        assertTrue(model.uiState.value.audioFailed)
        assertFalse(model.uiState.value.explaining)
        assertEquals(0, model.uiState.value.completedReadCount)
        model.onAction(ExplanationReadingAction.AudioToggled)
        assertFalse(model.uiState.value.audioFailed)
        assertEquals(sentence.textEn, model.uiState.value.pronunciationText)
    }

    @Test fun disabledVoiceSettingsPreventAutomaticAndManualPlaybackWithoutBlockingTextLearning() = runTest(dispatcher) {
        val model = ExplanationReadingViewModel(chapter, "s1", "基础讲解", emptyList(), provider(),
            voicePreferencesProvider = com.reallanguage.app.feature.learning.domain.LearningVoicePreferencesProvider {
                com.reallanguage.app.feature.learning.domain.LearningVoicePreferences(false, false, 2)
            })
        advanceUntilIdle()
        assertFalse(model.uiState.value.audioPlaying)
        assertFalse(model.uiState.value.explaining)
        model.onAction(ExplanationReadingAction.AudioToggled)
        assertFalse(model.uiState.value.audioPlaying)
        model.onAction(ExplanationReadingAction.ReadFinished)
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.completedReadCount)
        assertFalse(model.uiState.value.audioPlaying)
    }

    @Test
    fun threeReadCompletionsKeepRoundsHiddenAndRequireUnderstandingToAdvance() =
        runTest(dispatcher) {
            val requestedRounds = mutableListOf<Int>()
            val viewModel = createViewModel(
                provider = provider(
                    hint = { round ->
                        requestedRounds += round
                        ExplanationContentResult.Ready("新提示 $round")
                    },
                ),
            )
            viewModel.onAction(ExplanationReadingAction.ExplanationFinished)
            repeat(3) { index ->
                viewModel.onAction(ExplanationReadingAction.ReadFinished)
                advanceUntilIdle()
                if (index < 2) {
                    assertTrue(viewModel.uiState.value.explaining)
                    viewModel.onAction(ExplanationReadingAction.ExplanationFinished)
                }
            }
            assertEquals(listOf(2, 3), requestedRounds)
            assertTrue(viewModel.uiState.value.threeReadsCompleted)
            assertEquals(3, viewModel.uiState.value.completedReadCount)
            val effect = async { viewModel.effects.first() }
            viewModel.onAction(ExplanationReadingAction.UnderstoodRequested)
            advanceUntilIdle()
            assertEquals(ExplanationReadingEffect.OpenReadAndSpeak, effect.await())
        }

    @Test
    fun skippingExplanationDoesNotCountAsReadingOrMastery() {
        val viewModel = createViewModel()
        viewModel.onAction(ExplanationReadingAction.SkipExplanationRequested)
        assertFalse(viewModel.uiState.value.explaining)
        assertEquals(0, viewModel.uiState.value.completedReadCount)
        assertFalse(viewModel.uiState.value.threeReadsCompleted)
    }

    @Test
    fun consecutiveExpressionSelectionReplacesTheVisibleExplanation() =
        runTest(dispatcher) {
            val viewModel = createViewModel(
                provider = provider(
                    expression = { expressionId ->
                        ExplanationContentResult.ExpressionReady(
                            ExpressionExplanation(expressionId, "当前语境解释：$expressionId"),
                        )
                    },
                ),
            )
            viewModel.onAction(ExplanationReadingAction.ExpressionRequested("x1"))
            advanceUntilIdle()
            viewModel.onAction(ExplanationReadingAction.ExpressionRequested("x2"))
            advanceUntilIdle()
            assertEquals("x2", viewModel.uiState.value.selectedExpression?.expressionId)
            assertEquals("当前语境解释：x2", viewModel.uiState.value.selectedExpression?.explanationZh)
        }

    @Test
    fun failedStorageDoesNotConfirmReadAndRetryDoesNotGenerateTwice() = runTest(dispatcher) {
        var allowWrite = true
        var saved: Map<String, String>? = null
        var hints = 0
        val store = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
            override suspend fun read() = saved
            override suspend fun write(values: Map<String, String>): Boolean {
                if (!allowWrite) return false
                saved = values; return true
            }
        }
        val model = createViewModel(provider(hint = { hints++; ExplanationContentResult.Ready("提示") }), store)
        advanceUntilIdle()
        model.onAction(ExplanationReadingAction.ExplanationFinished)
        advanceUntilIdle()
        allowWrite = false
        model.onAction(ExplanationReadingAction.ReadFinished)
        advanceUntilIdle()
        assertEquals(0, model.uiState.value.completedReadCount)
        assertTrue(model.uiState.value.progressFailed)
        assertEquals(0, hints)
        allowWrite = true
        model.onAction(ExplanationReadingAction.RetryProgressRequested)
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.completedReadCount)
        assertEquals(1, hints)
        assertFalse(model.uiState.value.progressFailed)
    }

    @Test
    fun expressionFailureDoesNotRetryAnAlreadyDeliveredRound() = runTest(dispatcher) {
        val rounds = mutableListOf<Int>()
        val model = createViewModel(provider(hint = { rounds += it; ExplanationContentResult.Ready("提示") }, expression = { ExplanationContentResult.Failed }))
        model.onAction(ExplanationReadingAction.ExplanationFinished)
        model.onAction(ExplanationReadingAction.ReadFinished)
        advanceUntilIdle()
        model.onAction(ExplanationReadingAction.ExplanationFinished)
        model.onAction(ExplanationReadingAction.ExpressionRequested("x1"))
        advanceUntilIdle()
        model.onAction(ExplanationReadingAction.ReadFinished)
        advanceUntilIdle()
        assertEquals(listOf(2, 3), rounds)
        assertEquals(2, model.uiState.value.completedReadCount)
    }

    @Test
    fun newViewModelRestoresConfirmedReadsAndDeliveredHint() = runTest(dispatcher) {
        var saved: Map<String, String>? = null
        val store = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
            override suspend fun read() = saved
            override suspend fun write(values: Map<String, String>): Boolean { saved = values; return true }
        }
        val first = createViewModel(progressStore = store)
        advanceUntilIdle()
        first.onAction(ExplanationReadingAction.ExplanationFinished)
        advanceUntilIdle()
        first.onAction(ExplanationReadingAction.ReadFinished)
        advanceUntilIdle()
        first.onAction(ExplanationReadingAction.ExplanationFinished)
        advanceUntilIdle()
        val reopened = createViewModel(progressStore = store, provider = provider(hint = { error("No new AI request is allowed on restore") }))
        advanceUntilIdle()
        assertEquals(1, reopened.uiState.value.completedReadCount)
        assertEquals("新提示 2", reopened.uiState.value.currentIncrementalHint)
        assertFalse(reopened.uiState.value.explaining)
    }

    @Test
    fun retryingFailedHintDoesNotCountAnotherReadOrSkipTheFailedRound() = runTest(dispatcher) {
        val rounds = mutableListOf<Int>()
        val viewModel = createViewModel(provider(hint = { round ->
            rounds += round
            if (rounds.size == 1) ExplanationContentResult.Failed
            else ExplanationContentResult.Ready("恢复提示")
        }))
        viewModel.onAction(ExplanationReadingAction.ExplanationFinished)
        viewModel.onAction(ExplanationReadingAction.ReadFinished)
        advanceUntilIdle()
        viewModel.onAction(ExplanationReadingAction.ReadFinished)
        advanceUntilIdle()
        assertEquals(listOf(2, 2), rounds)
        assertEquals(1, viewModel.uiState.value.completedReadCount)
        assertFalse(viewModel.uiState.value.loadingFailed)
        assertEquals("恢复提示", viewModel.uiState.value.currentIncrementalHint)
    }

    @Test
    fun failedIncrementalHintKeepsCurrentSentenceWithoutAdvancing() = runTest(dispatcher) {
        val viewModel = createViewModel(
            provider = provider(hint = { ExplanationContentResult.Failed }),
        )
        viewModel.onAction(ExplanationReadingAction.ExplanationFinished)
        viewModel.onAction(ExplanationReadingAction.ReadFinished)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadingFailed)
        assertEquals(1, viewModel.uiState.value.completedReadCount)
        assertFalse(viewModel.uiState.value.threeReadsCompleted)
        assertNull(viewModel.uiState.value.currentIncrementalHint)
    }

    @Test
    fun navigationAudioAndCompletedReadingControlsRespectTheirBoundaries() =
        runTest(dispatcher) {
            val viewModel = createViewModel()
            viewModel.onAction(ExplanationReadingAction.ReadFinished)
            assertEquals(0, viewModel.uiState.value.completedReadCount)
            viewModel.onAction(ExplanationReadingAction.ContinueReadingRequested)
            viewModel.onAction(ExplanationReadingAction.AudioToggled)
            assertFalse(viewModel.uiState.value.audioPlaying)

            val back = async { viewModel.effects.first() }
            viewModel.onAction(ExplanationReadingAction.BackRequested)
            advanceUntilIdle()
            assertEquals(ExplanationReadingEffect.ReturnToChapterEntry, back.await())

            val skipped = async { viewModel.effects.first() }
            viewModel.onAction(ExplanationReadingAction.SkipSentenceRequested)
            advanceUntilIdle()
            assertEquals(ExplanationReadingEffect.SentenceSkipped, skipped.await())

            val understood = async { viewModel.effects.first() }
            viewModel.onAction(ExplanationReadingAction.UnderstoodRequested)
            advanceUntilIdle()
            assertEquals(3, viewModel.uiState.value.completedReadCount)
            assertFalse(viewModel.uiState.value.audioPlaying)
            assertEquals(ExplanationReadingEffect.OpenReadAndSpeak, understood.await())
        }

    @Test
    fun invalidOrFailedExpressionDoesNotCreateAnExplanation() = runTest(dispatcher) {
        val viewModel = createViewModel(
            provider = provider(expression = { ExplanationContentResult.Failed }),
        )
        viewModel.onAction(ExplanationReadingAction.ExpressionRequested("unknown"))
        assertFalse(viewModel.uiState.value.loading)
        viewModel.onAction(ExplanationReadingAction.ExpressionRequested("x1"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadingFailed)
        assertNull(viewModel.uiState.value.selectedExpression)
    }

    private fun createViewModel(
        provider: ExplanationReadingProvider = provider(),
        progressStore: com.reallanguage.app.feature.learning.domain.LearningProgressStore? = null,
    ) = ExplanationReadingViewModel(
        chapter = chapter,
        currentFocusSentenceId = "s1",
        baseExplanationZh = "基础讲解",
        expressions = listOf(
            SelectableExpression("x1", "expressed", 2, 11),
            SelectableExpression("x2", "more clearly", 19, 31),
        ),
        provider = provider,
        progressStore = progressStore,
    )

    private fun provider(
        hint: suspend (Int) -> ExplanationContentResult = {
            ExplanationContentResult.Ready("新提示 $it")
        },
        expression: suspend (String) -> ExplanationContentResult = {
            ExplanationContentResult.ExpressionReady(ExpressionExplanation(it, "解释 $it"))
        },
    ) = object : ExplanationReadingProvider {
        override suspend fun incrementalHint(
            sentenceId: String,
            round: Int,
            baseExplanationZh: String,
        ) = hint(round)

        override suspend fun explainExpression(
            sentenceId: String,
            expressionId: String,
        ) = expression(expressionId)
    }
}
