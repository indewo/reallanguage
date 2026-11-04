package com.reallanguage.app.feature.diary.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.reallanguage.app.feature.diary.domain.DiaryDictationRepository
import com.reallanguage.app.feature.diary.domain.DiaryDictationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryInputViewModelTest {
    @Test fun restoresReturnedProcessingTextAsEditableAndSubmittable() {
        val viewModel = DiaryInputViewModel(
            ConfirmedDiaryInputValidator,
            initialText = "需要继续修改的日记。",
        )

        assertEquals("需要继续修改的日记。", viewModel.uiState.value.text)
        assertTrue(viewModel.uiState.value.submitEnabled)
    }

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun emptyInputCannotSubmit() {
        val viewModel = createViewModel()
        viewModel.onAction(DiaryInputAction.TextChanged("   "))
        assertFalse(viewModel.uiState.value.submitEnabled)
    }

    @Test
    fun confirmedTenSentenceLimitIsEnforced() {
        val viewModel = createViewModel()
        viewModel.onAction(
            DiaryInputAction.TextChanged((1..11).joinToString("。") { "第${it}句" }),
        )
        assertEquals(DiaryInputError.TOO_MANY_SENTENCES, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.submitEnabled)
    }

    @Test
    fun confirmedThreeHundredCharacterLimitIsEnforcedWithoutDiscardingText() {
        val viewModel = createViewModel()
        val text = "中".repeat(301)

        viewModel.onAction(DiaryInputAction.TextChanged(text))

        assertEquals(text, viewModel.uiState.value.text)
        assertEquals(DiaryInputError.TOO_LONG, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.submitEnabled)
    }

    @Test
    fun releasedDictationAppendsAtCursorAndRemainsEditable() = runTest(dispatcher) {
        val repository = FakeDiaryDictationRepository(
            listOf(DiaryDictationResult.Recognized("补充内容")),
        )
        val viewModel = DiaryInputViewModel(ConfirmedDiaryInputValidator, "前后", repository)
        viewModel.onAction(DiaryInputAction.TextChanged("前后", selectionStart = 1, selectionEnd = 1))

        viewModel.onAction(DiaryInputAction.DictationHoldStarted)
        viewModel.onAction(DiaryInputAction.DictationReleased)
        advanceUntilIdle()

        assertEquals("前补充内容后", viewModel.uiState.value.text)
        assertEquals(DiaryDictationState.IDLE, viewModel.uiState.value.dictationState)
        assertTrue(viewModel.uiState.value.submitEnabled)
        viewModel.onAction(DiaryInputAction.TextChanged("前修改后"))
        assertEquals("前修改后", viewModel.uiState.value.text)
    }

    @Test
    fun dictationCanAppendMultipleSegmentsWithoutOverwritingManualChanges() = runTest(dispatcher) {
        val repository = FakeDiaryDictationRepository(
            listOf(
                DiaryDictationResult.Recognized("第一段。"),
                DiaryDictationResult.Recognized("第二段。"),
            ),
        )
        val viewModel = DiaryInputViewModel(ConfirmedDiaryInputValidator, dictation = repository)

        viewModel.onAction(DiaryInputAction.DictationHoldStarted)
        viewModel.onAction(DiaryInputAction.DictationReleased)
        advanceUntilIdle()
        viewModel.onAction(DiaryInputAction.TextChanged("修改后的第一段。"))
        viewModel.onAction(DiaryInputAction.DictationHoldStarted)
        viewModel.onAction(DiaryInputAction.DictationReleased)
        advanceUntilIdle()

        assertEquals("修改后的第一段。第二段。", viewModel.uiState.value.text)
        assertEquals(2, repository.startCount)
        assertEquals(2, repository.finishCount)
    }

    @Test
    fun failedDictationPreservesExistingTextAndAllowsAnotherHold() = runTest(dispatcher) {
        val repository = FakeDiaryDictationRepository(
            listOf(DiaryDictationResult.Failed, DiaryDictationResult.Recognized("成功补充")),
        )
        val viewModel = DiaryInputViewModel(ConfirmedDiaryInputValidator, "保留文字", repository)

        viewModel.onAction(DiaryInputAction.DictationHoldStarted)
        viewModel.onAction(DiaryInputAction.DictationReleased)
        advanceUntilIdle()
        assertEquals("保留文字", viewModel.uiState.value.text)
        assertEquals(DiaryDictationState.FAILED, viewModel.uiState.value.dictationState)

        viewModel.onAction(DiaryInputAction.DictationHoldStarted)
        viewModel.onAction(DiaryInputAction.DictationReleased)
        advanceUntilIdle()
        assertEquals("保留文字成功补充", viewModel.uiState.value.text)
    }

    @Test
    fun backWithTextRequiresDiscardConfirmationAndCancelPreservesText() {
        val viewModel = createViewModel()
        viewModel.onAction(DiaryInputAction.TextChanged("今天发生了一件事。"))
        viewModel.onAction(DiaryInputAction.BackRequested)
        assertTrue(viewModel.uiState.value.showDiscardConfirmation)

        viewModel.onAction(DiaryInputAction.ContinueEditingRequested)
        assertFalse(viewModel.uiState.value.showDiscardConfirmation)
        assertEquals("今天发生了一件事。", viewModel.uiState.value.text)
    }

    @Test
    fun confirmingDiscardClearsTextAndReturnsHome() = runTest(dispatcher) {
        val viewModel = createViewModel()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(DiaryInputAction.TextChanged("今天发生了一件事。"))
        viewModel.onAction(DiaryInputAction.BackRequested)
        viewModel.onAction(DiaryInputAction.DiscardConfirmed)
        advanceUntilIdle()

        assertEquals(DiaryInputUiState(), viewModel.uiState.value)
        assertEquals(DiaryInputEffect.OpenHome, effect.await())
    }

    @Test
    fun validSubmissionLocksTheAcceptedTextAndPreventsDuplicates() = runTest(dispatcher) {
        val viewModel = createViewModel()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(DiaryInputAction.TextChanged("今天发生了一件事。"))
        viewModel.onAction(DiaryInputAction.SubmitRequested)
        viewModel.onAction(DiaryInputAction.SubmitRequested)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.submitting)
        assertFalse(viewModel.uiState.value.submitEnabled)
        assertEquals("今天发生了一件事。", viewModel.uiState.value.text)
        assertEquals(DiaryInputEffect.OpenSafetyCheck("今天发生了一件事。"), effect.await())
    }

    @Test
    fun newSessionDoesNotRestoreAnUnsubmittedDraft() {
        val first = createViewModel()
        first.onAction(DiaryInputAction.TextChanged("未提交内容"))
        assertEquals(DiaryInputUiState(), createViewModel().uiState.value)
    }

    private fun createViewModel() = DiaryInputViewModel(
        ConfirmedDiaryInputValidator,
        dictation = FakeDiaryDictationRepository(),
    )

    private class FakeDiaryDictationRepository(
        results: List<DiaryDictationResult> = emptyList(),
    ) : DiaryDictationRepository {
        private val pending = ArrayDeque(results)
        var startCount = 0
        var finishCount = 0

        override fun start(): Boolean {
            startCount += 1
            return true
        }

        override suspend fun finish(commandId: String): DiaryDictationResult {
            finishCount += 1
            return pending.removeFirstOrNull() ?: DiaryDictationResult.Failed
        }

        override fun cancel() = Unit
    }
}
