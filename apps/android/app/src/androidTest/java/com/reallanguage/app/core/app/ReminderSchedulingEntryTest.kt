package com.reallanguage.app.core.app

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.reallanguage.app.feature.home.domain.*
import com.reallanguage.app.feature.home.presentation.dailyReminderSchedulingStatus
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReminderSchedulingEntryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun onlyTheCompletedRuntimeResultIsShownAsTheSavedConfigurationStatus() {
        val result = DailyReminderScheduleDecision.NeedsDecision(ReminderDecisionGap.MISSED_TIME)
        var calls = 0
        rule.setContent { ReminderSchedulingEntry("owner", { "owner" }, { calls++; result }) { Text(it ?: "unchecked") } }
        rule.onNodeWithText(dailyReminderSchedulingStatus(result, true).message).assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun aLateResultFromThePreviousAccountIsNeverShown() {
        var account = "old-owner"
        var calls = 0
        val result = CompletableDeferred<DailyReminderScheduleDecision>()
        rule.setContent { ReminderSchedulingEntry("old-owner", { account }, { calls++; result.await() }) { Text(it ?: "unchecked") } }
        rule.runOnIdle {
            assertEquals(1, calls)
            account = "new-owner"
            result.complete(DailyReminderScheduleDecision.Cancel("old-owner", ReminderCancelReason.DISABLED))
        }
        rule.onNodeWithText("每日提醒已关闭。").assertDoesNotExist()
    }

    @Test fun returningFromSystemSettingsRechecksPermissionWithoutChangingReminderPreferences() {
        val owner = TestOwner()
        var reason = ReminderCancelReason.DISABLED
        var calls = 0
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            ReminderSchedulingEntry("owner", { "owner" }, { calls++; DailyReminderScheduleDecision.Cancel("owner", reason) }) { Text(it ?: "unchecked") }
        } }
        rule.onNodeWithText("每日提醒已关闭。").assertIsDisplayed()
        rule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            reason = ReminderCancelReason.PERMISSION_UNAVAILABLE
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        rule.onNodeWithText("尚未获得通知权限，未安排每日提醒。").assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, calls) }
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
