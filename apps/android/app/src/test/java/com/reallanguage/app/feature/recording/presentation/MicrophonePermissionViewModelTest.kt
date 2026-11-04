package com.reallanguage.app.feature.recording.presentation

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophonePermissionViewModelTest {
    @Test fun pageDoesNotRequestPermissionAutomatically() {
        val viewModel = MicrophonePermissionViewModel(canRequestDirectly = true)
        assertFalse(viewModel.uiState.value.requestingPermission)
    }

    @Test fun retryRequestsPermissionWhenAndroidAllowsIt() = runTest {
        val viewModel = MicrophonePermissionViewModel(canRequestDirectly = true)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(MicrophonePermissionAction.AuthorizeRequested)
        assertEquals(MicrophonePermissionEffect.RequestMicrophonePermission, effect.await())
        assertTrue(viewModel.uiState.value.requestingPermission)
    }

    @Test fun retryOpensSystemSettingsWhenAndroidCannotAskAgain() = runTest {
        val viewModel = MicrophonePermissionViewModel(canRequestDirectly = false)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(MicrophonePermissionAction.AuthorizeRequested)
        assertEquals(MicrophonePermissionEffect.OpenAppSettings, effect.await())
        assertFalse(viewModel.uiState.value.requestingPermission)
    }

    @Test fun grantedPermissionStartsRecordingAndDenialStaysOnPage() = runTest {
        val viewModel = MicrophonePermissionViewModel(canRequestDirectly = true)
        viewModel.onAction(MicrophonePermissionAction.PermissionResult(granted = false))
        assertFalse(viewModel.uiState.value.requestingPermission)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(MicrophonePermissionAction.PermissionResult(granted = true))
        assertEquals(MicrophonePermissionEffect.OpenRecording, effect.await())
    }

    @Test fun returnGoesHomeWithoutCreatingRecording() = runTest {
        val viewModel = MicrophonePermissionViewModel(canRequestDirectly = true)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(MicrophonePermissionAction.ReturnHomeRequested)
        assertEquals(MicrophonePermissionEffect.OpenHome, effect.await())
    }
}
