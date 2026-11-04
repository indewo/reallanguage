package com.reallanguage.app.feature.review.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbilityStatus
import com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft
import com.reallanguage.app.feature.review.domain.ReviewChapterSnapshot
import com.reallanguage.app.feature.review.domain.ReviewContextSentence
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import org.junit.Rule
import org.junit.Test

class ReviewChapterScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun handledModuleIsNotPresentedAsStillPendingWhileNextModuleCanBeOpened() {
        val actions = mutableListOf<ReviewChapterAction>()
        val state = ReviewChapterUiState(
            reviewChapterId = "pending-review", commandId = "pending-command", loading = false,
            chapter = ReviewChapterSnapshot(
                "pending-review", "original", ReviewSourceType.DIARY, "已保存日记",
                listOf("I made a plan."), "I made a plan.",
                listOf(
                    ReviewAbility("handled", ReviewAbilityModule.FULL_SENTENCE_SPEAKING, ReviewAbilityStatus.LEARNING),
                    ReviewAbility("next", ReviewAbilityModule.WORD_MEANING, ReviewAbilityStatus.LEARNING),
                ),
            ),
            resultDrafts = listOf(ReviewAbilityResultDraft("handled", "handled-result", false)),
        )
        composeRule.setContent { RlTheme { ReviewChapterScreen(state, actions::add) } }
        composeRule.onNodeWithText("整句会说").assertDoesNotExist()
        composeRule.onNodeWithTag("review-chapter-continue").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(listOf(ReviewChapterAction.OpenCurrentModuleRequested), actions)
        org.junit.Assert.assertEquals("next", state.currentAbility?.abilityId)
        org.junit.Assert.assertEquals(1, state.resultDrafts.size)
    }

    @Test
    fun showsChapterAndFocusProgressThenOpensCurrentAbility() {
        val actions = mutableListOf<ReviewChapterAction>()
        val state = ReviewChapterUiState(
            reviewChapterId = "review-2",
            commandId = "command-2",
            chapter = ReviewChapterSnapshot(
                "review-2",
                "71c692ae-2b2b-4b5f-84bc-2549312cd8af",
                ReviewSourceType.DIARY,
                "7 月 12 日日记 · 面试前",
                listOf("I was nervous.", "I finally spoke up.", "She smiled."),
                "I finally spoke up.",
                listOf(ReviewAbility("a1", ReviewAbilityModule.FULL_SENTENCE_SPEAKING, ReviewAbilityStatus.LEARNING, sentenceId = "focus-2")),
                originalChapterNumber = 2,
                originalSentences = listOf(
                    ReviewContextSentence("focus-1", "I was nervous.", "我很紧张。", true, 0),
                    ReviewContextSentence("focus-2", "I finally spoke up.", "我终于开口了。", true, 1),
                    ReviewContextSentence("focus-3", "She smiled.", "她笑了。", true, 2),
                ),
            ),
            loading = false,
        )

        composeRule.setContent { RlTheme { ReviewChapterScreen(state, actions::add) } }

        composeRule.onNodeWithTag("review-chapter-workspace").assertIsDisplayed()
        composeRule.onNodeWithText("统一回顾 · 第 2 章").assertIsDisplayed()
        composeRule.onNodeWithText("重点句 2 / 3").assertIsDisplayed()
        composeRule.onNodeWithTag("review-chapter-continue").assertIsDisplayed().performClick()
        assert(actions == listOf(ReviewChapterAction.OpenCurrentModuleRequested))
    }
}
