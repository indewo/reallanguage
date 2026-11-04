package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class TargetSelfAssessmentControlsTest {
    @get:Rule val compose = createComposeRule()
    private val word = SelfAssessmentTarget("word", "s", "calm", "平静的", setOf(SelfAssessmentAbility.MEANING_WRITING))
    private val pattern = SelfAssessmentTarget("pattern", "s", "feel + adjective", "表达感受", setOf(SelfAssessmentAbility.PATTERN_WRITING, SelfAssessmentAbility.PATTERN_SPEAKING))

    @Test fun wordOnlyShowsCurrentSelectedMeaningAndDispatchesItsIdentity() {
        val actions = mutableListOf<TargetSelfAssessmentAction>()
        compose.setContent { RlTheme { TargetSelfAssessmentControls(TargetSelfAssessmentUiState(listOf(word, pattern)), actions::add, display = "calm") } }
        compose.onNodeWithText("平静的").assertIsDisplayed()
        compose.onNodeWithText("表达感受").assertDoesNotExist()
        compose.onNodeWithText("会了").performClick()
        compose.onNodeWithText("我会说").assertDoesNotExist()
        assertEquals(listOf(TargetSelfAssessmentAction.Mark("word", SelfAssessmentAbility.MEANING_WRITING)), actions)
    }

    @Test fun patternKeepsWritingAndSpeakingIndependentAndPreventsRepeatMark() {
        val actions = mutableListOf<TargetSelfAssessmentAction>()
        compose.setContent { RlTheme { TargetSelfAssessmentControls(
            TargetSelfAssessmentUiState(listOf(pattern.copy(assessedAbilities = setOf(SelfAssessmentAbility.PATTERN_WRITING)))), actions::add, targetId = "pattern",
        ) } }
        compose.onNodeWithText("会写：暂定已会").assertIsNotEnabled()
        compose.onNodeWithText("我会说").performClick()
        assertEquals(listOf(TargetSelfAssessmentAction.Mark("pattern", SelfAssessmentAbility.PATTERN_SPEAKING)), actions)
    }

    @Test fun failureProvidesRetryWithoutClaimingSaved() {
        val actions = mutableListOf<TargetSelfAssessmentAction>()
        compose.setContent { RlTheme { TargetSelfAssessmentControls(TargetSelfAssessmentUiState(listOf(word), failed = true), actions::add, display = "calm") } }
        compose.onNodeWithText("暂定已会").assertDoesNotExist()
        compose.onNodeWithText("重试自评").performClick()
        assertEquals(listOf(TargetSelfAssessmentAction.Retry), actions)
    }

    @Test fun unselectedWordsDoNotGetAControlThatWouldCreateKnowledge() {
        compose.setContent { RlTheme { TargetSelfAssessmentControls(TargetSelfAssessmentUiState(listOf(word)), {}, display = "unselected") } }
        compose.onNodeWithText("会了").assertDoesNotExist()
    }
}
