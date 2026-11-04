package com.reallanguage.app.feature.recording.presentation

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecordingIntroViewModelTest {
    @Test fun openingExplanationNeverRequestsMicrophoneAutomatically() {
        val viewModel = RecordingIntroViewModel()
        assertFalse(viewModel.uiState.value.requestingPermission)
    }

    @Test fun agreementRequestsAndroidMicrophonePermission() = runTest {
        val viewModel = RecordingIntroViewModel()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(RecordingIntroAction.AgreeRequested)
        assertEquals(RecordingIntroEffect.RequestMicrophonePermission, effect.await())
    }

    @Test fun grantedPermissionImmediatelyStartsRecording() = runTest {
        val viewModel = RecordingIntroViewModel()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(RecordingIntroAction.PermissionResult(granted = true))
        assertEquals(RecordingIntroEffect.OpenRecording, effect.await())
    }

    @Test fun deniedPermissionOpensPermissionExplanation() = runTest {
        val viewModel = RecordingIntroViewModel()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(RecordingIntroAction.PermissionResult(granted = false))
        assertEquals(RecordingIntroEffect.OpenPermissionExplanation, effect.await())
    }

    @Test fun returnGoesHomeWithoutRequestingPermission() = runTest {
        val viewModel = RecordingIntroViewModel()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(RecordingIntroAction.ReturnHomeRequested)
        assertEquals(RecordingIntroEffect.OpenHome, effect.await())
        assertFalse(viewModel.uiState.value.requestingPermission)
    }
}
