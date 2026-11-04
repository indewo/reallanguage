package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.recording.domain.LocalRecordingSummary
import com.reallanguage.app.feature.recording.domain.RecordingConfirmationRepository
import com.reallanguage.app.feature.recording.domain.RecordingLoadResult
import com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingConfirmationViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun callbackDoesNotUnlockRepeatedClicksBeforePlaybackRequestReturns() = runTest {
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val repository = object : RecordingConfirmationRepository {
            override val playbackState = kotlinx.coroutines.flow.MutableStateFlow<RecordingPlaybackResult>(RecordingPlaybackResult.Stopped)
            override suspend fun load(recordingId: String) = RecordingLoadResult.Ready(summary)
            override suspend fun togglePlayback(recordingId: String): RecordingPlaybackResult {
                requests++
                playbackState.value = RecordingPlaybackResult.Playing
                gate.await()
                return RecordingPlaybackResult.Playing
            }
        }
        val viewModel = RecordingConfirmationViewModel("r1", repository)
        advanceUntilIdle()
        viewModel.onAction(RecordingConfirmationAction.PreviewRequested)
        runCurrent()
        val busyBeforeReturn = viewModel.uiState.value.playbackBusy
        viewModel.onAction(RecordingConfirmationAction.PreviewRequested)
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(busyBeforeReturn)
        assertEquals(1, requests)
    }

    @Test fun rejectedPlaybackIsNotHiddenByAnOlderStoppedEvent() = runTest {
        val repository = object : RecordingConfirmationRepository {
            override val playbackState = kotlinx.coroutines.flow.MutableStateFlow<RecordingPlaybackResult>(RecordingPlaybackResult.Stopped)
            override suspend fun load(recordingId: String) = RecordingLoadResult.Ready(summary)
            override suspend fun togglePlayback(recordingId: String) = RecordingPlaybackResult.Failed
        }
        val viewModel = RecordingConfirmationViewModel("r1", repository)
        advanceUntilIdle()
        viewModel.onAction(RecordingConfirmationAction.PreviewRequested)
        advanceUntilIdle()
        assertEquals(RecordingConfirmationError.PLAYBACK_FAILED, viewModel.uiState.value.error)
    }

    @Test fun naturalPlaybackCompletionClearsThePlayingState() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        repository.playbackState.value = RecordingPlaybackResult.Playing
        viewModel.onAction(RecordingConfirmationAction.PreviewRequested)
        advanceUntilIdle()
        repository.playbackState.value = RecordingPlaybackResult.Stopped
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.playing)
    }

    @Test fun leavingForHomeStopsOriginalAudioBeforeNavigation() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(RecordingConfirmationAction.PreviewRequested)
        advanceUntilIdle()
        viewModel.onAction(RecordingConfirmationAction.ReturnHomeRequested)
        assertEquals(1, repository.stopCount)
        assertFalse(viewModel.uiState.value.playing)
    }

    @Test fun loadsLocalRecordingDuration() = runTest {
        val viewModel = viewModel(FakeRepository())
        advanceUntilIdle()
        assertEquals(summary, viewModel.uiState.value.recording)
    }

    @Test fun previewTogglesLocalPlayback() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(RecordingConfirmationAction.PreviewRequested)
        advanceUntilIdle()
        assertEquals(RecordingPlaybackResult.Playing, repository.playbackResult)
        assertEquals(true, viewModel.uiState.value.playing)
        repository.playbackResult = RecordingPlaybackResult.Stopped
        viewModel.onAction(RecordingConfirmationAction.PreviewRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.playing)
    }

    @Test fun deleteOnlyOpensIndependentConfirmation() = runTest {
        val viewModel = viewModel(FakeRepository())
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(RecordingConfirmationAction.DeleteRequested)
        assertEquals(RecordingConfirmationEffect.OpenDeletionConfirmation("r1"), effect.await())
    }

    @Test fun transcriptionAndReturnPreserveLocalRecording() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val transcription = async { viewModel.effects.first() }
        viewModel.onAction(RecordingConfirmationAction.TranscribeRequested)
        assertEquals(RecordingConfirmationEffect.OpenTranscription("r1"), transcription.await())
        val home = async { viewModel.effects.first() }
        viewModel.onAction(RecordingConfirmationAction.ReturnHomeRequested)
        assertEquals(RecordingConfirmationEffect.OpenHome, home.await())
        assertEquals(0, repository.deleteCount)
    }

    @Test fun ordinaryBackStopsPlaybackAndReturnsToTheActualPreviousNode() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val previous = async { viewModel.effects.first() }

        viewModel.onAction(RecordingConfirmationAction.BackRequested)

        assertEquals(RecordingConfirmationEffect.ReturnToPrevious, previous.await())
        assertEquals(1, repository.stopCount)
        assertEquals(0, repository.deleteCount)
    }

    @Test fun loadAndPlaybackFailuresRemainRetryable() = runTest {
        val repository = FakeRepository(loadResult = RecordingLoadResult.Failed)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        assertEquals(RecordingConfirmationError.LOAD_FAILED, viewModel.uiState.value.error)
        repository.loadResult = RecordingLoadResult.Ready(summary)
        viewModel.onAction(RecordingConfirmationAction.RetryRequested)
        advanceUntilIdle()
        repository.playbackResult = RecordingPlaybackResult.Failed
        viewModel.onAction(RecordingConfirmationAction.PreviewRequested)
        advanceUntilIdle()
        assertEquals(RecordingConfirmationError.PLAYBACK_FAILED, viewModel.uiState.value.error)
    }

    private fun viewModel(repository: FakeRepository) = RecordingConfirmationViewModel("r1", repository)

    private class FakeRepository(
        var loadResult: RecordingLoadResult = RecordingLoadResult.Ready(summary),
        var playbackResult: RecordingPlaybackResult = RecordingPlaybackResult.Playing,
    ) : RecordingConfirmationRepository {
        var deleteCount = 0
        var stopCount = 0
        override val playbackState = kotlinx.coroutines.flow.MutableStateFlow<RecordingPlaybackResult>(RecordingPlaybackResult.Stopped)
        override fun stopPlayback() { stopCount++ }
        override suspend fun load(recordingId: String): RecordingLoadResult = loadResult
        override suspend fun togglePlayback(recordingId: String): RecordingPlaybackResult {
            playbackState.value = playbackResult
            return playbackResult
        }
    }

    companion object { private val summary = LocalRecordingSummary("r1", 42) }
}
