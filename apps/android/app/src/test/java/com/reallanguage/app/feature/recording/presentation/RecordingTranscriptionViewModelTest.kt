package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.recording.domain.RecordingEndReason
import com.reallanguage.app.feature.recording.domain.RecordingTranscriptionRepository
import com.reallanguage.app.feature.recording.domain.TranscriptionRequest
import com.reallanguage.app.feature.recording.domain.TranscriptionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingTranscriptionViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun leavingOnceCannotBeOverriddenByLateTranscriptionCompletion() = runTest {
        val response = CompletableDeferred<Unit>()
        val repository = object : RecordingTranscriptionRepository {
            override suspend fun transcribe(request: TranscriptionRequest, commandId: String): TranscriptionResult {
                withContext(NonCancellable) { response.await() }
                return TranscriptionResult.Completed("late-transcript")
            }
        }
        val viewModel = RecordingTranscriptionViewModel(request, "command", repository)
        val effects = mutableListOf<RecordingTranscriptionEffect>()
        val collector = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect(effects::add) }
        advanceUntilIdle()
        repeat(2) { viewModel.onAction(RecordingTranscriptionAction.ReturnHomeRequested) }
        response.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(RecordingTranscriptionEffect.OpenHome), effects)
        collector.cancel()
    }

    @Test fun submitsSpeechTranscriptionOnceWithStableContract() = runTest {
        val repository = FakeRepository()
        RecordingTranscriptionViewModel(request, "transcribe-command", repository)
        advanceUntilIdle()
        assertEquals(listOf(request to "transcribe-command"), repository.calls)
    }

    @Test fun completionOpensEditableTranscriptResult() = runTest {
        val viewModel = RecordingTranscriptionViewModel(request, "command", FakeRepository())
        val effect = async { viewModel.effects.first() }
        advanceUntilIdle()
        assertEquals(
            RecordingTranscriptionEffect.OpenResult("r1", "transcript-1"),
            effect.await(),
        )
    }

    @Test fun failureRoutesToDedicatedFailureWithoutDeletingLocalRecording() = runTest {
        val repository = FakeRepository(TranscriptionResult.Failed("operation-1"))
        val viewModel = RecordingTranscriptionViewModel(request, "command", repository)
        val effect = async { viewModel.effects.first() }
        advanceUntilIdle()
        assertEquals(
            RecordingTranscriptionEffect.OpenFailure("r1", "operation-1"),
            effect.await(),
        )
        assertTrue(repository.localRecordingPreserved)
    }

    @Test fun transportExceptionAlsoUsesDedicatedFailureRoute() = runTest {
        val viewModel = RecordingTranscriptionViewModel(request, "command", ThrowingRepository())
        val effect = async { viewModel.effects.first() }
        advanceUntilIdle()
        assertEquals(RecordingTranscriptionEffect.OpenFailure("r1", null), effect.await())
    }

    private class FakeRepository(
        var result: TranscriptionResult = TranscriptionResult.Completed("transcript-1"),
    ) : RecordingTranscriptionRepository {
        val calls = mutableListOf<Pair<TranscriptionRequest, String>>()
        val localRecordingPreserved = true
        override suspend fun transcribe(request: TranscriptionRequest, commandId: String): TranscriptionResult {
            calls += request to commandId
            return result
        }
    }

    private class ThrowingRepository : RecordingTranscriptionRepository {
        override suspend fun transcribe(request: TranscriptionRequest, commandId: String): TranscriptionResult =
            error("network")
    }

    companion object {
        private val request = TranscriptionRequest(
            recordingId = "r1",
            durationSeconds = 42,
            audioFormat = "audio/mp4",
            languageHint = "zh-CN",
            endReason = RecordingEndReason.USER_STOPPED,
        )
    }
}
