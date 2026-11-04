package com.reallanguage.app.feature.home.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget
import com.reallanguage.app.feature.home.domain.MessageKind
import com.reallanguage.app.feature.home.domain.MessageTargetState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MessagesScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun readFailureKeepsTheMessageAndExposesTheExistingActionForRetry() {
        val actions = mutableListOf<MessagesAction>()
        composeRule.setContent { RlTheme { MessagesScreen(
            MessagesUiState(messages = listOf(message("m1", "隔离复核结果", false)), loading = false, markAllReadFailed = true),
            actions::add,
        ) } }
        composeRule.onNodeWithTag("messages-mark-read-failed").assertIsDisplayed()
        composeRule.onNodeWithText("隔离复核结果").assertIsDisplayed()
        composeRule.onNodeWithTag("messages-mark-all-read").performClick()
        assertEquals(listOf(MessagesAction.MarkAllReadRequested), actions)
    }

    @Test fun pendingReadMutationDisablesItsButtonButDoesNotTrapTheUser() {
        val actions = mutableListOf<MessagesAction>()
        composeRule.setContent { RlTheme { MessagesScreen(
            MessagesUiState(messages = listOf(message("m1", "隔离复核结果", false)), loading = false, markingAllRead = true),
            actions::add,
        ) } }
        composeRule.onNodeWithTag("messages-mark-all-read").assertIsNotEnabled()
        composeRule.onNodeWithTag("messages-return-home").performClick()
        assertEquals(listOf(MessagesAction.ReturnHomeRequested), actions)
    }

    @Test
    fun rendersInboxRowsAndDispatchesMessageMarkAllAndHome() {
        val actions = mutableListOf<MessagesAction>()
        val messages = listOf(
            message("m1", "人工审核已通过", false),
            message("m2", "学习内容已生成", true),
            message("m3", "人工审核未通过", false),
            message("m4", "今日回顾已就绪", true),
        )
        composeRule.setContent {
            RlTheme { MessagesScreen(MessagesUiState(messages = messages, loading = false), actions::add) }
        }

        composeRule.onNodeWithTag("messages-inbox-artwork").assertIsDisplayed()
        composeRule.onNodeWithTag("messages-filter").assertIsDisplayed()
        composeRule.onAllNodesWithTag("message-row", useUnmergedTree = true).assertCountEquals(4)
        composeRule.onNodeWithTag("messages-open-m1").performClick()
        composeRule.onNodeWithTag("messages-mark-all-read").performClick()
        composeRule.onNodeWithTag("messages-return-home").performClick()

        assertEquals(
            listOf(
                MessagesAction.MessageRequested("m1"),
                MessagesAction.MarkAllReadRequested,
                MessagesAction.ReturnHomeRequested,
            ),
            actions,
        )
    }

    private fun message(id: String, summary: String, read: Boolean) = AppMessage(
        id,
        MessageKind.SYSTEM,
        summary,
        "消息内容",
        200,
        read,
        AppMessageTarget("review", id),
        MessageTargetState.AVAILABLE,
    )
}
