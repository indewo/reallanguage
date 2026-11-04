package com.reallanguage.app.feature.recording.presentation

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionFailureViewModelTest {
    @Test fun retryReentersTranscriptionForSameRecording() = runTest {
        val viewModel = TranscriptionFailureViewModel("r1")
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(TranscriptionFailureAction.RetryRequested)
        assertEquals(TranscriptionFailureEffect.OpenTranscription("r1"), effect.await())
    }

    @Test fun returnOpensConfirmationForSameLocalRecording() = runTest {
        val viewModel = TranscriptionFailureViewModel("r1")
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(TranscriptionFailureAction.ReturnRequested)
        assertEquals(TranscriptionFailureEffect.OpenRecordingConfirmation("r1"), effect.await())
    }

    @Test fun stateRetainsRecordingIdentityWithoutDeleteAction() {
        val viewModel = TranscriptionFailureViewModel("r1")
        assertEquals("r1", viewModel.uiState.value.recordingId)
    }
}
