package com.reallanguage.app.feature.error.presentation

import com.reallanguage.app.feature.error.domain.FailedStepRetryRepository
import com.reallanguage.app.feature.error.domain.FailedStepRetryResult
import com.reallanguage.app.feature.error.domain.ServiceFailureCategory
import com.reallanguage.app.feature.error.domain.ServiceFailureContext
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
class ServiceFailureViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun failureShowsExactStepAndPreservedDataWithoutInventingBusinessResults() {
        val context = ServiceFailureContext("english-generation", "英语生成", "已确认的中文内容", ServiceFailureCategory.AI_OR_SERVICE)
        val state = ServiceFailureViewModel(context, "retry-1", FakeRepository()).uiState.value
        assertEquals(context, state.context)
        assertFalse(state.fixedContentCreated)
        assertFalse(state.learningStateChanged)
        assertFalse(state.downstreamChargeCreated)
    }

    @Test fun matchingRetryOnlyRestartsFailedStepAndReturnsThere() = runTest(dispatcher) {
        val repository = FakeRepository()
        val context = ServiceFailureContext("safety-check", "安全检查", "原始输入", ServiceFailureCategory.AI_OR_SERVICE)
        val viewModel = ServiceFailureViewModel(context, "retry-1", repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ServiceFailureAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(listOf("safety-check" to "retry-1"), repository.calls)
        assertEquals(ServiceFailureEffect.ReturnToStep("safety-check"), effect.await())
    }

    @Test fun retryFailureStaysInCurrentFlowAndCanRetry() = runTest(dispatcher) {
        val repository = FakeRepository(FailedStepRetryResult.Failed)
        val viewModel = ServiceFailureViewModel(
            ServiceFailureContext("feedback", "学习反馈", "当前步骤进度", ServiceFailureCategory.AI_OR_SERVICE),
            "retry-1",
            repository,
        )
        viewModel.onAction(ServiceFailureAction.RetryRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.retryFailed)
        repository.result = FailedStepRetryResult.Started
        viewModel.onAction(ServiceFailureAction.RetryRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.retryFailed)
        assertEquals(2, repository.calls.size)
    }

    @Test fun returnUsesCurrentPagesMatchingReturnPathInsteadOfHome() = runTest(dispatcher) {
        val viewModel = ServiceFailureViewModel(
            ServiceFailureContext("formation", "章节形成", "重点句选择", ServiceFailureCategory.AI_OR_SERVICE),
            "retry-1",
            FakeRepository(),
        )
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ServiceFailureAction.ReturnRequested)
        advanceUntilIdle()
        assertEquals(ServiceFailureEffect.UseStepReturnPath("formation"), effect.await())
    }

    @Test fun transcriptionFailureRoutesToRc07WithoutGenericRetry() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = ServiceFailureViewModel(
            ServiceFailureContext("transcription", "录音转写", "本机原始录音", ServiceFailureCategory.RECORDING_TRANSCRIPTION),
            "retry-1",
            repository,
        )
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ServiceFailureAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(ServiceFailureEffect.OpenRecordingTranscriptionFailure, effect.await())
        assertTrue(repository.calls.isEmpty())
    }

    private class FakeRepository(var result: FailedStepRetryResult = FailedStepRetryResult.Started) : FailedStepRetryRepository {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun retryStep(stepId: String, commandId: String): FailedStepRetryResult {
            calls += stepId to commandId
            return result
        }
    }
}
