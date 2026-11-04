package com.reallanguage.app.feature.home.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget
import com.reallanguage.app.feature.home.domain.MessageKind
import com.reallanguage.app.feature.home.domain.MessageTargetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual Screen at 640×360dp/200%, retaining the device's real safe insets. */
class MessagesContentFitTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun doubleFontLongMessagesKeepEveryCharacterAndTheLastRealEntryCanBeOpened() {
        val actions = mutableListOf<MessagesAction>()
        val messages = (1..6).map { index -> message(index) }
        render(MessagesUiState(messages = messages, loading = false), actions::add)
        composeRule.waitForIdle()
        captureMessagesVisualEvidence("57-short-font2-long-top")
        messages.forEach { message ->
            assertWholeText(message.summaryZh)
            assertWholeText(message.relatedContentLabelZh!!)
        }
        composeRule.onNodeWithTag("messages-open-message-6").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        captureMessagesVisualEvidence("57-short-font2-long-bottom")
        assertEquals(listOf(MessagesAction.MessageRequested("message-6")), actions)
        composeRule.onNodeWithTag("messages-return-home").assertIsDisplayed()
        composeRule.onNodeWithTag("messages-mark-all-read").assertIsDisplayed()
    }

    @Test fun doubleFontFooterLabelsAndTheRealFailureRemainReadableWithoutTrappingReturnOrRetry() {
        val actions = mutableListOf<MessagesAction>()
        render(MessagesUiState(messages = listOf(message(1)), loading = false, markAllReadFailed = true), actions::add)
        composeRule.waitForIdle()
        captureMessagesVisualEvidence("57-short-font2-read-failed")
        assertWholeText("  全部标为已读")
        assertWholeText("返回首页")
        val failure = composeRule.onNodeWithTag("messages-mark-read-failed")
        failure.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        failure.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        layouts.forEach { assertLayoutContainsAllGlyphs(it.layoutInput.text.text, it) }
        composeRule.onNodeWithTag("messages-mark-all-read").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("messages-return-home").assertIsDisplayed().performClick()
        assertEquals(listOf(MessagesAction.MarkAllReadRequested, MessagesAction.ReturnHomeRequested), actions)
    }

    private fun assertWholeText(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertTrue("The real Text node must expose a layout for [$text]", layouts.isNotEmpty())
        layouts.forEach { assertLayoutContainsAllGlyphs(text, it) }
    }

    private fun assertLayoutContainsAllGlyphs(text: String, layout: TextLayoutResult) {
        val diagnostic = "[$text] size=${layout.size}, lines=${layout.lineCount}, constraints=${layout.layoutInput.constraints}"
        assertTrue(diagnostic, layout.lineCount > 0)
        assertEquals(diagnostic, text.length, layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertTrue("Ellipsis hides part of $diagnostic", !layout.isLineEllipsized(line))
            assertTrue("Line left clips $diagnostic", layout.getLineLeft(line) >= -1f)
            assertTrue("Line right clips $diagnostic", layout.getLineRight(line) <= layout.size.width + 1f)
            assertTrue("Line bottom clips $diagnostic", layout.getLineBottom(line) <= layout.size.height + 1f)
        }
    }

    private fun render(state: MessagesUiState, onAction: (MessagesAction) -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                RlTheme {
                    Box(Modifier.requiredSize(640.dp, 360.dp).testTag("messages-short-viewport")) {
                        MessagesScreen(state, onAction)
                    }
                }
            }
        }
    }

    private fun message(index: Int) = AppMessage(
        "message-$index", MessageKind.MANUAL_REVIEW_RESULT,
        "第 $index 条人工审核已完成，请查看对应章节的详细结果并继续上次中断的学习内容。",
        "第 $index 个来源：九月二日日记中关于与朋友沟通的一段真实经历，第二章等待继续学习。",
        1_783_338_720_000L + index, false, AppMessageTarget("review", "chapter-$index"), MessageTargetState.AVAILABLE,
    )
}
