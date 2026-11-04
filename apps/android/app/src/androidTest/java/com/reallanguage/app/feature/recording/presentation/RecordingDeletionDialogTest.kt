package com.reallanguage.app.feature.recording.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordingDeletionDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersIrreversibleWarningAndDispatchesCancelAndConfirm() {
        val actions = mutableListOf<RecordingDeletionAction>()
        composeRule.setContent {
            RlTheme {
                RecordingDeletionDialog(
                    state = RecordingDeletionUiState("recording-1"),
                    durationSeconds = 84,
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("recording-deletion-summary").assertIsDisplayed()
        composeRule.onNodeWithTag("recording-deletion-warning").assertIsDisplayed()
        composeRule.onNodeWithTag("recording-deletion-cancel").performClick()
        composeRule.onNodeWithTag("recording-deletion-confirm").performClick()

        assertEquals(
            listOf(RecordingDeletionAction.CancelRequested, RecordingDeletionAction.ConfirmRequested),
            actions,
        )
    }
}
