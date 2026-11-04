package com.reallanguage.app.feature.error.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.error.domain.ConnectionResumePoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConnectionErrorOverlayTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersSeparatedRecoverySheetAndDispatchesBothActions() {
        val actions = mutableListOf<ConnectionErrorAction>()
        val state = ConnectionErrorUiState(
            resumePoint = ConnectionResumePoint(
                pageId = "explanation-reading",
                positionToken = "sentence-2",
                temporaryTextInput = "尚未提交的文字",
            ),
        )

        composeRule.setContent { RlTheme { ConnectionErrorOverlay(state, actions::add) } }

        composeRule.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("connection-error-status").assertIsDisplayed()
        composeRule.onNodeWithText("当前操作尚未提交成功。", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("connection-error-return").performClick()
        composeRule.onNodeWithTag("connection-error-reconnect").performClick()

        assertEquals(
            listOf(ConnectionErrorAction.ReturnRequested, ConnectionErrorAction.ReconnectRequested),
            actions,
        )
    }
}
