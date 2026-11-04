package com.reallanguage.app.feature.learning.presentation

import com.reallanguage.app.feature.learning.domain.CompleteWritingEvaluator
import com.reallanguage.app.feature.learning.domain.CompleteWritingRepository
import com.reallanguage.app.feature.learning.domain.CompleteWritingSaveResult
import com.reallanguage.app.feature.learning.domain.FinalWritingBatchResult
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedWritingComparator
import com.reallanguage.app.feature.learning.domain.LockedWritingTarget
import com.reallanguage.app.feature.learning.domain.WritingTargetType
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
class CompleteWritingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val comparator = FixedWritingComparator()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun ambiguousTargetLocationKeepsAnswersAndDoesNotSaveOrAdvance() = runTest(dispatcher) {
        val fixed = FixedLearningSentence("s1", "Run run.", "跑两次。", true)
        var saves = 0
        val model = CompleteWritingViewModel(
            FixedLearningChapter("ambiguous", 1, listOf(fixed)),
            listOf(LockedWritingTarget("run", "s1", WritingTargetType.MEANING, listOf("Run"),
                com.reallanguage.app.feature.learning.domain.WritingTargetSpan(0, 3, "Run"))),
            comparator, CompleteWritingEvaluator(comparator),
            CompleteWritingRepository { _, _ -> saves++; CompleteWritingSaveResult.Saved },
        )
        submitAndAdvance(model, fixed.textEn)
        submitAndAdvance(model, fixed.textEn)
        model.onAction(CompleteWritingAction.FinalInputChanged("s1", "Run."))
        model.onAction(CompleteWritingAction.SubmitRequested)
        advanceUntilIdle()
        assertEquals(0, saves)
        assertTrue(model.uiState.value.evaluationUnavailable)
        assertNull(model.uiState.value.finalResult)
        assertEquals("Run.", model.uiState.value.finalInputs["s1"])
        assertFalse(model.uiState.value.saving)
        model.onAction(CompleteWritingAction.FinalInputChanged("s1", fixed.textEn))
        model.onAction(CompleteWritingAction.SubmitRequested)
        advanceUntilIdle()
        assertEquals(1, saves)
        assertFalse(model.uiState.value.evaluationUnavailable)
        assertTrue((model.uiState.value.finalResult as FinalWritingBatchResult.Valid).sentenceResults.single().targetResults.single().passed)
    }

    @Test fun mobileSmartApostropheDoesNotCreateFalseWholeSentenceOrLockedTargetFailure() = runTest(dispatcher) {
        val fixed = FixedLearningSentence("s1", "I cannot wait.", "我等不及了。", true)
        val mobileInput = "I can’t wait."
        var saved: FinalWritingBatchResult? = null
        val model = CompleteWritingViewModel(
            FixedLearningChapter("mobile", 1, listOf(fixed)),
            listOf(LockedWritingTarget("cannot", "s1", WritingTargetType.MEANING, listOf("cannot"))),
            comparator, CompleteWritingEvaluator(comparator),
            CompleteWritingRepository { _, result -> saved = result; CompleteWritingSaveResult.Saved },
        )
        submitAndAdvance(model, fixed.textEn)
        submitAndAdvance(model, fixed.textEn)
        assertEquals(CompleteWritingPhase.FINAL_BATCH, model.uiState.value.phase)
        model.onAction(CompleteWritingAction.FinalInputChanged("s1", mobileInput))
        model.onAction(CompleteWritingAction.SubmitRequested)
        advanceUntilIdle()
        val result = (saved as FinalWritingBatchResult.Valid).sentenceResults.single()
        assertTrue(result.wholeSentencePassed)
        assertTrue(result.targetResults.single().passed)
        assertEquals(saved, model.uiState.value.finalResult)
        assertEquals(mobileInput, model.uiState.value.finalInputs["s1"])
        assertFalse(model.uiState.value.helpUsed)
    }

    @Test fun finalDraftAndUsedHelpSurviveRecreatingTheLearningModule() = runTest(dispatcher) {
        var saved: Map<String, String>? = null
        val store = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
            override suspend fun read() = saved
            override suspend fun write(values: Map<String, String>): Boolean { saved = values; return true }
        }
        fun create() = CompleteWritingViewModel(chapter, emptyList(), comparator, CompleteWritingEvaluator(comparator), CompleteWritingRepository { _, _ -> CompleteWritingSaveResult.Saved }, store)
        val firstEntry = create()
        advanceUntilIdle()
        moveToFinal(firstEntry)
        firstEntry.onAction(CompleteWritingAction.FinalInputChanged("s1", "my saved unfinished input"))
        firstEntry.onAction(CompleteWritingAction.HelpRequested)
        advanceUntilIdle()
        val restored = create()
        advanceUntilIdle()
        assertEquals(CompleteWritingPhase.FINAL_BATCH, restored.uiState.value.phase)
        assertEquals("my saved unfinished input", restored.uiState.value.finalInputs["s1"])
        assertTrue(restored.uiState.value.helpUsed)
        assertFalse(restored.uiState.value.audioPlaying)
    }

    @Test fun anAcceptedFinalBatchIsReadBackWithoutSavingItAgainOnReentry() = runTest(dispatcher) {
        var saves = 0
        val accepted = FinalWritingBatchResult.SupportedLearningOnly
        val repository = object : CompleteWritingRepository {
            override suspend fun loadFinalBatch(chapterId: String) = accepted
            override suspend fun saveFinalBatch(chapterId: String, result: FinalWritingBatchResult): CompleteWritingSaveResult { saves++; return CompleteWritingSaveResult.Saved }
        }
        val store = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
            override suspend fun read(): Map<String, String>? = null
            override suspend fun write(values: Map<String, String>) = true
        }
        val restored = CompleteWritingViewModel(chapter, emptyList(), comparator, CompleteWritingEvaluator(comparator), repository, store)
        advanceUntilIdle()
        assertEquals(accepted, restored.uiState.value.finalResult)
        assertEquals(CompleteWritingPhase.FINAL_BATCH, restored.uiState.value.phase)
        restored.onAction(CompleteWritingAction.SubmitRequested)
        advanceUntilIdle()
        assertEquals(0, saves)
    }

    @Test fun failedDraftSaveCannotLeaveTheModuleAndRetryKeepsTheLatestInput() = runTest(dispatcher) {
        var writable = true
        var saved: Map<String, String>? = null
        val store = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
            override suspend fun read() = saved
            override suspend fun write(values: Map<String, String>): Boolean {
                if (!writable) return false
                saved = values; return true
            }
        }
        val model = CompleteWritingViewModel(chapter, emptyList(), comparator, CompleteWritingEvaluator(comparator), CompleteWritingRepository { _, _ -> CompleteWritingSaveResult.Saved }, store)
        advanceUntilIdle()
        writable = false
        model.onAction(CompleteWritingAction.InputChanged("first"))
        model.onAction(CompleteWritingAction.InputChanged("latest unsaved input"))
        val next = async { model.effects.first() }
        model.onAction(CompleteWritingAction.BackRequested)
        advanceUntilIdle()
        assertTrue(model.uiState.value.saveFailed)
        assertFalse(next.isCompleted)
        writable = true
        model.onAction(CompleteWritingAction.BackRequested)
        advanceUntilIdle()
        assertEquals(CompleteWritingEffect.ReturnToPatternSpeaking, next.await())
        assertEquals("latest unsaved input", saved?.get("input"))
    }

    @Test fun failedOrMalformedRestoreDoesNotOverwriteTheStoredDraftWithAnEmptyOne() = runTest(dispatcher) {
        var writes = 0
        var failRead = true
        val store = object : com.reallanguage.app.feature.learning.domain.LearningProgressStore {
            override suspend fun read(): Map<String, String>? {
                if (failRead) error("isolated read failure")
                return null
            }
            override suspend fun write(values: Map<String, String>): Boolean { writes++; return true }
        }
        val model = CompleteWritingViewModel(chapter, emptyList(), comparator, CompleteWritingEvaluator(comparator), CompleteWritingRepository { _, _ -> CompleteWritingSaveResult.Saved }, store)
        advanceUntilIdle()
        model.onAction(CompleteWritingAction.InputChanged("must not replace unread data"))
        advanceUntilIdle()
        assertEquals(0, writes)
        assertTrue(model.uiState.value.saveFailed)
        failRead = false
        model.onAction(CompleteWritingAction.SubmitRequested)
        advanceUntilIdle()
        assertFalse(model.uiState.value.saveFailed)
        assertEquals("", model.uiState.value.input)
    }

    @Test
    fun doubleSubmitAndEditingDuringSaveCannotChangeTheAcceptedBatch() = runTest(dispatcher) {
        val completion = kotlinx.coroutines.CompletableDeferred<CompleteWritingSaveResult>()
        var calls = 0
        val model = createViewModel(CompleteWritingRepository { _, _ -> calls++; completion.await() })
        moveToFinal(model)
        model.onAction(CompleteWritingAction.FinalInputChanged("s1", first.textEn))
        model.onAction(CompleteWritingAction.FinalInputChanged("s2", second.textEn))
        model.onAction(CompleteWritingAction.SubmitRequested)
        model.onAction(CompleteWritingAction.SubmitRequested)
        model.onAction(CompleteWritingAction.FinalInputChanged("s1", "changed while saving"))
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(first.textEn, model.uiState.value.finalInputs["s1"])
        completion.complete(CompleteWritingSaveResult.Saved)
        advanceUntilIdle()
        val accepted = model.uiState.value.finalResult
        model.onAction(CompleteWritingAction.FinalInputChanged("s1", "changed after saved"))
        model.onAction(CompleteWritingAction.SubmitRequested)
        advanceUntilIdle()
        assertEquals(accepted, model.uiState.value.finalResult)
        assertEquals(first.textEn, model.uiState.value.finalInputs["s1"])
        assertEquals(1, calls)
    }

    @Test
    fun followsAudibleThenSilentForEverySentenceBeforeFinalBatch() {
        val viewModel = createViewModel()
        assertEquals(CompleteWritingPhase.AUDIBLE_SINGLE, viewModel.uiState.value.phase)
        assertTrue(viewModel.uiState.value.audioPlaying)
        submitAndAdvance(viewModel, first.textEn)
        assertEquals(CompleteWritingPhase.SILENT_SINGLE, viewModel.uiState.value.phase)
        assertFalse(viewModel.uiState.value.audioPlaying)
        submitAndAdvance(viewModel, first.textEn)
        assertEquals(CompleteWritingPhase.AUDIBLE_SINGLE, viewModel.uiState.value.phase)
        assertEquals("s2", viewModel.uiState.value.currentFocusSentenceId)
        submitAndAdvance(viewModel, second.textEn)
        submitAndAdvance(viewModel, second.textEn)
        assertEquals(CompleteWritingPhase.FINAL_BATCH, viewModel.uiState.value.phase)
        assertFalse(viewModel.uiState.value.audioPlaying)
    }

    @Test
    fun blankTrainingAndFinalInputNeverCreateResults() {
        val viewModel = createViewModel()
        viewModel.onAction(CompleteWritingAction.SubmitRequested)
        assertTrue(viewModel.uiState.value.emptySubmission)
        moveToFinal(viewModel)
        viewModel.onAction(CompleteWritingAction.SubmitRequested)
        assertTrue(viewModel.uiState.value.emptySubmission)
        assertNull(viewModel.uiState.value.finalResult)
    }

    @Test
    fun silentHelpMarksAttemptSupportedAndResetsAtNextTrainingAttempt() {
        val viewModel = createViewModel()
        submitAndAdvance(viewModel, first.textEn)
        viewModel.onAction(CompleteWritingAction.HelpRequested)
        assertTrue(viewModel.uiState.value.helpUsed)
        viewModel.onAction(CompleteWritingAction.AudioToggled)
        assertTrue(viewModel.uiState.value.audioPlaying)
        submitAndAdvance(viewModel, first.textEn)
        assertFalse(viewModel.uiState.value.helpUsed)
    }

    @Test
    fun finalBatchWithoutHelpCreatesSeparatedValidResults() = runTest(dispatcher) {
        val viewModel = createViewModel()
        moveToFinal(viewModel)
        viewModel.onAction(CompleteWritingAction.FinalInputChanged("s1", first.textEn))
        viewModel.onAction(CompleteWritingAction.FinalInputChanged("s2", "I clearly myself"))
        viewModel.onAction(CompleteWritingAction.SubmitRequested)
        advanceUntilIdle()
        val result = viewModel.uiState.value.finalResult as FinalWritingBatchResult.Valid
        assertTrue(result.sentenceResults.first { it.sentenceId == "s1" }.wholeSentencePassed)
        assertFalse(result.sentenceResults.first { it.sentenceId == "s2" }.wholeSentencePassed)
    }

    @Test
    fun helpInFinalBatchDisqualifiesAllFormalResultsButCanContinue() = runTest(dispatcher) {
        val viewModel = createViewModel()
        moveToFinal(viewModel)
        viewModel.onAction(CompleteWritingAction.HelpRequested)
        viewModel.onAction(CompleteWritingAction.FinalInputChanged("s1", first.textEn))
        viewModel.onAction(CompleteWritingAction.FinalInputChanged("s2", second.textEn))
        viewModel.onAction(CompleteWritingAction.SubmitRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.finalResult is FinalWritingBatchResult.SupportedLearningOnly)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(CompleteWritingAction.ContinueRequested)
        advanceUntilIdle()
        assertEquals(CompleteWritingEffect.OpenSceneResponse, effect.await())
    }

    @Test
    fun saveFailureDoesNotExposeResultOrAllowNavigation() = runTest(dispatcher) {
        val viewModel = createViewModel(
            CompleteWritingRepository { _, _ -> CompleteWritingSaveResult.Failed },
        )
        moveToFinal(viewModel)
        viewModel.onAction(CompleteWritingAction.FinalInputChanged("s1", first.textEn))
        viewModel.onAction(CompleteWritingAction.FinalInputChanged("s2", second.textEn))
        viewModel.onAction(CompleteWritingAction.SubmitRequested)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.saveFailed)
        assertNull(viewModel.uiState.value.finalResult)
        viewModel.onAction(CompleteWritingAction.ContinueRequested)
        assertNull(viewModel.uiState.value.finalResult)
    }

    @Test
    fun retryClearsTrainingResultAndBackReturnsToPatternSpeaking() = runTest(dispatcher) {
        val viewModel = createViewModel()
        viewModel.onAction(CompleteWritingAction.InputChanged("wrong"))
        viewModel.onAction(CompleteWritingAction.SubmitRequested)
        assertEquals(false, viewModel.uiState.value.currentAttemptCorrect)
        viewModel.onAction(CompleteWritingAction.RetryCurrentRequested)
        assertNull(viewModel.uiState.value.currentAttemptCorrect)
        assertEquals("", viewModel.uiState.value.input)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(CompleteWritingAction.BackRequested)
        advanceUntilIdle()
        assertEquals(CompleteWritingEffect.ReturnToPatternSpeaking, effect.await())
    }

    @Test
    fun actionsOutsideTheirAllowedPhaseDoNotMutateOrAdvanceState() {
        val viewModel = createViewModel()
        viewModel.onAction(CompleteWritingAction.FinalAudioRequested("s1"))
        assertFalse(viewModel.uiState.value.helpUsed)
        viewModel.onAction(CompleteWritingAction.HelpRequested)
        assertFalse(viewModel.uiState.value.helpUsed)
        viewModel.onAction(CompleteWritingAction.AudioToggled)
        assertFalse(viewModel.uiState.value.helpUsed)
        viewModel.onAction(CompleteWritingAction.NextRequested)
        assertEquals(CompleteWritingPhase.AUDIBLE_SINGLE, viewModel.uiState.value.phase)
        viewModel.onAction(CompleteWritingAction.FinalInputChanged("s1", "outside final"))
        assertEquals("", viewModel.uiState.value.finalInputs.getValue("s1"))
        moveToFinal(viewModel)
        viewModel.onAction(CompleteWritingAction.InputChanged("outside single"))
        assertEquals("", viewModel.uiState.value.input)
        viewModel.onAction(CompleteWritingAction.FinalInputChanged("unknown", "value"))
        assertFalse("unknown" in viewModel.uiState.value.finalInputs)
        viewModel.onAction(CompleteWritingAction.FinalAudioRequested("unknown"))
        assertFalse(viewModel.uiState.value.helpUsed)
        viewModel.onAction(CompleteWritingAction.FinalAudioRequested("s2"))
        assertTrue(viewModel.uiState.value.helpUsed)
        assertEquals("s2", viewModel.uiState.value.audioSentenceId)
        viewModel.onAction(CompleteWritingAction.ContinueRequested)
        assertNull(viewModel.uiState.value.finalResult)
    }

    private fun submitAndAdvance(viewModel: CompleteWritingViewModel, input: String) {
        viewModel.onAction(CompleteWritingAction.InputChanged(input))
        viewModel.onAction(CompleteWritingAction.SubmitRequested)
        viewModel.onAction(CompleteWritingAction.NextRequested)
    }

    private fun moveToFinal(viewModel: CompleteWritingViewModel) {
        submitAndAdvance(viewModel, first.textEn)
        submitAndAdvance(viewModel, first.textEn)
        submitAndAdvance(viewModel, second.textEn)
        submitAndAdvance(viewModel, second.textEn)
    }

    private fun createViewModel(
        repository: CompleteWritingRepository = CompleteWritingRepository { _, _ ->
            CompleteWritingSaveResult.Saved
        },
    ) = CompleteWritingViewModel(
        chapter = chapter,
        lockedTargets = listOf(
            LockedWritingTarget("p1", "s1", WritingTargetType.PATTERN, listOf("for the first time")),
        ),
        comparator = comparator,
        evaluator = CompleteWritingEvaluator(comparator),
        repository = repository,
    )

    companion object {
        private val first = FixedLearningSentence(
            "s1",
            "I wrote a diary entry for the first time.",
            "我第一次写了一篇日记。",
            true,
        )
        private val second = FixedLearningSentence(
            "s2",
            "I expressed myself clearly.",
            "我清楚地表达了自己。",
            true,
        )
        private val chapter = FixedLearningChapter("c1", 1, listOf(first, second))
    }
}
