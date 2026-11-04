package com.reallanguage.app.feature.settings.presentation

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TimePicker
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.settings.domain.*
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReminderSettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val settings = InitialSettingsDraft(formalityLevel = 3, slangEnabled = false,
        basicVocabulary = BasicVocabulary.CET_6, voiceEnabled = true, autoPlayEnabled = false, speechSpeedLevel = 3)
    private val reminder = ReminderSettingsDraft(false, LocalTime.of(20, 0))
    private fun state() = SubsequentSettingsUiState(settings, settings, loading = false,
        originalReminder = reminder, reminderDraft = reminder)

    @Test fun defaultLocalPreferenceIsVisibleInSecondGroupWithoutRequestingPermission() {
        val actions = mutableListOf<SubsequentSettingsAction>(); var requests = 0
        composeRule.setContent { RlTheme { SubsequentSettingsScreen(state(), actions::add,
            onReminderEnableRequested = { requests++ }) } }
        composeRule.onNodeWithTag("settings-daily-reminder").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings-reminder-enabled").assertIsOff()
        composeRule.onNodeWithTag("settings-reminder-time").assertTextContains("20:00")
        composeRule.onAllNodesWithTag("subsequent-settings-group", true).assertCountEquals(3)
        assertEquals(0, requests); assertTrue(actions.isEmpty())
    }

    @Test fun enableOnlyRequestsPermissionAndDisableOnlyChangesDraft() {
        val actions = mutableListOf<SubsequentSettingsAction>(); var requests = 0
        val current = mutableStateOf(state())
        composeRule.setContent { RlTheme { SubsequentSettingsScreen(current.value, actions::add,
            onReminderEnableRequested = { requests++ }) } }
        composeRule.onNodeWithTag("settings-reminder-enabled").performScrollTo().performClick()
        assertEquals(1, requests); assertTrue(actions.isEmpty())
        composeRule.runOnIdle { current.value = state().copy(reminderDraft = reminder.copy(enabled = true)) }
        composeRule.onNodeWithTag("settings-reminder-enabled").assertIsOn().performClick()
        assertEquals(listOf(SubsequentSettingsAction.ReminderEnabledChanged(false)), actions)
        assertEquals(1, requests)
    }

    @Test @android.annotation.TargetApi(29)
    fun nativeTimePickerConfirmationChangesOnlyMinutePrecisionDraftAndCancelDoesNotSave() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val actions = mutableListOf<SubsequentSettingsAction>()
        composeRule.setContent { RlTheme { SubsequentSettingsScreen(state(), actions::add) } }
        composeRule.onNodeWithTag("settings-reminder-time").performScrollTo().performClick()
        setNativeTime(21, 15, android.R.id.button1)
        assertEquals(listOf(SubsequentSettingsAction.ReminderTimeChanged(LocalTime.of(21, 15))), actions)
        composeRule.onNodeWithTag("settings-reminder-time").performClick()
        setNativeTime(22, 30, android.R.id.button2)
        assertEquals(1, actions.size)
    }

    @Test fun submittingDisablesReminderEditsAndBothFooterActions() {
        composeRule.setContent { RlTheme { SubsequentSettingsScreen(state().copy(saving = true,
            reminderDraft = reminder.copy(enabled = true)), {}) } }
        composeRule.onNodeWithTag("settings-reminder-enabled").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("settings-reminder-time").assertIsNotEnabled()
        composeRule.onNodeWithTag("subsequent-settings-return").assertIsNotEnabled()
        composeRule.onNodeWithTag("subsequent-settings-save").assertIsNotEnabled()
    }

    @Test fun partialSuccessNamesSavedAndUnsavedSidesButReminderOnlyFailureDoesNotInventRemoteSave() {
        val current = mutableStateOf(state().copy(error = SubsequentSettingsError.SAVE_FAILED,
            saveProgress = SettingsSaveProgress(true, false)))
        composeRule.setContent { RlTheme { SubsequentSettingsScreen(current.value, {}) } }
        composeRule.onNodeWithTag("settings-save-status").assertIsDisplayed()
            .assertTextEquals("学习设置已保存；每日提醒尚未确认保存，请重试。")
        composeRule.runOnIdle { current.value = current.value.copy(saveProgress = SettingsSaveProgress(true, false, false, true)) }
        composeRule.onNodeWithTag("settings-save-status").assertTextEquals("每日提醒尚未确认保存，请重试。")
    }

    @Test fun doubleFontCanScrollReminderContentWithoutMovingOrHidingSaveAndReturn() {
        val actions = mutableListOf<SubsequentSettingsAction>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                RlTheme { SubsequentSettingsScreen(state().copy(reminderDraft = reminder.copy(time = LocalTime.of(21, 0))), actions::add) }
            }
        }
        val before = composeRule.onNodeWithTag("subsequent-settings-save").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("settings-reminder-time").performScrollTo().assertIsDisplayed()
        val after = composeRule.onNodeWithTag("subsequent-settings-save").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        composeRule.onNodeWithTag("subsequent-settings-save").performClick()
        composeRule.onNodeWithTag("subsequent-settings-return").assertIsDisplayed().performClick()
        assertEquals(listOf(SubsequentSettingsAction.SaveRequested, SubsequentSettingsAction.ReturnRequested), actions)
    }

    @Test fun absentLocalDraftDoesNotRenderAnInventedEnabledSwitch() {
        composeRule.setContent { RlTheme { SubsequentSettingsScreen(SubsequentSettingsUiState(settings, settings, loading = false), {}) } }
        composeRule.onNodeWithTag("settings-daily-reminder").assertDoesNotExist()
    }

    @android.annotation.TargetApi(29)
    private fun setNativeTime(hour: Int, minute: Int, buttonId: Int) {
        composeRule.runOnIdle {
            val root = WindowInspector.getGlobalWindowViews().single { findTimePicker(it) != null }
            requireNotNull(findTimePicker(root)).apply { this.hour = hour; this.minute = minute }
            requireNotNull(root.findViewById<Button>(buttonId)).performClick()
        }
    }

    private fun findTimePicker(view: View): TimePicker? {
        if (view is TimePicker) return view
        if (view is ViewGroup) repeat(view.childCount) { index ->
            findTimePicker(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
