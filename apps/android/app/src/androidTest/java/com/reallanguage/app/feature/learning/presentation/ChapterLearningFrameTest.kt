package com.reallanguage.app.feature.learning.presentation

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChapterLearningFrameTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun keepsEnglishLeftChineseRightAndMarksCurrentFocusSentence() {
        val chapter = FixedLearningChapter(
            chapterId = "c1",
            chapterNumber = 1,
            sentences = listOf(
                FixedLearningSentence(
                    "s1", "I expressed myself more clearly.", "我表达得更清楚了。", true,
                ),
                FixedLearningSentence(
                    "s2", "That made me happy.", "这让我很开心。", false,
                ),
            ),
        )
        composeRule.setContent {
            RlTheme {
                ChapterLearningFrame(chapter = chapter, currentFocusSentenceId = "s1") {
                    Text("基础讲解")
                }
            }
        }

        composeRule.onNodeWithTag("learning-focus-s1").assertIsDisplayed()
        composeRule.onNodeWithText("基础讲解").assertIsDisplayed()
        val englishLeft = composeRule.onNodeWithTag("learning-english-s1")
            .fetchSemanticsNode().boundsInRoot.left
        val chineseLeft = composeRule.onNodeWithTag("learning-chinese-s1")
            .fetchSemanticsNode().boundsInRoot.left
        assertTrue(englishLeft < chineseLeft)
    }
}
