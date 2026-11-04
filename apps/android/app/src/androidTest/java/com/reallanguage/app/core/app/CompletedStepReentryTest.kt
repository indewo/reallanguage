package com.reallanguage.app.core.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.CompletedStepSnapshot
import org.junit.Rule
import org.junit.Test

class CompletedStepReentryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun sameEnglishInAnotherSnapshotCannotKeepPreviousChineseOrExplanation() {
        val route = mutableStateOf(HomeDiaryRoute.CompletedStepReview(
            CompletedStepSnapshot("LR-02", "第一份已完成内容", "I run a cafe.", "我经营一家咖啡馆。", "之前的讲解"), HomeDiaryRoute.Home,
        ))
        composeRule.setContent { RlTheme { CompletedStepReviewRoute(route.value, {}) } }
        composeRule.runOnIdle { route.value = HomeDiaryRoute.CompletedStepReview(
            CompletedStepSnapshot("LR-02", "第二份已完成内容", "I run a cafe.", "这家咖啡馆由我经营。", "本次的讲解"), HomeDiaryRoute.Home,
        ) }
        composeRule.onAllNodesWithText("我经营一家咖啡馆。").assertCountEquals(0)
        composeRule.onAllNodesWithText("这家咖啡馆由我经营。").assertCountEquals(3)
    }

    @Test fun realNativePlaybackReturnsToPronounceAfterCompletionOrFailure() {
        val snapshot = CompletedStepSnapshot("LR-02", "已完成内容", "Hello world.", "你好，世界。", null)
        composeRule.setContent { RlTheme { CompletedStepReviewRoute(HomeDiaryRoute.CompletedStepReview(snapshot, HomeDiaryRoute.Home), {}) } }
        composeRule.onAllNodesWithText("发音")[0].assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) { composeRule.onAllNodesWithText("静音").fetchSemanticsNodes().isEmpty() }
        composeRule.onAllNodesWithText("发音").assertCountEquals(2)
        composeRule.onNodeWithTag("completed-step-return").assertIsDisplayed()
    }
}
