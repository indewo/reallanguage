package com.reallanguage.app.feature.home.presentation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DailyReminderIntroDialogTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersReminderTimelineAndDispatchesBothChoices() {
        val actions = mutableListOf<DailyReminderIntroAction>()
        composeRule.setContent {
            RlTheme {
                DailyReminderIntroDialog(DailyReminderIntroUiState(), actions::add)
            }
        }

        composeRule.onNodeWithTag("daily-reminder-timeline").assertIsDisplayed()
        composeRule.onAllNodesWithTag("daily-reminder-time", useUnmergedTree = true)
            .assertCountEquals(3)
        composeRule.onNodeWithTag("daily-reminder-permission-note").assertIsDisplayed()
        composeRule.onNodeWithTag("daily-reminder-enable").performClick()
        composeRule.onNodeWithTag("daily-reminder-not-now").performClick()

        assertEquals(
            listOf(DailyReminderIntroAction.EnableRequested, DailyReminderIntroAction.NotNowRequested),
            actions,
        )
    }

    @Test fun referenceLayoutSeparatesTheBellFromDaytimeAndKeepsItsClosingAssuranceWhole() {
        composeRule.setContent { RlTheme { Box(Modifier.requiredSize(915.5.dp, 429.5.dp).consumeWindowInsets(WindowInsets.safeDrawing)) {
            DailyReminderIntroContent(DailyReminderIntroUiState(), {})
        } } }
        composeRule.onNodeWithTag("daily-reminder-title-underline").assertIsDisplayed()
        val daytime = composeRule.onNodeWithTag("daily-reminder-daytime-icon").fetchSemanticsNode().boundsInRoot
        val bell = composeRule.onNodeWithTag("daily-reminder-notification-marker").fetchSemanticsNode().boundsInRoot
        val evening = composeRule.onNodeWithTag("daily-reminder-evening-icon").fetchSemanticsNode().boundsInRoot
        assertTrue("Reference has its own reminder marker between the day and evening, not replacing the sun",
            bell.center.x > daytime.right && bell.center.x < evening.left)
        assertWholeText("daily-reminder-settings-assurance")
        val root = composeRule.onNodeWithTag("daily-reminder-sheet").fetchSemanticsNode().boundsInRoot
        val footer = composeRule.onNodeWithTag("daily-reminder-settings-assurance").fetchSemanticsNode().boundsInRoot
        assertTrue("Closing assurance must remain inside the sheet", footer.bottom <= root.bottom)
    }

    @Test fun actualFullScreenDialogKeepsClosingAssuranceVisibleAtReferenceSize() {
        composeRule.setContent { RlTheme { DailyReminderIntroDialog(DailyReminderIntroUiState(), {}) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("daily-reminder-settings-assurance").performScrollTo()
        assertWholeText("daily-reminder-settings-assurance")
        val panel = composeRule.onNodeWithTag("daily-reminder-main-panel").fetchSemanticsNode().boundsInRoot
        val root = composeRule.onNodeWithTag("daily-reminder-sheet").fetchSemanticsNode().boundsInRoot
        val footer = composeRule.onNodeWithTag("daily-reminder-settings-assurance").fetchSemanticsNode().boundsInRoot
        assertTrue("Actual dialog footer must have a full visible line; root=$root panel=$panel footer=$footer", footer.height >= 39f)
        val windows = android.view.inspector.WindowInspector.getGlobalWindowViews().joinToString { view ->
            "${view.width}x${view.height}; insets=${view.rootWindowInsets}"
        }
        assertTrue("Actual dialog keeps its safe frame; root=$root panel=$panel windows=$windows", panel.top > root.top)
    }

    @Test fun shortLandscapeLargeFontCanReadFailureAndReachBothChoicesWithoutTruncation() {
        val actions = mutableListOf<DailyReminderIntroAction>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                RlTheme { Box(Modifier.requiredSize(640.dp, 320.dp).consumeWindowInsets(WindowInsets.safeDrawing)) {
                    DailyReminderIntroContent(DailyReminderIntroUiState(error = DailyReminderIntroError.SAVE_FAILED), actions::add)
                } }
            }
        }
        composeRule.onNodeWithTag("daily-reminder-settings-assurance").performScrollTo().assertIsDisplayed()
        assertWholeText("daily-reminder-settings-assurance")
        composeRule.onNodeWithText("暂时无法保存选择，请重试；这不会阻止你继续使用首页。")
            .performScrollTo().assertIsDisplayed()
        listOf("daily-reminder-enable", "daily-reminder-not-now").forEach {
            composeRule.onNodeWithTag(it).performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(listOf(DailyReminderIntroAction.RetryRequested, DailyReminderIntroAction.NotNowRequested), actions)
    }

    @Test fun referencePanelRetainsTheOriginalRightHandFrameBelowTheTitle() {
        composeRule.setContent { RlTheme { Box(Modifier.requiredSize(915.5.dp, 429.5.dp).consumeWindowInsets(WindowInsets.safeDrawing)) {
            DailyReminderIntroContent(DailyReminderIntroUiState(), {})
        } } }
        val root = composeRule.onNodeWithTag("daily-reminder-sheet").getUnclippedBoundsInRoot()
        val panel = composeRule.onNodeWithTag("daily-reminder-main-panel").getUnclippedBoundsInRoot()
        assertTrue("Original right panel starts around x=803px", (panel.left - root.left).value in 395f..410f)
        assertTrue("Original right frame begins below its 130px title region", (panel.top - root.top).value in 64f..75f)
        assertTrue("Original right frame extends toward the sheet bottom", (panel.bottom - root.top).value in 417f..429.5f)
    }

    private fun assertWholeText(tag: String) {
        val node = composeRule.onNodeWithTag(tag)
        node.assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(get(layouts))
            val measurements = layouts.joinToString { layout ->
                "size=${layout.size}, bounds=$bounds, overflowHeight=${layout.didOverflowHeight}, " +
                    "lines=" + (0 until layout.lineCount).joinToString { line ->
                        "${layout.getLineLeft(line)}..${layout.getLineRight(line)} / bottom=${layout.getLineBottom(line)}"
                    }
            }
            assertTrue("$tag must show complete text, not a clipped fragment: $measurements", layouts.all { layout ->
                !layout.didOverflowHeight && layout.size.height <= bounds.height + 1f &&
                    (0 until layout.lineCount).all { line -> !layout.isLineEllipsized(line) &&
                        layout.getLineLeft(line) >= -.5f && layout.getLineRight(line) <= layout.size.width + .5f }
            })
        }
    }
}
