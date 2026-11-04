package com.reallanguage.app.feature.review.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performScrollTo
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.R
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.review.domain.ReviewResultSummary
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class ReviewResultScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before fun focusedLearningWindow() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun loadingDoesNotClaimCompletionBeforeSettlement() {
        composeRule.setContent { RlTheme { ReviewResultScreen(ReviewResultUiState("s", "c")) {} } }
        composeRule.onNodeWithText("本次回顾已完成").assertDoesNotExist()
        composeRule.onNodeWithTag("review-result-home").assertDoesNotExist()
    }

    @Test fun failureDoesNotClaimCompletionAndOffersRetry() {
        val actions = mutableListOf<ReviewResultAction>()
        composeRule.setContent { RlTheme {
            ReviewResultScreen(ReviewResultUiState("s", "c", settling = false, settlementFailed = true), actions::add)
        } }
        composeRule.onNodeWithText("本次回顾已完成").assertDoesNotExist()
        composeRule.onNodeWithText("重试").performClick()
        org.junit.Assert.assertEquals(listOf(ReviewResultAction.RetryRequested), actions)
    }

    @Test
    fun showsSettledCountsAndReturnsHome() {
        val actions = mutableListOf<ReviewResultAction>()
        val state = ReviewResultUiState(
            "session-1",
            "settle-1",
            summary = ReviewResultSummary(3, 0, cycleCompleted = true, normalLearningDaysReset = true),
            settling = false,
        )

        composeRule.setContent { RlTheme { ReviewResultScreen(state, actions::add) } }

        composeRule.onNodeWithTag("review-result-summary").assertIsDisplayed()
        composeRule.onNodeWithText("今日回顾结果").assertIsDisplayed()
        composeRule.onNodeWithText("本次回顾已完成").assertIsDisplayed()
        composeRule.onNodeWithTag("review-result-home").assertIsDisplayed().performClick()
        assert(actions == listOf(ReviewResultAction.ReturnHomeRequested))
    }

    @Test fun shortScreenAtTwoHundredPercentKeepsHomeFixedWhileAllResultTextCanBeRead() {
        val actions = mutableListOf<ReviewResultAction>()
        val state = ReviewResultUiState("session-short", "settle-short", settling = false,
            summary = ReviewResultSummary(123, 456, cycleCompleted = false, normalLearningDaysReset = false))
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                Box(Modifier.size(800.dp, 320.dp).testTag("review-result-short-root")) {
                    RlTheme { ReviewResultScreen(state, actions::add) }
                }
            }
        }
        val home = composeRule.onNodeWithTag("review-result-home")
        home.assertIsDisplayed()
        val before = home.getUnclippedBoundsInRoot()
        listOf(
            composeRule.activity.getString(R.string.review_day_result_completed, 123),
            composeRule.activity.getString(R.string.review_day_result_remaining, 456),
        ).forEach { actualCount ->
            composeRule.onNodeWithText(actualCount).performScrollTo().assertIsDisplayed()
            org.junit.Assert.assertEquals("Reading either real count must not move the home action",
                before, home.getUnclippedBoundsInRoot())
        }
        val note = composeRule.onNodeWithTag("review-result-cycle-note")
        note.performScrollTo().assertIsDisplayed()
        val textBounds = note.getUnclippedBoundsInRoot()
        val viewport = composeRule.onNodeWithTag("review-result-scroll-viewport").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("The complete closing explanation must fit inside the body viewport",
            textBounds.top >= viewport.top && textBounds.bottom <= viewport.bottom)
        val after = home.getUnclippedBoundsInRoot()
        val root = composeRule.onNodeWithTag("review-result-short-root").getUnclippedBoundsInRoot()
        org.junit.Assert.assertEquals("The fixed home action must not scroll with the summary", before, after)
        org.junit.Assert.assertTrue("The full home action must stay inside the short screen", after.top >= root.top && after.bottom <= root.bottom)
        home.assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(listOf(ReviewResultAction.ReturnHomeRequested), actions)
    }
}
