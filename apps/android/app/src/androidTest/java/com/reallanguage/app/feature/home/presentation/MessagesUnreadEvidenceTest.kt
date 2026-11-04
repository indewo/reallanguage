package com.reallanguage.app.feature.home.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget
import com.reallanguage.app.feature.home.domain.MessageKind
import com.reallanguage.app.feature.home.domain.MessageTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MessagesUnreadEvidenceTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun firstLoadingCannotClaimThatTheInboxHasZeroUnreadMessages() {
        composeRule.setContent { RlTheme { MessagesScreen(MessagesUiState(loading = true)) {} } }
        composeRule.onNodeWithText("0 条新消息", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNode(hasText("正在读取消息…") and hasAnyAncestor(hasTestTag("messages-inbox-artwork")),
            useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun failedFirstReadExposesAnUnconfirmedCountAndARetryWithoutInventingZero() {
        val actions = mutableListOf<MessagesAction>()
        composeRule.setContent { RlTheme { MessagesScreen(MessagesUiState(loading = false, loadFailed = true), actions::add) } }
        composeRule.waitForIdle()
        captureMessagesVisualEvidence("57-first-read-failed-real-window")
        composeRule.onNodeWithText("0 条新消息", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("消息数量待确认", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed().performClick()
        assertEquals(listOf(MessagesAction.RetryRequested), actions)
    }

    @Test fun failedRefreshKeepsThePreviousMessagesAndTheirCountButMarksItUnconfirmed() {
        val actions = mutableListOf<MessagesAction>()
        val messages = (1..2).map { id -> AppMessage(
            "message-$id", MessageKind.SYSTEM, "已读取的第 $id 条消息", null, id.toLong(), false,
            AppMessageTarget("review", "chapter-$id"), MessageTargetState.AVAILABLE,
        ) }
        composeRule.setContent { RlTheme { MessagesScreen(MessagesUiState(messages = messages, loading = false, loadFailed = true), actions::add) } }
        composeRule.waitForIdle()
        captureMessagesVisualEvidence("57-refresh-failed-retained-real-window")
        composeRule.onNodeWithText("2 条新消息", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("消息数量待确认", useUnmergedTree = true).assertIsDisplayed()
        // This real host can expose system bars and enter the compact layout.
        // Retention requires both complete rows to remain reachable in order,
        // not squeezing two cards into every possible first viewport.
        val rows = messages.map { composeRule.onNodeWithText(it.summaryZh, useUnmergedTree = true).fetchSemanticsNode() }
        assertTrue("Retained rows preserve their original order", rows[0].positionInRoot.y < rows[1].positionInRoot.y)
        messages.forEach { composeRule.onNodeWithText(it.summaryZh, useUnmergedTree = true).performScrollTo().assertIsDisplayed() }
        captureMessagesVisualEvidence("57-refresh-failed-retained-bottom-real-window")
        composeRule.onNodeWithText("消息数量待确认", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed().performClick()
        assertEquals(listOf(MessagesAction.RetryRequested), actions)
    }

    @Test fun successfullyReadEmptyInboxStillReportsRealZero() {
        composeRule.setContent { RlTheme { MessagesScreen(MessagesUiState(loading = false)) {} } }
        composeRule.onNodeWithText("0 条新消息", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("当前没有消息。").assertIsDisplayed()
        composeRule.onNodeWithText("消息数量待确认", useUnmergedTree = true).assertDoesNotExist()
    }
}
