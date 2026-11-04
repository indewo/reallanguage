package com.reallanguage.app.feature.recording.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MicrophonePermissionScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun explainsPrivateRecordingAndProvidesSettingsAndHomeActions() {
        val actions = mutableListOf<MicrophonePermissionAction>()
        composeRule.setContent {
            RlTheme {
                MicrophonePermissionScreen(
                    MicrophonePermissionUiState(canRequestDirectly = false),
                    actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("microphone-permission-header").assertIsDisplayed()
        composeRule.onNodeWithTag("microphone-permission-artwork").assertIsDisplayed()
        composeRule.onNodeWithTag("microphone-permission-steps").assertIsDisplayed()
        composeRule.onNodeWithTag("microphone-permission-authorize").performClick()
        composeRule.onNodeWithTag("microphone-permission-home").performClick()

        assertEquals(
            listOf(
                MicrophonePermissionAction.AuthorizeRequested,
                MicrophonePermissionAction.ReturnHomeRequested,
            ),
            actions,
        )
    }
}
