package com.reallanguage.app.feature.settings.presentation

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.DailyReminderScheduleDecision
import com.reallanguage.app.feature.home.domain.ReminderCancelReason
import com.reallanguage.app.feature.home.domain.ReminderDecisionGap
import com.reallanguage.app.feature.home.presentation.dailyReminderSchedulingStatus
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReminderSchedulingStatusScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun componentDisplaysEachRealMappedStatusWithoutInventingSavedOrDeliveredSuccess() {
        val decisions = listOf<DailyReminderScheduleDecision?>(null,
            DailyReminderScheduleDecision.Cancel("isolated-settings", ReminderCancelReason.PERMISSION_UNAVAILABLE),
            DailyReminderScheduleDecision.Cancel("isolated-settings", ReminderCancelReason.PLATFORM_UNAVAILABLE),
            DailyReminderScheduleDecision.NeedsDecision(ReminderDecisionGap.MISSED_TIME),
            DailyReminderScheduleDecision.NeedsDecision(ReminderDecisionGap.TIME_ZONE_CHANGED))
        val message = mutableStateOf(dailyReminderSchedulingStatus(decisions.first()).message)
        composeRule.setContent { RlTheme { ReminderSchedulingStatusText(message.value) } }
        for (decision in decisions) {
            composeRule.runOnIdle { message.value = dailyReminderSchedulingStatus(decision).message }
            composeRule.onNodeWithText("已保存配置的系统状态").assertIsDisplayed()
            composeRule.onNodeWithTag("reminder-scheduling-message").assertTextEquals(message.value)
            assertFalse(message.value.contains("已保存"))
            assertFalse(message.value.contains("已送达"))
            assertFalse(message.value.contains("已发送"))
        }
    }

    @Test fun doubleFontStatusWrapsCompletelyWithoutClipping() {
        val message = dailyReminderSchedulingStatus(DailyReminderScheduleDecision.NeedsDecision(
            ReminderDecisionGap.AMBIGUOUS_OR_MISSING_LOCAL_TIME)).message
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                RlTheme { ReminderSchedulingStatusText(message, Modifier.width(240.dp)) }
            }
        }
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag("reminder-scheduling-message").assertIsDisplayed()
            .assertTextEquals(message).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertFalse("All status lines must be laid out", layout.hasVisualOverflow)
        assertTrue("The narrow double-font case must really wrap", layout.lineCount > 1)
        val bounds = composeRule.onNodeWithTag("reminder-scheduling-message").fetchSemanticsNode().boundsInRoot
        assertTrue("The parent must not clip a complete text layout", bounds.height >= layout.size.height - 1f)
    }

    @Test fun screenForwardsSavedConfigurationStatusButKeepsUnsavedDraftAndSaveActionIndependent() {
        val settings = InitialSettingsDraft(formalityLevel = 3, basicVocabulary = BasicVocabulary.CET_6)
        val reminder = ReminderSettingsDraft(false, LocalTime.of(20, 0))
        val state = SubsequentSettingsUiState(settings, settings, loading = false,
            originalReminder = reminder, reminderDraft = reminder.copy(enabled = true))
        val actions = mutableListOf<SubsequentSettingsAction>()
        val message = dailyReminderSchedulingStatus(DailyReminderScheduleDecision.Cancel(
            "isolated-settings", ReminderCancelReason.DISABLED)).message
        composeRule.setContent { RlTheme { SubsequentSettingsScreen(state, actions::add,
            reminderSchedulingMessage = message) } }
        composeRule.onNodeWithTag("reminder-scheduling-message").performScrollTo().assertIsDisplayed()
            .assertTextEquals("每日提醒已关闭。")
        composeRule.onNodeWithText("已保存配置的系统状态").assertIsDisplayed()
        assertTrue(actions.isEmpty())
        composeRule.onNodeWithTag("subsequent-settings-save").assertIsEnabled().performClick()
        assertEquals(listOf(SubsequentSettingsAction.SaveRequested), actions)
    }

    @Test fun absentStatusDoesNotInventAnArrangementFromTheDraft() {
        val settings = InitialSettingsDraft(formalityLevel = 3, basicVocabulary = BasicVocabulary.CET_6)
        val reminder = ReminderSettingsDraft(true, LocalTime.of(20, 0))
        composeRule.setContent { RlTheme { SubsequentSettingsScreen(SubsequentSettingsUiState(
            settings, settings, loading = false, originalReminder = reminder, reminderDraft = reminder), {}) } }
        composeRule.onNodeWithTag("reminder-scheduling-message").assertDoesNotExist()
    }
}
