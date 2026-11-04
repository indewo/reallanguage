package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PatternBookScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersSeparateWritingAndSpeakingStatusAndOpensTheSelectedPattern() {
        val actions = mutableListOf<PatternBookAction>()
        val state = PatternBookUiState(
            patterns = listOf(
                PatternRecord(
                    patternId = "p1",
                    structureEn = "work up the courage to + 动词",
                    functionZh = "鼓起勇气做某事",
                    writingStatus = KnowledgeStatus.MASTERED,
                    speakingStatus = KnowledgeStatus.LEARNING,
                    consecutiveSuccessCount = 2,
                    occurrenceCount = 4,
                ),
            ),
            loading = false,
        )

        composeRule.setContent { RlTheme { PatternBookScreen(state, actions::add) } }

        composeRule.onNodeWithTag("pattern-book-list").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("会写，已掌握，连续成功 2 次", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("会说，学习中，连续成功 2 次", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("pattern-row-p1").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("ledger-back").assertIsDisplayed().performClick()
        assertEquals(
            listOf(
                PatternBookAction.PatternSelected("p1"),
                PatternBookAction.ReturnHomeRequested,
            ),
            actions,
        )
    }
}
