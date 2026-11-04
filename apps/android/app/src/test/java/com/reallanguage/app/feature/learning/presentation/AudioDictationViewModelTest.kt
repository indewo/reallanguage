package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedWritingComparator
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionRepository
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionResult
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
class AudioDictationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chapter = FixedLearningChapter(
        "c1", 1,
        listOf(
            FixedLearningSentence(
                "s1", "I expressed myself clearly.", "我清楚地表达了自己。", true,
            ),
        ),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun mobileSmartApostropheIsAcceptedAndSavedWithoutRewritingTheInputOrOpeningCorrection() = runTest(dispatcher) {
        val mobileInput = "I’m ready."
        var completions = 0
        val model = AudioDictationViewModel(
            chapter = FixedLearningChapter("mobile", 1, listOf(FixedLearningSentence("s1", "I am ready.", "我准备好了。", true))),
            currentFocusSentenceId = "s1", lockedHintItems = emptyList(), comparator = FixedWritingComparator(),
            completionRepository = AudioDictationCompletionRepository { completions++; AudioDictationCompletionResult.Saved },
        )
        val effect = async { model.effects.first() }
        model.onAction(AudioDictationAction.InputChanged(mobileInput))
        model.onAction(AudioDictationAction.SubmitRequested)
        advanceUntilIdle()
        assertTrue(model.uiState.value.correct)
        assertFalse(model.uiState.value.incorrect)
        assertFalse(effect.isCompleted)
        assertEquals(mobileInput, model.uiState.value.input)
        assertEquals(1, completions)
        model.onAction(AudioDictationAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(AudioDictationEffect.OpenFullSentenceSpeaking, effect.await())
    }

    @Test fun playbackFailureStopsLoopWithoutCompletingOrErasingInputAndCanRetry() = runTest(dispatcher) {
        val model = createViewModel()
        model.onAction(AudioDictationAction.InputChanged("unfinished"))
        model.onAction(AudioDictationAction.AudioPlaybackFailed)
        assertFalse(model.uiState.value.audioLooping)
        assertTrue(model.uiState.value.audioFailed)
        assertFalse(model.uiState.value.correct)
        assertEquals("unfinished", model.uiState.value.input)
        model.onAction(AudioDictationAction.AudioToggled)
        assertTrue(model.uiState.value.audioLooping)
        assertFalse(model.uiState.value.audioFailed)
    }

    @Test fun acceptedAnswerRemainsVisibleUntilUserContinuesAndCannotBeOverwritten() = runTest(dispatcher) {
        var submissions = 0
        val model = createViewModel(AudioDictationCompletionRepository { submissions++; AudioDictationCompletionResult.Saved })
        val effect = async { model.effects.first() }
        model.onAction(AudioDictationAction.InputChanged("I expressed myself clearly."))
        model.onAction(AudioDictationAction.SubmitRequested)
        model.onAction(AudioDictationAction.InputChanged("changed while saving"))
        advanceUntilIdle()
        assertTrue(model.uiState.value.correct)
        assertFalse(effect.isCompleted)
        assertEquals("I expressed myself clearly.", model.uiState.value.input)
        model.onAction(AudioDictationAction.SubmitRequested)
        model.onAction(AudioDictationAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(1, submissions)
        assertEquals(AudioDictationEffect.OpenFullSentenceSpeaking, effect.await())
    }

    @Test fun typedInputAndExpandedLockedHintsSurviveModuleReentry() = runTest(dispatcher) {
        var saved: Map<String, String>? = null
        val store = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
            override suspend fun read() = saved
            override suspend fun write(values: Map<String, String>): Boolean { saved = values; return true }
        }
        fun create() = AudioDictationViewModel(chapter, "s1", listOf("固定提示"), comparator = FixedWritingComparator(), completionRepository = AudioDictationCompletionRepository { AudioDictationCompletionResult.Saved }, progressStore = store)
        val first = create(); advanceUntilIdle()
        first.onAction(AudioDictationAction.InputChanged("unfinished input"))
        first.onAction(AudioDictationAction.HintToggled)
        first.onAction(AudioDictationAction.AudioToggled)
        advanceUntilIdle()
        val restored = create(); advanceUntilIdle()
        assertEquals("unfinished input", restored.uiState.value.input)
        assertTrue(restored.uiState.value.hintExpanded)
        assertFalse(restored.uiState.value.audioLooping)
    }

    @Test
    fun entersWithSlowLoopingAudioAndHintCollapsed() {
        val state = createViewModel().uiState.value
        assertTrue(state.audioLooping)
        assertFalse(state.hintExpanded)
    }

    @Test
    fun correctContinuousInputOpensSpeakingWithoutCreatingMastery() = runTest(dispatcher) {
        val viewModel = createViewModel()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(AudioDictationAction.InputChanged("i expressed myself clearly"))
        viewModel.onAction(AudioDictationAction.SubmitRequested)
        advanceUntilIdle()
        viewModel.onAction(AudioDictationAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(AudioDictationEffect.OpenFullSentenceSpeaking, effect.await())
        assertTrue(viewModel.uiState.value.correct)
    }

    @Test
    fun incorrectInputOpensCorrectionAndBlankInputStaysOnStep() = runTest(dispatcher) {
        val viewModel = createViewModel()
        viewModel.onAction(AudioDictationAction.SubmitRequested)
        assertTrue(viewModel.uiState.value.emptySubmission)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(AudioDictationAction.InputChanged("I clearly."))
        viewModel.onAction(AudioDictationAction.SubmitRequested)
        advanceUntilIdle()
        assertEquals(AudioDictationEffect.OpenReadWriteCorrection("I clearly."), effect.await())
        assertTrue(viewModel.uiState.value.incorrect)
    }

    @Test
    fun audioHintInputAndBackControlsRemainLocalToTheCurrentStep() = runTest(dispatcher) {
        val viewModel = createViewModel()
        viewModel.onAction(AudioDictationAction.AudioToggled)
        viewModel.onAction(AudioDictationAction.HintToggled)
        assertFalse(viewModel.uiState.value.audioLooping)
        assertTrue(viewModel.uiState.value.hintExpanded)
        viewModel.onAction(AudioDictationAction.InputChanged("draft"))
        assertEquals("draft", viewModel.uiState.value.input)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(AudioDictationAction.BackRequested)
        advanceUntilIdle()
        assertEquals(AudioDictationEffect.ReturnToHandwriting, effect.await())
    }

    @Test
    fun correctAnswerCannotAdvanceUntilItsLocalStepIsSaved() = runTest(dispatcher) {
        var attempts = 0
        val viewModel = createViewModel(
            AudioDictationCompletionRepository {
                attempts += 1
                if (attempts == 1) AudioDictationCompletionResult.Failed
                else AudioDictationCompletionResult.Saved
            },
        )
        viewModel.onAction(AudioDictationAction.InputChanged("I expressed myself clearly."))
        viewModel.onAction(AudioDictationAction.SubmitRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.saveFailed)
        assertFalse(viewModel.uiState.value.correct)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(AudioDictationAction.SubmitRequested)
        advanceUntilIdle()
        assertEquals(2, attempts)
        viewModel.onAction(AudioDictationAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(AudioDictationEffect.OpenFullSentenceSpeaking, effect.await())
    }

    private fun createViewModel(
        completionRepository: AudioDictationCompletionRepository =
            AudioDictationCompletionRepository { AudioDictationCompletionResult.Saved },
    ) = AudioDictationViewModel(
        chapter = chapter,
        currentFocusSentenceId = "s1",
        lockedHintItems = listOf("express oneself：表达自己"),
        comparator = FixedWritingComparator(),
        completionRepository = completionRepository,
    )
}
