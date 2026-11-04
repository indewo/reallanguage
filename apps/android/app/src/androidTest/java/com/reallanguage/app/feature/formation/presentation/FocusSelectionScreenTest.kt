package com.reallanguage.app.feature.formation.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import org.junit.Rule
import org.junit.Test

class FocusSelectionScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun entireSelectionRuleFitsInsideItsPaper() {
        composeRule.setContent { RlTheme { FocusSelectionScreen(FocusSelectionUiState(units = emptyList()), {}) } }
        composeRule.onNodeWithTag("focus_rules").assertIsDisplayed()
        composeRule.onNodeWithText("从完整内容中\n选择 1—3 句\n作为这次学习重点。").assertIsDisplayed()
    }

    @Test
    fun showsEveryUnitAndMarksRecommendationWithoutPreselection() {
        val state = FocusSelectionUiState(
            units = listOf(
                SemanticUnit("u1", 0, "推荐句。", "表达自然"),
                SemanticUnit("u2", 1, "普通句。"),
            ),
        )
        composeRule.setContent { RlTheme { FocusSelectionScreen(state, {}) } }

        composeRule.onNodeWithText("推荐句。").assertIsDisplayed()
        composeRule.onNodeWithText("普通句。").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("AI 推荐").assertIsDisplayed()
        composeRule.onNodeWithText("已选").assertIsDisplayed()
        composeRule.onNodeWithText("0 / 3").assertIsDisplayed()
        composeRule.onNodeWithText("确定选择内容").assertIsNotEnabled()
        composeRule.onNodeWithTag("focus_header").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_sentence_list").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_rules").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_counter").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_confirm").assertIsDisplayed()
    }

    @Test
    fun sentenceAndBackRegionsKeepTheirActions() {
        val actions = mutableListOf<FocusSelectionAction>()
        val state = FocusSelectionUiState(
            units = listOf(SemanticUnit("u1", 0, "推荐句。", "表达自然")),
        )
        composeRule.setContent { RlTheme { FocusSelectionScreen(state, actions::add) } }

        composeRule.onNodeWithTag("focus_sentence_u1").performClick()
        composeRule.onNodeWithTag("focus_back").performClick()

        assert(actions.contains(FocusSelectionAction.UnitToggled("u1")))
        assert(actions.contains(FocusSelectionAction.BackRequested))
    }

    @Test
    fun longFullContentRemainsReachable() {
        val state = FocusSelectionUiState(
            units = (1..9).map { SemanticUnit("u$it", it - 1, "第${it}句。") },
        )
        composeRule.setContent { RlTheme { FocusSelectionScreen(state, {}) } }
        composeRule.onNodeWithText("第9句。").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("focus_confirm").assertIsDisplayed()
    }

    @Test
    fun compactViewportKeepsRulesContentAndConfirmReachable() {
        val state = FocusSelectionUiState(
            units = (1..6).map { SemanticUnit("u$it", it - 1, "第${it}句需要完整显示。") },
        )
        composeRule.setContent {
            RlTheme {
                Box(Modifier.size(width = 600.dp, height = 360.dp)) {
                    FocusSelectionScreen(state, {})
                }
            }
        }

        composeRule.onNodeWithTag("focus_rules").assertIsDisplayed()
        composeRule.onNodeWithTag("focus_confirm").assertIsDisplayed()
        composeRule.onNodeWithText("第6句需要完整显示。").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("focus_confirm").assertIsDisplayed()
    }

    @Test
    fun multilineSentenceGrowsInsteadOfClippingText() {
        val state = FocusSelectionUiState(units = listOf(
            SemanticUnit("long", 0, "这是需要完整显示、不能裁切的长句。".repeat(20)),
        ))
        composeRule.setContent { RlTheme { FocusSelectionScreen(state, {}) } }
        composeRule.onNodeWithTag("focus_sentence_long").assertHeightIsAtLeast(100.dp)
    }
}
