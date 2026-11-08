package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.recording.domain.ActiveRecording
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeRepository
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveRecordingViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun newRecordingIgnoresPreviousCompletionAndDoesNotNavigateTwice() = runTest {
        val repository = FakeRepository()
        repository.state.value = RecordingRuntimeState.Completed("previous", 20)
        val viewModel = ActiveRecordingViewModel(repository)
        val effects = mutableListOf<ActiveRecordingEffect>()
        val collector = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect(effects::add) }
        advanceUntilIdle()
        assertTrue("Starting a new recording must not reopen the previous file", effects.isEmpty())
        repository.state.value = RecordingRuntimeState.Recording(ActiveRecording("current", 1))
        advanceUntilIdle()
        repository.state.value = RecordingRuntimeState.Completed("current", 2)
        advanceUntilIdle()
        repository.state.value = RecordingRuntimeState.Starting
        advanceUntilIdle()
        repository.state.value = RecordingRuntimeState.Completed("current", 2)
        advanceUntilIdle()
        assertEquals(listOf(ActiveRecordingEffect.OpenCompletion("current")), effects)
        collector.cancel()
    }

    @Test fun startsForegroundRecordingFromVisibleUserAction() = runTest {
        val repository = FakeRepository()
        ActiveRecordingViewModel(repository)
        advanceUntilIdle()
        assertEquals(1, repository.startCount)
    }

    @Test fun reflectsServiceElapsedTimeWithoutAddingProductChrome() = runTest {
        val repository = FakeRepository()
        val viewModel = ActiveRecordingViewModel(repository)
        repository.state.value = RecordingRuntimeState.Recording(ActiveRecording("r1", 37))
        advanceUntilIdle()
        assertEquals(37, viewModel.uiState.value.elapsedSeconds)
        assertTrue(viewModel.uiState.value.recording)
    }

    @Test fun manualStopUsesCurrentRecordingAndOpensCompletion() = runTest {
        val repository = FakeRepository()
        val viewModel = ActiveRecordingViewModel(repository)
        repository.state.value = RecordingRuntimeState.Recording(ActiveRecording("r1", 12))
        advanceUntilIdle()
        viewModel.onAction(ActiveRecordingAction.StopRequested)
        advanceUntilIdle()
        assertEquals(listOf("r1"), repository.stoppedIds)
        val effect = async { viewModel.effects.first() }
        repository.state.value = RecordingRuntimeState.Completed("r1", 12)
        assertEquals(ActiveRecordingEffect.OpenCompletion("r1"), effect.await())
    }

    @Test fun serviceAutoStopsAtOneHourAndOpensCompletion() = runTest {
        val repository = FakeRepository()
        val viewModel = ActiveRecordingViewModel(repository)
        repository.state.value = RecordingRuntimeState.Recording(ActiveRecording("r1", 3600))
        advanceUntilIdle()
        assertEquals(listOf("r1"), repository.stoppedIds)
    }

    @Test fun failureKeepsCompactControlRetryable() = runTest {
        val repository = FakeRepository()
        val viewModel = ActiveRecordingViewModel(repository)
        repository.state.value = RecordingRuntimeState.Failed
        advanceUntilIdle()
        assertEquals(ActiveRecordingError.RECORDING_FAILED, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.recording)
        viewModel.onAction(ActiveRecordingAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(2, repository.startCount)
    }

    private class FakeRepository : RecordingRuntimeRepository {
        override val state = MutableStateFlow<RecordingRuntimeState>(RecordingRuntimeState.Starting)
        var startCount = 0
        val stoppedIds = mutableListOf<String>()
        override suspend fun setPaused(recordingId: String, paused: Boolean) {
            val active = (state.value as RecordingRuntimeState.Recording).active
            state.value = RecordingRuntimeState.Recording(active.copy(paused = paused))
        }
        override suspend fun start() { startCount++ }
        override suspend fun stop(recordingId: String) { stoppedIds += recordingId }
    }
}
