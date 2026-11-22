package com.reallanguage.app.core.app

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.review.domain.ReviewChapterSummary
import com.reallanguage.app.feature.review.domain.ReviewQueueResult
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import com.reallanguage.app.feature.review.domain.TodayReviewRepository
import com.reallanguage.app.feature.review.presentation.TodayReviewViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TodayReviewEntryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun reenteringReadsRemainingQueueInsteadOfOpeningCompletedChapter() {
        val visible = mutableStateOf(true)
        var loads = 0
        val opened = mutableListOf<String>()
        val repository = object : TodayReviewRepository {
            override suspend fun loadTodayQueue(maxFocusSentences: Int): ReviewQueueResult {
                loads++
                return ReviewQueueResult.Ready(listOf(ReviewChapterSummary("review-$loads", "original-$loads",
                    ReviewSourceType.DIARY, "隔离测试日记", "Sentence $loads.", 1, 1)))
            }
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TodayReviewViewModel(repository) as T
        }
        composeRule.setContent { RlTheme {
            if (visible.value) TodayReviewEntry(factory,
                onChapter = { opened += it; visible.value = false },
                onHome = { visible.value = false },
            ) else Button(onClick = { visible.value = true }) { Text("重新进入回顾") }
        } }
        composeRule.onNodeWithTag("today-review-start").performClick()
        composeRule.waitUntil { opened.size == 1 }
        composeRule.onNodeWithText("重新进入回顾").performClick()
        composeRule.onNodeWithTag("today-review-start").performClick()
        composeRule.waitUntil { opened.size == 2 }
        assertEquals(listOf("review-1", "review-2"), opened)
        assertEquals(2, loads)
    }

    @Test fun nonEmptyQueueReturnsHomeWithoutOpeningAChapter() {
        val opened = mutableListOf<String>()
        var homeCalls = 0
        val repository = object : TodayReviewRepository {
            override suspend fun loadTodayQueue(maxFocusSentences: Int): ReviewQueueResult =
                ReviewQueueResult.Ready(listOf(ReviewChapterSummary("review-1", "chapter-1",
                    ReviewSourceType.DIARY, "隔离测试日记", "A complete review sentence.", 1, 1)))
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TodayReviewViewModel(repository) as T
        }
        composeRule.setContent { RlTheme {
            TodayReviewEntry(factory, onChapter = opened::add, onHome = { homeCalls++ })
        } }

        composeRule.onNodeWithTag("today-review-queue").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("返回首页").assertIsDisplayed().performClick()

        assertEquals(1, homeCalls)
        assertEquals(emptyList<String>(), opened)
    }
}
