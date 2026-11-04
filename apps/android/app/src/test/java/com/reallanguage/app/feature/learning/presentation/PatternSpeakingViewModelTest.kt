package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModelStore
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedback
import com.reallanguage.app.feature.learning.domain.PatternPracticeFeedbackResult
import com.reallanguage.app.feature.learning.domain.PatternPracticeGenerationResult
import com.reallanguage.app.feature.learning.domain.PatternPracticeSet
import com.reallanguage.app.feature.learning.domain.PatternSpeakingRepository
import com.reallanguage.app.feature.learning.domain.LearningProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class PatternSpeakingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun failedLockedPatternReadIsNotTreatedAsNoSelection() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = PatternSpeakingViewModel(chapter, "s1", null, repository, reloadLockedPatterns = { listOf(pattern) })
        advanceUntilIdle()
        assertTrue(vm.uiState.value.failureMessageVisible)
        assertEquals(0, repository.generationCalls)
        vm.onAction(PatternSpeakingAction.RetryLoadingRequested)
        advanceUntilIdle()
        assertEquals(listOf(pattern), vm.uiState.value.lockedPatterns)
        assertEquals(PatternSpeakingStage.READING, vm.uiState.value.stage)
        assertEquals(1, repository.generationCalls)
    }

    @Test fun restoredExamplesAndReadCompletionDoNotGenerateAgain() = runTest(dispatcher) {
        val store = MemoryProgress()
        val vm = PatternSpeakingViewModel(chapter, "s1", listOf(pattern), FakeRepository(), store)
        advanceUntilIdle()
        vm.onAction(PatternSpeakingAction.StartReadingRequested("e1"))
        vm.onAction(PatternSpeakingAction.ReadingFinished("e1"))
        advanceUntilIdle()
        val repository = FakeRepository()
        val restored = PatternSpeakingViewModel(chapter, "s1", listOf(pattern), repository, store)
        advanceUntilIdle()
        assertEquals(0, repository.generationCalls)
        assertEquals(examples, restored.uiState.value.examples)
        assertEquals(setOf("e1"), restored.uiState.value.completedReadingExampleIds)
    }

    @Test fun failedProgressReadDoesNotGenerateOrOverwriteSavedPractice() = runTest(dispatcher) {
        val store = MemoryProgress().apply { readable = false }
        val repository = FakeRepository()
        val vm = PatternSpeakingViewModel(chapter, "s1", listOf(pattern), repository, store)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.failureMessageVisible)
        assertEquals(0, repository.generationCalls)
        assertEquals(0, store.writes)
    }

    @Test fun removedSiblingRestoreKeepsTheCurrentPaidSetAndPersistsOnlyTheSurvivingPlan() = runTest(dispatcher) {
        val paid = examples.map { it.copy(exampleId = "saved-b-${it.exampleId}", targetResponseEn = "I write by hand.") }
        val store = MemoryProgress().apply { values = encodePatternProgress(PatternSpeakingUiState(chapter, "s1", sourceOrderedPatterns,
            patternIndex = 1, stage = PatternSpeakingStage.READING, examples = paid,
            completedReadingExampleIds = setOf(paid.first().exampleId), processedPatternIds = setOf(pattern.patternId))) }
        var proofReads = 0
        val repository = FakeRepository()
        val vm = PatternSpeakingViewModel(chapter, "s1", listOf(reorderedB), repository, store,
            loadRemovedPatterns = { proofReads++; listOf(pattern) })
        advanceUntilIdle()
        assertFalse(vm.uiState.value.progressFailed)
        assertEquals(paid, vm.uiState.value.examples)
        assertEquals(setOf(paid.first().exampleId), vm.uiState.value.completedReadingExampleIds)
        assertEquals(listOf(PatternPracticeSet(reorderedB.patternId, paid)), repository.restoredPractices)
        assertEquals(0, repository.generationCalls)
        assertEquals(1, proofReads)
        assertEquals("1", store.values?.get("patternCount"))
        assertEquals(reorderedB.patternId, store.values?.get("pattern.0.id"))
        val reopened = FakeRepository()
        val second = PatternSpeakingViewModel(chapter, "s1", listOf(reorderedB), reopened, store,
            loadRemovedPatterns = { listOf(pattern) })
        advanceUntilIdle()
        assertFalse(second.uiState.value.progressFailed)
        assertEquals(paid, second.uiState.value.examples)
        assertEquals(0, reopened.generationCalls)
    }

    @Test fun removedCurrentDoesNotReassignItsPaidSetOrAutoGenerateTheNextPattern() = runTest(dispatcher) {
        val store = MemoryProgress().apply { values = encodePatternProgress(PatternSpeakingUiState(chapter, "s1", sourceOrderedPatterns,
            stage = PatternSpeakingStage.READING, examples = examples, completedReadingExampleIds = setOf("e1"))) }
        val repository = FakeRepository()
        val vm = PatternSpeakingViewModel(chapter, "s1", listOf(reorderedB), repository, store,
            loadRemovedPatterns = { listOf(pattern) })
        advanceUntilIdle()
        assertFalse(vm.uiState.value.progressFailed)
        assertEquals(reorderedB, vm.uiState.value.currentPattern)
        assertTrue(vm.uiState.value.examples.isEmpty())
        assertTrue(vm.uiState.value.failureMessageVisible)
        assertEquals(0, repository.generationCalls)
        assertTrue(repository.restoredPractices.isEmpty())
        vm.onAction(PatternSpeakingAction.RetryLoadingRequested)
        advanceUntilIdle()
        assertEquals(listOf(reorderedB.patternId), repository.generatedPatternIds)
    }

    @Test fun unavailableRemovalAuthorityCannotOverwriteOrGenerateFromOldProgress() = runTest(dispatcher) {
        val saved = encodePatternProgress(PatternSpeakingUiState(chapter, "s1", sourceOrderedPatterns,
            patternIndex = 1, stage = PatternSpeakingStage.READING, examples = examples))
        val store = MemoryProgress().apply { values = saved }
        val repository = FakeRepository()
        var readable = false
        var proofReads = 0
        val vm = PatternSpeakingViewModel(chapter, "s1", listOf(reorderedB), repository, store,
            loadRemovedPatterns = { proofReads++; check(readable); listOf(pattern) })
        advanceUntilIdle()
        assertTrue(vm.uiState.value.progressFailed)
        assertEquals(1, proofReads)
        assertEquals(saved, store.values)
        assertEquals(0, store.writes)
        assertEquals(0, repository.generationCalls)
        readable = true
        vm.onAction(PatternSpeakingAction.RetryProgressRequested)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.progressFailed)
        assertEquals(2, proofReads)
        assertEquals(examples, vm.uiState.value.examples)
        assertEquals(0, repository.generationCalls)
    }

    @Test fun failedSaveCannotAdvanceAndRetryDoesNotRepeatPaidFeedback() = runTest(dispatcher) {
        val store = MemoryProgress()
        val repository = FakeRepository()
        val vm = PatternSpeakingViewModel(chapter, "s1", listOf(pattern), repository, store)
        advanceUntilIdle()
        examples.forEach {
            vm.onAction(PatternSpeakingAction.StartReadingRequested(it.exampleId))
            vm.onAction(PatternSpeakingAction.ReadingFinished(it.exampleId))
            advanceUntilIdle()
        }
        store.writable = false
        vm.onAction(PatternSpeakingAction.StartResponsesRequested)
        advanceUntilIdle()
        assertEquals(PatternSpeakingStage.READING, vm.uiState.value.stage)
        store.writable = true
        vm.onAction(PatternSpeakingAction.StartResponsesRequested)
        advanceUntilIdle()
        assertEquals(PatternSpeakingStage.RESPONDING, vm.uiState.value.stage)
        assertEquals(2, repository.successfulReadingCalls)
    }

    @Test fun rapidReadingCompletionCannotSubmitTwiceOrSwitchActiveExample() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = createViewModel(listOf(pattern), repository)
        advanceUntilIdle()
        vm.onAction(PatternSpeakingAction.StartReadingRequested("e1"))
        vm.onAction(PatternSpeakingAction.StartReadingRequested("e2"))
        assertEquals("e1", vm.uiState.value.readingExampleId)
        vm.onAction(PatternSpeakingAction.ReadingFinished("e1"))
        vm.onAction(PatternSpeakingAction.ReadingFinished("e1"))
        advanceUntilIdle()
        assertEquals(1, repository.successfulReadingCalls)
        assertEquals(setOf("e1"), vm.uiState.value.completedReadingExampleIds)
    }

    @Test fun rapidResponseCompletionCannotSubmitTwiceOrAdvanceWhilePending() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = readyForResponses(repository)
        vm.onAction(PatternSpeakingAction.StartResponseRequested)
        vm.onAction(PatternSpeakingAction.FinishResponseRequested)
        vm.onAction(PatternSpeakingAction.FinishResponseRequested)
        vm.onAction(PatternSpeakingAction.SkipSceneRequested)
        advanceUntilIdle()
        assertEquals(1, repository.responseCalls)
        assertEquals(0, vm.uiState.value.responseIndex)
        assertTrue(vm.uiState.value.currentResponseCompleted)
    }

    @Test fun activePatternCannotBeSkippedAndInitialLoadingCannotBeDuplicated() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = createViewModel(listOf(pattern, pattern.copy(patternId = "p2")), repository)
        vm.onAction(PatternSpeakingAction.RetryLoadingRequested)
        advanceUntilIdle()
        assertEquals(1, repository.generationCalls)
        vm.onAction(PatternSpeakingAction.ContinueWithoutPatternRequested)
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.patternIndex)
        assertEquals(PatternSpeakingStage.READING, vm.uiState.value.stage)
    }

    @Test
    fun noLockedPatternSkipsDirectlyToCompleteWriting() = runTest(dispatcher) {
        val viewModel = createViewModel(emptyList(), FakeRepository())
        assertEquals(PatternSpeakingEffect.OpenCompleteWriting, viewModel.effects.first())
    }

    @Test
    fun everyGeneratedExampleMustBeReadOnceBeforeResponses() = runTest(dispatcher) {
        val viewModel = createViewModel(listOf(pattern), FakeRepository())
        advanceUntilIdle()
        assertEquals(PatternSpeakingStage.READING, viewModel.uiState.value.stage)
        viewModel.onAction(PatternSpeakingAction.StartResponsesRequested)
        assertEquals(PatternSpeakingStage.READING, viewModel.uiState.value.stage)
        examples.forEach { example ->
            viewModel.onAction(PatternSpeakingAction.StartReadingRequested(example.exampleId))
            viewModel.onAction(PatternSpeakingAction.ReadingFinished(example.exampleId))
            advanceUntilIdle()
        }
        assertEquals(2, viewModel.uiState.value.completedReadingExampleIds.size)
        viewModel.onAction(PatternSpeakingAction.StartResponsesRequested)
        assertEquals(PatternSpeakingStage.RESPONDING, viewModel.uiState.value.stage)
    }

    @Test
    fun visibleTargetResponseProducesTrainingFeedbackOnly() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = readyForResponses(repository)
        viewModel.onAction(PatternSpeakingAction.StartResponseRequested)
        viewModel.onAction(PatternSpeakingAction.FinishResponseRequested)
        advanceUntilIdle()
        assertEquals("I used to walk to school", repository.lastTranscript)
        assertTrue(viewModel.uiState.value.currentResponseCompleted)
        assertFalse(viewModel.uiState.value.repeatRecommended)
    }

    @Test
    fun aSceneCanBeSkippedWithoutFailingOrSkippingWholePattern() = runTest(dispatcher) {
        val viewModel = readyForResponses(FakeRepository())
        viewModel.onAction(PatternSpeakingAction.SkipSceneRequested)
        assertEquals(1, viewModel.uiState.value.responseIndex)
        assertEquals(PatternSpeakingStage.RESPONDING, viewModel.uiState.value.stage)
    }

    @Test
    fun finishingAllScenesContinuesToCompleteWriting() = runTest(dispatcher) {
        val viewModel = readyForResponses(FakeRepository())
        viewModel.onAction(PatternSpeakingAction.SkipSceneRequested)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PatternSpeakingAction.SkipSceneRequested)
        advanceUntilIdle()
        assertEquals(PatternSpeakingEffect.OpenCompleteWriting, effect.await())
    }

    @Test
    fun invalidGenerationCannotSilentlySkipALockedPattern() = runTest(dispatcher) {
        val invalid = FakeRepository(
            generation = PatternPracticeGenerationResult.Ready(
                PatternPracticeSet(pattern.patternId, emptyList()),
            ),
        )
        val viewModel = createViewModel(listOf(pattern), invalid)
        advanceUntilIdle()
        assertEquals(PatternSpeakingStage.LOADING, viewModel.uiState.value.stage)
        assertTrue(viewModel.uiState.value.failureMessageVisible)
        viewModel.onAction(PatternSpeakingAction.ContinueWithoutPatternRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.completed)
    }

    @Test fun explicitlyNotNaturalPatternCanContinueWithoutInventedExamples() = runTest(dispatcher) {
        val viewModel = createViewModel(listOf(pattern), FakeRepository(generation = PatternPracticeGenerationResult.NotNaturallyVerifiable))
        advanceUntilIdle()
        assertEquals(PatternSpeakingStage.UNAVAILABLE, viewModel.uiState.value.stage)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PatternSpeakingAction.ContinueWithoutPatternRequested)
        advanceUntilIdle()
        assertEquals(PatternSpeakingEffect.OpenCompleteWriting, effect.await())
    }

    @Test
    fun loadingFailureCanRetryAndBackAndAudioRemainLocal() = runTest(dispatcher) {
        val repository = FakeRepository(
            generation = PatternPracticeGenerationResult.Failed,
        )
        val viewModel = createViewModel(listOf(pattern), repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.failureMessageVisible)
        viewModel.onAction(PatternSpeakingAction.AudioToggled)
        viewModel.onAction(PatternSpeakingAction.PronounceRequested)
        assertTrue(viewModel.uiState.value.audioMuted)
        repository.generation = PatternPracticeGenerationResult.Ready(
            PatternPracticeSet(pattern.patternId, examples),
        )
        viewModel.onAction(PatternSpeakingAction.RetryLoadingRequested)
        advanceUntilIdle()
        assertEquals(PatternSpeakingStage.READING, viewModel.uiState.value.stage)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(PatternSpeakingAction.BackRequested)
        advanceUntilIdle()
        assertEquals(PatternSpeakingEffect.ReturnToFullSentenceSpeaking, effect.await())
    }

    @Test
    fun failedReadingDoesNotCompleteExampleAndDuplicateReadIsIgnored() = runTest(dispatcher) {
        val repository = FakeRepository(
            readingResult = PatternPracticeFeedbackResult.Inconclusive,
        )
        val viewModel = createViewModel(listOf(pattern), repository)
        advanceUntilIdle()
        viewModel.onAction(PatternSpeakingAction.ReadingFinished("missing"))
        viewModel.onAction(PatternSpeakingAction.StartReadingRequested("e1"))
        viewModel.onAction(PatternSpeakingAction.ReadingFinished("e1"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.completedReadingExampleIds.isEmpty())
        assertTrue(viewModel.uiState.value.failureMessageVisible)
        repository.readingResult = PatternPracticeFeedbackResult.Ready(
            PatternPracticeFeedback("清楚。", false),
        )
        viewModel.onAction(PatternSpeakingAction.StartReadingRequested("e1"))
        viewModel.onAction(PatternSpeakingAction.ReadingFinished("e1"))
        advanceUntilIdle()
        viewModel.onAction(PatternSpeakingAction.ReadingFinished("e1"))
        advanceUntilIdle()
        assertEquals(1, repository.successfulReadingCalls)
    }

    @Test
    fun inconclusiveResponsesStayOnSceneUntilRetry() = runTest(dispatcher) {
        val repository = FakeRepository(
            responseResult = PatternPracticeFeedbackResult.Inconclusive,
        )
        val viewModel = readyForResponses(repository)
        viewModel.onAction(PatternSpeakingAction.StartResponseRequested)
        viewModel.onAction(PatternSpeakingAction.FinishResponseRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.failureMessageVisible)
        viewModel.onAction(PatternSpeakingAction.RetryResponseRequested)
        assertFalse(viewModel.uiState.value.failureMessageVisible)
        viewModel.onAction(PatternSpeakingAction.StartResponseRequested)
        viewModel.onAction(PatternSpeakingAction.FinishResponseRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.failureMessageVisible)
        assertFalse(viewModel.uiState.value.currentResponseCompleted)
    }

    @Test
    fun repeatRecommendationAllowsRetryAndMultiplePatternsStayOrdered() = runTest(dispatcher) {
        val secondPattern = pattern.copy(patternId = "p2", structure = "be used to + noun")
        val repository = FakeRepository(
            responseResult = PatternPracticeFeedbackResult.Ready(
                PatternPracticeFeedback("结构正确，关键音需要再读。", true),
            ),
        )
        val viewModel = readyForResponses(repository, listOf(pattern, secondPattern))
        viewModel.onAction(PatternSpeakingAction.StartResponseRequested)
        viewModel.onAction(PatternSpeakingAction.FinishResponseRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.repeatRecommended)
        viewModel.onAction(PatternSpeakingAction.RetryResponseRequested)
        assertFalse(viewModel.uiState.value.currentResponseCompleted)
        viewModel.onAction(PatternSpeakingAction.SkipSceneRequested)
        viewModel.onAction(PatternSpeakingAction.SkipSceneRequested)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.patternIndex)
        assertEquals("p2", viewModel.uiState.value.currentPattern?.patternId)
        assertEquals(PatternSpeakingStage.READING, viewModel.uiState.value.stage)
    }

    @Test fun skippingTheLastSceneWhileRecordingCancelsCaptureAndAllowsTheNextPatternToBeRead() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = readyForResponses(repository, sourceOrderedPatterns)
        val effects = mutableListOf<PatternSpeakingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects.add(it) } }
        vm.onAction(PatternSpeakingAction.SkipSceneRequested)
        advanceUntilIdle()
        assertEquals(examples.lastIndex, vm.uiState.value.responseIndex)
        vm.onAction(PatternSpeakingAction.StartResponseRequested)
        assertTrue(vm.uiState.value.recording)
        val cancelsBeforeSkip = repository.captureCancellationCalls
        val startsBeforeNextPattern = repository.captureStartCalls
        val readingsBeforeNextPattern = repository.successfulReadingCalls

        vm.onAction(PatternSpeakingAction.SkipSceneRequested)
        advanceUntilIdle()

        assertEquals(cancelsBeforeSkip + 1, repository.captureCancellationCalls)
        assertEquals(0, repository.responseCalls)
        assertEquals(reorderedB, vm.uiState.value.currentPattern)
        assertEquals(PatternSpeakingStage.READING, vm.uiState.value.stage)
        assertEquals(setOf(pattern.patternId), vm.uiState.value.processedPatternIds)
        assertFalse(vm.uiState.value.completed)
        assertTrue(effects.isEmpty())
        assertFalse("Cancelling the last scene must not leave the next pattern in a phantom recording", vm.uiState.value.recording)
        assertEquals(null, vm.uiState.value.readingExampleId)
        vm.onAction(PatternSpeakingAction.StartReadingRequested(examples.first().exampleId))
        assertEquals("The next pattern must actually start a fresh capture", startsBeforeNextPattern + 1, repository.captureStartCalls)
        assertTrue(vm.uiState.value.recording)
        assertEquals(examples.first().exampleId, vm.uiState.value.readingExampleId)
        vm.onAction(PatternSpeakingAction.ReadingFinished(examples.first().exampleId))
        advanceUntilIdle()
        assertEquals(readingsBeforeNextPattern + 1, repository.successfulReadingCalls)
        assertEquals(setOf(examples.first().exampleId), vm.uiState.value.completedReadingExampleIds)
        assertEquals(setOf(pattern.patternId), vm.uiState.value.processedPatternIds)
        assertEquals(0, repository.responseCalls)
        assertTrue(effects.isEmpty())
    }

    @Test fun skippingANonFinalSceneWhileRecordingCancelsCaptureWithoutProcessingThePattern() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = readyForResponses(repository, sourceOrderedPatterns)
        vm.onAction(PatternSpeakingAction.StartResponseRequested)
        assertTrue(vm.uiState.value.recording)
        val cancelsBeforeSkip = repository.captureCancellationCalls
        val startsBeforeNextScene = repository.captureStartCalls

        vm.onAction(PatternSpeakingAction.SkipSceneRequested)
        advanceUntilIdle()

        assertEquals(cancelsBeforeSkip + 1, repository.captureCancellationCalls)
        assertEquals(pattern, vm.uiState.value.currentPattern)
        assertEquals(PatternSpeakingStage.RESPONDING, vm.uiState.value.stage)
        assertEquals(1, vm.uiState.value.responseIndex)
        assertFalse(vm.uiState.value.recording)
        assertEquals(emptySet<String>(), vm.uiState.value.processedPatternIds)
        assertFalse(vm.uiState.value.completed)
        assertEquals(0, repository.responseCalls)
        assertEquals(listOf(pattern.patternId), repository.generatedPatternIds)
        vm.onAction(PatternSpeakingAction.StartResponseRequested)
        assertEquals(startsBeforeNextScene + 1, repository.captureStartCalls)
        assertTrue(vm.uiState.value.recording)
        assertEquals(0, repository.responseCalls)
    }

    @Test fun failedSkipCheckpointKeepsAnActuallyCancelledCaptureStoppedAndCanRetrySaving() = runTest(dispatcher) {
        val repository = FakeRepository()
        val store = MemoryProgress()
        val vm = PatternSpeakingViewModel(chapter, "s1", sourceOrderedPatterns, repository, store)
        val effects = mutableListOf<PatternSpeakingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects.add(it) } }
        advanceUntilIdle()
        examples.forEach { example ->
            vm.onAction(PatternSpeakingAction.StartReadingRequested(example.exampleId))
            vm.onAction(PatternSpeakingAction.ReadingFinished(example.exampleId))
            advanceUntilIdle()
        }
        vm.onAction(PatternSpeakingAction.StartResponsesRequested)
        advanceUntilIdle()
        vm.onAction(PatternSpeakingAction.SkipSceneRequested)
        advanceUntilIdle()
        assertEquals(examples.lastIndex, vm.uiState.value.responseIndex)
        vm.onAction(PatternSpeakingAction.StartResponseRequested)
        assertTrue(vm.uiState.value.recording)
        val beforeFailure = requireNotNull(store.values).toMap()
        val writesBeforeFailure = store.writes
        val cancelsBeforeFailure = repository.captureCancellationCalls
        val startsBeforeFailure = repository.captureStartCalls
        store.writable = false

        vm.onAction(PatternSpeakingAction.SkipSceneRequested)
        advanceUntilIdle()

        assertEquals(cancelsBeforeFailure + 1, repository.captureCancellationCalls)
        assertTrue("The actual progress write must reach the failure gate", store.writes > writesBeforeFailure)
        assertEquals(beforeFailure, store.values)
        assertTrue(vm.uiState.value.progressFailed)
        assertFalse(vm.uiState.value.submitting)
        assertEquals(pattern, vm.uiState.value.currentPattern)
        assertEquals(examples.lastIndex, vm.uiState.value.responseIndex)
        assertEquals(emptySet<String>(), vm.uiState.value.processedPatternIds)
        assertFalse(vm.uiState.value.completed)
        assertFalse("A failed checkpoint must not resurrect the already cancelled recording", vm.uiState.value.recording)
        assertEquals(null, vm.uiState.value.readingExampleId)
        assertEquals(0, repository.responseCalls)
        assertEquals(listOf(pattern.patternId), repository.generatedPatternIds)
        assertTrue(effects.isEmpty())

        store.writable = true
        vm.onAction(PatternSpeakingAction.RetryProgressRequested)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.progressFailed)
        assertFalse(vm.uiState.value.recording)
        assertEquals(startsBeforeFailure, repository.captureStartCalls)
        assertEquals(cancelsBeforeFailure + 1, repository.captureCancellationCalls)
        assertEquals(0, repository.responseCalls)
        assertTrue(effects.isEmpty())
        vm.onAction(PatternSpeakingAction.SkipSceneRequested)
        advanceUntilIdle()
        assertEquals(reorderedB, vm.uiState.value.currentPattern)
        assertEquals(PatternSpeakingStage.READING, vm.uiState.value.stage)
        assertFalse(vm.uiState.value.recording)
        assertEquals(setOf(pattern.patternId), processedIds(requireNotNull(store.values)))
        assertEquals(0, repository.responseCalls)
        assertTrue(effects.isEmpty())
    }

    @Test fun legacyReorderedCurrentBRetainsPaidPracticeThenProcessesAAndPersistsItsRealCompletedIds() = runTest(dispatcher) {
        val saved = legacyReorderedProgress(currentIndex = 0)
        val originalSaved = saved.toMap()
        val store = MemoryProgress().apply { values = saved }
        val repository = FakeRepository()
        val vm = PatternSpeakingViewModel(chapter, "s1", sourceOrderedPatterns, repository, store)
        val earlyEffects = mutableListOf<PatternSpeakingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { earlyEffects.add(it) } }
        advanceUntilIdle()

        assertPaidCurrentPractice(vm, reorderedB, saved)
        assertEquals(0, repository.generationCalls)
        assertEquals(listOf(reorderedB.patternId), repository.restoredPractices.map { it.patternId })
        assertEquals(emptySet<String>(), processedIds(requireNotNull(store.values)))
        vm.onAction(PatternSpeakingAction.NextSceneRequested)
        advanceUntilIdle()

        assertEquals(pattern, vm.uiState.value.currentPattern)
        assertFalse(vm.uiState.value.completed)
        assertEquals(PatternSpeakingStage.READING, vm.uiState.value.stage)
        assertEquals(listOf(pattern.patternId), repository.generatedPatternIds)
        assertTrue("B at the new last index is not permission to omit unprocessed A", earlyEffects.isEmpty())
        assertEquals(setOf(reorderedB.patternId), processedIds(requireNotNull(store.values)))
        vm.onAction(PatternSpeakingAction.StartReadingRequested("e1"))
        vm.onAction(PatternSpeakingAction.ReadingFinished("e1"))
        advanceUntilIdle()

        // The next instance must use explicit completed IDs, not infer a prefix from A's new
        // index zero. The already-generated A examples and its first reading remain paid work.
        val restoredRepository = FakeRepository()
        val restored = PatternSpeakingViewModel(chapter, "s1", sourceOrderedPatterns, restoredRepository, store)
        val effects = mutableListOf<PatternSpeakingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.effects.collect { effects.add(it) } }
        advanceUntilIdle()
        assertFalse(restored.uiState.value.progressFailed)
        assertEquals(pattern, restored.uiState.value.currentPattern)
        assertEquals(examples, restored.uiState.value.examples)
        assertEquals(setOf("e1"), restored.uiState.value.completedReadingExampleIds)
        assertEquals(0, restoredRepository.generationCalls)
        restored.onAction(PatternSpeakingAction.StartReadingRequested("e2"))
        restored.onAction(PatternSpeakingAction.ReadingFinished("e2"))
        advanceUntilIdle()
        restored.onAction(PatternSpeakingAction.StartResponsesRequested)
        advanceUntilIdle()
        repeat(examples.size) {
            restored.onAction(PatternSpeakingAction.SkipSceneRequested)
            advanceUntilIdle()
        }

        assertTrue(restored.uiState.value.completed)
        assertEquals(listOf(PatternSpeakingEffect.OpenCompleteWriting), effects)
        assertEquals(sourceOrderedPatterns.map { it.patternId }.toSet(), processedIds(requireNotNull(store.values)))
        assertEquals(0, restoredRepository.generationCalls)
        assertEquals(0, repository.responseCalls + restoredRepository.responseCalls)
        assertEquals("The old accepted map is never modified in place", originalSaved, saved)
        assertEquals(listOf(pattern, reorderedB), sourceOrderedPatterns)
    }

    @Test fun legacyReorderedCurrentADoesNotRepeatTheAlreadyProcessedB() = runTest(dispatcher) {
        val saved = legacyReorderedProgress(currentIndex = 1)
        val store = MemoryProgress().apply { values = saved }
        val repository = FakeRepository()
        val vm = PatternSpeakingViewModel(chapter, "s1", sourceOrderedPatterns, repository, store)
        val effects = mutableListOf<PatternSpeakingEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects.add(it) } }
        advanceUntilIdle()

        assertPaidCurrentPractice(vm, pattern, saved)
        assertEquals(setOf(reorderedB.patternId), processedIds(requireNotNull(store.values)))
        assertEquals(0, repository.generationCalls)
        vm.onAction(PatternSpeakingAction.NextSceneRequested)
        advanceUntilIdle()
        vm.onAction(PatternSpeakingAction.NextSceneRequested)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.completed)
        assertEquals(listOf(PatternSpeakingEffect.OpenCompleteWriting), effects)
        assertEquals(0, repository.generationCalls)
        assertEquals(0, repository.responseCalls)
        assertEquals(sourceOrderedPatterns.map { it.patternId }.toSet(), processedIds(requireNotNull(store.values)))
        assertEquals(legacyReorderedProgress(currentIndex = 1), saved)
    }

    @Test fun legacyCompletedReorderedPlanRemainsCompleteAndItsNewSavedVersionReopensWithoutGeneration() = runTest(dispatcher) {
        val saved = legacyReorderedProgress(currentIndex = 1, completed = true)
        val store = MemoryProgress().apply { values = saved }
        repeat(2) { entry ->
            val repository = FakeRepository()
            val vm = PatternSpeakingViewModel(chapter, "s1", sourceOrderedPatterns, repository, store)
            val effects = mutableListOf<PatternSpeakingEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects.add(it) } }
            advanceUntilIdle()

            assertFalse("Completed entry $entry must not become an unavailable paid practice", vm.uiState.value.progressFailed)
            assertTrue(vm.uiState.value.completed)
            assertEquals(pattern, vm.uiState.value.currentPattern)
            assertEquals(listOf(PatternSpeakingEffect.OpenCompleteWriting), effects)
            assertEquals(0, repository.generationCalls)
            assertEquals(0, repository.responseCalls)
            assertEquals(sourceOrderedPatterns.map { it.patternId }.toSet(), processedIds(requireNotNull(store.values)))
        }
        assertEquals(legacyReorderedProgress(currentIndex = 1, completed = true), saved)
    }

    @Test fun reorderedLegacyProgressRejectsMissingDuplicateOrChangedTargetIdentitiesWithoutOverwritingOrGenerating() = runTest(dispatcher) {
        val original = legacyReorderedProgress(currentIndex = 1)
        val damaged = listOf(
            original + ("pattern.0.id" to pattern.patternId),
            original - "pattern.0.id",
            original + ("pattern.0.id" to "unknown-target"),
            original + ("pattern.0.source" to "other-sentence"),
            original + ("pattern.0.structure" to "different + structure"),
            original + ("pattern.0.explanation" to "另一个未接受的含义"),
            original + ("patternCount" to "1"),
            original + ("patternIndex" to "2"),
            legacyReorderedProgress(currentIndex = 0, completed = true),
        )
        damaged.forEachIndexed { index, saved ->
            val store = MemoryProgress().apply { values = saved }
            val repository = FakeRepository()
            val vm = PatternSpeakingViewModel(chapter, "s1", sourceOrderedPatterns, repository, store)
            advanceUntilIdle()
            vm.onAction(PatternSpeakingAction.NextSceneRequested)
            advanceUntilIdle()

            assertTrue("Damaged identity case $index", vm.uiState.value.progressFailed)
            assertFalse(vm.uiState.value.completed)
            assertEquals(0, repository.generationCalls)
            assertTrue(repository.restoredPractices.isEmpty())
            assertEquals(0, store.writes)
            assertEquals(saved, store.values)
        }
    }

    @Test fun explicitProcessedIdsCannotInventCompletionOrFallBackToALegacyPrefixWhenDamaged() = runTest(dispatcher) {
        val validV2 = legacyReorderedProgress(currentIndex = 1) + mapOf(
            "version" to "2", "processedPatternCount" to "1", "processedPattern.0.id" to reorderedB.patternId,
        )
        val damaged = listOf(
            validV2 - "processedPatternCount",
            validV2 - "processedPattern.0.id",
            validV2 + ("processedPattern.0.id" to "unknown-target"),
            validV2 + ("processedPattern.0.id" to pattern.patternId),
            validV2 + mapOf("processedPatternCount" to "2", "processedPattern.1.id" to reorderedB.patternId),
            validV2 + ("completed" to "true"),
            validV2 + ("version" to "999"),
        )
        damaged.forEachIndexed { index, saved ->
            val store = MemoryProgress().apply { values = saved }
            val repository = FakeRepository()
            val vm = PatternSpeakingViewModel(chapter, "s1", sourceOrderedPatterns, repository, store)
            advanceUntilIdle()

            assertTrue("Damaged explicit completion case $index", vm.uiState.value.progressFailed)
            assertFalse(vm.uiState.value.completed)
            assertEquals(0, repository.generationCalls)
            assertTrue(repository.restoredPractices.isEmpty())
            assertEquals(0, store.writes)
            assertEquals(saved, store.values)
        }
    }

    @Test fun queuedAutoSaveCannotRevertACommittedCompletionEvenBeforeItEventuallyCatchesUp() = runTest(dispatcher) {
        assertQueuedAutoSaveCannotRevertTransition(finalScene = true)
    }

    @Test fun queuedAutoSaveCannotRevertACommittedOrdinarySceneAdvance() = runTest(dispatcher) {
        assertQueuedAutoSaveCannotRevertTransition(finalScene = false)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertQueuedAutoSaveCannotRevertTransition(finalScene: Boolean) {
        // Main.immediate can resume a mutex waiter inside unlock, before the saving coroutine's
        // caller continues. Use that real scheduling possibility, with two explicit I/O gates.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val initial = encodePatternProgress(restorePatternProgress(
            PatternSpeakingUiState(chapter, "s1", sourceOrderedPatterns),
            legacyReorderedProgress(currentIndex = 1),
        )) + ("responseIndex" to if (finalScene) "1" else "0")
        val store = SuspendedTransitionProgress(initial)
        val repository = FakeRepository()
        val models = ViewModelStore()
        try {
            val vm = PatternSpeakingViewModel(chapter, "s1", sourceOrderedPatterns, repository, store)
                .also { models.put("queued-save", it) }
            val effects = mutableListOf<PatternSpeakingEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects.add(it) } }
            advanceUntilIdle()
            assertFalse(vm.uiState.value.progressFailed)
            assertEquals(0, store.writeAttempts)

            vm.onAction(PatternSpeakingAction.TranscriptChanged("first locally accepted edit"))
            assertTrue("The first real auto-save must be suspended while owning the save mutex", store.firstWriteStarted.isCompleted)
            vm.onAction(PatternSpeakingAction.TranscriptChanged("second locally accepted edit"))
            vm.onAction(PatternSpeakingAction.NextSceneRequested)
            assertTrue(vm.uiState.value.submitting)
            store.releaseFirstWrite.complete(Unit)
            advanceUntilIdle()
            assertTrue("The explicit transition must acquire the mutex before the queued latest auto-save", store.transitionWriteStarted.isCompleted)
            val transition = store.transitionWriteStarted.await()
            assertEquals(if (finalScene) "true" else "false", transition["completed"])
            assertEquals("1", transition["responseIndex"])
            assertEquals(if (finalScene) setOf(pattern.patternId, reorderedB.patternId) else setOf(reorderedB.patternId), processedIds(transition))

            store.releaseTransitionWrite.complete(Unit)
            advanceUntilIdle()
            val committedTransitionIndex = store.committed.indexOf(transition)
            assertTrue("The transition must actually have been saved, not ignored to suppress the race", committedTransitionIndex >= 0)
            val laterWrites = store.committed.drop(committedTransitionIndex + 1)
            assertTrue("No later auto-save may roll back an acknowledged transition, even transiently: $laterWrites",
                laterWrites.all { saved ->
                    if (finalScene) saved["completed"] == "true" && processedIds(saved) == setOf(pattern.patternId, reorderedB.patternId)
                    else saved["responseIndex"] == "1" && processedIds(saved) == setOf(reorderedB.patternId)
                })
            assertEquals(if (finalScene) "true" else "false", store.values["completed"])
            assertEquals("1", store.values["responseIndex"])
            assertEquals(if (finalScene) listOf(PatternSpeakingEffect.OpenCompleteWriting) else emptyList<PatternSpeakingEffect>(), effects)
            assertEquals(0, repository.generationCalls)
            assertEquals(0, repository.responseCalls)
        } finally {
            store.releaseFirstWrite.complete(Unit)
            store.releaseTransitionWrite.complete(Unit)
            models.clear()
        }
    }

    private class SuspendedTransitionProgress(initial: Map<String, String>) : LearningProgressStore {
        var values = initial.toMap()
        var writeAttempts = 0
        val committed = mutableListOf<Map<String, String>>()
        val firstWriteStarted = CompletableDeferred<Unit>()
        val transitionWriteStarted = CompletableDeferred<Map<String, String>>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val releaseTransitionWrite = CompletableDeferred<Unit>()
        override suspend fun read(): Map<String, String> = values.toMap()
        override suspend fun write(values: Map<String, String>): Boolean {
            val snapshot = values.toMap()
            when (++writeAttempts) {
                1 -> { firstWriteStarted.complete(Unit); releaseFirstWrite.await() }
                2 -> { transitionWriteStarted.complete(snapshot); releaseTransitionWrite.await() }
            }
            this.values = snapshot
            committed.add(snapshot)
            return true
        }
    }

    /** An exact pre-migration v1 payload: do not use the production encoder to fabricate history. */
    private fun legacyReorderedProgress(currentIndex: Int, completed: Boolean = false): Map<String, String> = buildMap {
        val oldOrder = listOf(reorderedB, pattern)
        val current = oldOrder[currentIndex]
        put("version", "1")
        put("patternCount", "2")
        oldOrder.forEachIndexed { index, target ->
            put("pattern.$index.id", target.patternId)
            put("pattern.$index.source", target.sourceSentenceId)
            put("pattern.$index.structure", target.structure)
            put("pattern.$index.explanation", target.explanationZh)
        }
        put("patternIndex", currentIndex.toString())
        put("stage", "RESPONDING")
        put("exampleCount", examples.size.toString())
        examples.forEachIndexed { index, example ->
            put("example.$index.id", "${current.patternId}-accepted-${example.exampleId}")
            put("example.$index.scene", example.sceneZh)
            put("example.$index.prompt", example.aiPromptEn)
            put("example.$index.en", example.targetResponseEn)
            put("example.$index.zh", example.targetResponseZh)
            put("example.$index.read", "true")
        }
        put("responseIndex", examples.lastIndex.toString())
        put("transcript", "已确认的回应文本 ${current.patternId}")
        put("feedback", "已保存的客观反馈 ${current.patternId}")
        put("repeat", "true")
        put("responseCompleted", "true")
        put("muted", "true")
        put("completed", completed.toString())
    }

    private fun assertPaidCurrentPractice(vm: PatternSpeakingViewModel, target: LockedSpeakingPattern, saved: Map<String, String>) {
        val state = vm.uiState.value
        assertFalse(state.progressFailed)
        assertEquals(target, state.currentPattern)
        assertEquals(sourceOrderedPatterns.indexOf(target), state.patternIndex)
        assertEquals(sourceOrderedPatterns, state.lockedPatterns)
        assertEquals(PatternSpeakingStage.RESPONDING, state.stage)
        assertEquals(examples.map { it.copy(exampleId = "${target.patternId}-accepted-${it.exampleId}") }, state.examples)
        assertEquals(state.examples.map { it.exampleId }.toSet(), state.completedReadingExampleIds)
        assertEquals(examples.lastIndex, state.responseIndex)
        assertEquals(saved["transcript"], state.transcript)
        assertEquals(saved["feedback"], state.feedbackZh)
        assertTrue(state.repeatRecommended)
        assertTrue(state.currentResponseCompleted)
        assertTrue(state.audioMuted)
        assertFalse(state.recording)
        assertFalse(state.submitting)
        assertFalse(state.completed)
    }

    private fun processedIds(saved: Map<String, String>): Set<String> {
        assertEquals("New progress must explicitly represent processed target IDs", "2", saved["version"])
        val count = saved.getValue("processedPatternCount").toInt()
        val ids = List(count) { saved.getValue("processedPattern.$it.id") }
        assertEquals("A processed target must not be counted twice", ids.size, ids.toSet().size)
        return ids.toSet()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.readyForResponses(
        repository: FakeRepository,
        patterns: List<LockedSpeakingPattern> = listOf(pattern),
    ): PatternSpeakingViewModel {
        val viewModel = createViewModel(patterns, repository)
        advanceUntilIdle()
        examples.forEach {
            viewModel.onAction(PatternSpeakingAction.StartReadingRequested(it.exampleId))
            viewModel.onAction(PatternSpeakingAction.ReadingFinished(it.exampleId))
            advanceUntilIdle()
        }
        viewModel.onAction(PatternSpeakingAction.StartResponsesRequested)
        return viewModel
    }

    private fun createViewModel(
        patterns: List<LockedSpeakingPattern>,
        repository: FakeRepository,
    ) = PatternSpeakingViewModel(chapter, "s1", patterns, repository)

    private class FakeRepository(
        var generation: PatternPracticeGenerationResult =
            PatternPracticeGenerationResult.Ready(PatternPracticeSet(pattern.patternId, examples)),
        var readingResult: PatternPracticeFeedbackResult =
            PatternPracticeFeedbackResult.Ready(
                PatternPracticeFeedback("内容和发音清楚。", false),
            ),
        var responseResult: PatternPracticeFeedbackResult =
            PatternPracticeFeedbackResult.Ready(
                PatternPracticeFeedback("目标结构、内容和发音清楚。", false),
            ),
    ) : PatternSpeakingRepository {
        var lastTranscript: String? = null
        var successfulReadingCalls = 0
        var responseCalls = 0
        var generationCalls = 0
        var captureStartCalls = 0
        var captureCancellationCalls = 0
        val generatedPatternIds = mutableListOf<String>()
        val restoredPractices = mutableListOf<PatternPracticeSet>()

        override fun startCapture(): Boolean { captureStartCalls++; return true }

        override fun cancelCapture() { captureCancellationCalls++ }

        override fun restorePractice(pattern: LockedSpeakingPattern, practiceSet: PatternPracticeSet) {
            check(pattern.patternId == practiceSet.patternId)
            restoredPractices.add(practiceSet)
        }

        override suspend fun generate(
            pattern: LockedSpeakingPattern,
        ): PatternPracticeGenerationResult { generationCalls++; generatedPatternIds.add(pattern.patternId); return when (val current = generation) {
            is PatternPracticeGenerationResult.Ready -> current.copy(
                practiceSet = current.practiceSet.copy(patternId = pattern.patternId),
            )
            else -> current
        }
        }

        override suspend fun readingFeedback(
            patternId: String,
            exampleId: String,
        ): PatternPracticeFeedbackResult {
            if (readingResult is PatternPracticeFeedbackResult.Ready) {
                successfulReadingCalls += 1
            }
            return readingResult
        }

        override suspend fun responseFeedback(
            patternId: String,
            exampleId: String,
        ): PatternPracticeFeedbackResult {
            responseCalls++
            lastTranscript = "I used to walk to school"
            return responseResult
        }
    }

    companion object {
        private val chapter = FixedLearningChapter(
            "c1",
            1,
            listOf(
                FixedLearningSentence(
                    "s1",
                    "I used to write by hand.",
                    "我过去常常手写。",
                    true,
                ),
            ),
        )
        private val pattern = LockedSpeakingPattern(
            "p1",
            "s1",
            "used to + 动词原形",
            "表示过去经常发生、现在已经改变的习惯。",
        )
        private val reorderedB = pattern.copy(patternId = "p2", structure = "by + noun", explanationZh = "说明完成动作的方式。")
        private val sourceOrderedPatterns = listOf(pattern, reorderedB)
        private val examples = listOf(
            PatternPracticeExample(
                "e1",
                "谈过去的上学方式",
                "How did you get to school before?",
                "I used to walk to school.",
                "我过去常常走路上学。",
            ),
            PatternPracticeExample(
                "e2",
                "谈过去的周末习惯",
                "What did you do on weekends?",
                "I used to visit my grandparents.",
                "我过去周末常去看望祖父母。",
            ),
        )
    }

    private class MemoryProgress : LearningProgressStore {
        var values: Map<String, String>? = null
        var readable = true
        var writable = true
        var writes = 0
        override suspend fun read(): Map<String, String>? { check(readable); return values }
        override suspend fun write(values: Map<String, String>): Boolean {
            writes++; if (!writable) return false
            this.values = values.toMap(); return true
        }
    }
}
