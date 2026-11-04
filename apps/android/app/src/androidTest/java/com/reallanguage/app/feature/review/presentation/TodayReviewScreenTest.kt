package com.reallanguage.app.feature.review.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.review.domain.ReviewChapterSummary
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

class TodayReviewScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun dailyLimitExplainsDeferredWorkInsteadOfSayingThereIsNone() {
        composeRule.setContent { RlTheme {
            TodayReviewScreen(TodayReviewUiState(loading = false, deferredByDailyLimit = true), {})
        } }
        composeRule.onNodeWithText("今天的剩余额度不足以完成下一章，未完成内容保留到下一个回顾日。").assertIsDisplayed()
        composeRule.onNodeWithText("今天没有待回顾内容。").assertDoesNotExist()
    }

    @Test fun emptyQueueStillHasReturnHome() = verifyReturnHome(TodayReviewUiState(loading = false))

    @Test fun failedQueueStillHasReturnHome() = verifyReturnHome(TodayReviewUiState(loading = false, loadFailed = true))

    @Test fun loadingQueueStillHasReturnHome() = verifyReturnHome(TodayReviewUiState(loading = true))

    @Test fun deferredQueueStillHasReturnHome() = verifyReturnHome(TodayReviewUiState(loading = false, deferredByDailyLimit = true))

    private fun verifyReturnHome(state: TodayReviewUiState) {
        val actions = mutableListOf<TodayReviewAction>()
        composeRule.setContent { RlTheme { TodayReviewScreen(state, actions::add) } }
        composeRule.onNodeWithText("返回首页").assertIsDisplayed().performClick()
        assertEquals(listOf(TodayReviewAction.ReturnHomeRequested), actions)
        if (state.deferredByDailyLimit) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.cacheDir, "review-deferred-state.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }

    @Test
    fun showsRealQueueSummaryAndStartsCurrentChapter() {
        val actions = mutableListOf<TodayReviewAction>()
        val state = TodayReviewUiState(
            chapters = listOf(
                ReviewChapterSummary("r1", "c1", ReviewSourceType.DIARY, "昨晚的日记", "I finally spoke up.", 1, 2),
                ReviewChapterSummary("r2", "c2", ReviewSourceType.QUICK_RECORDING, "通勤录音", "The train was delayed again.", 2, 3),
            ),
            loading = false,
        )

        composeRule.setContent { RlTheme { TodayReviewScreen(state, actions::add) } }

        composeRule.onNodeWithTag("today-review-queue").assertIsDisplayed()
        composeRule.onNodeWithText("今天有 3 个重点句需要回顾").assertIsDisplayed()
        composeRule.onNodeWithTag("today-review-start").assertIsDisplayed().performClick()
        assert(actions == listOf(TodayReviewAction.StartCurrentChapterRequested))
    }
}
