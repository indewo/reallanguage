package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.RemainingChapterSummary
import org.junit.Rule
import org.junit.Test

class ChapterSelectionScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun showsSeparatedChapterCardsAndSelectsRequestedChapter() {
        val actions = mutableListOf<ChapterSelectionAction>()
        val state = ChapterSelectionUiState(
            sourceId = "source",
            chapters = listOf(
                RemainingChapterSummary("c2", 2, "同事听完后点了点头"),
                RemainingChapterSummary("c3", 3, "我也轻松了很多"),
            ),
        )

        composeRule.setContent { RlTheme { ChapterSelectionScreen(state, actions::add) } }

        composeRule.onNodeWithTag("chapter-selection-list").assertIsDisplayed()
        composeRule.onNodeWithText("这份日记还有 2 个未开始章节").assertIsDisplayed()
        composeRule.onNodeWithTag("chapter-selection-start-c2").assertIsDisplayed().performClick()
        assert(actions == listOf(ChapterSelectionAction.ChapterSelected("c2")))
    }
}
