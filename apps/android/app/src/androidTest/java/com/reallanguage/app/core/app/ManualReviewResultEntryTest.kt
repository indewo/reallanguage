package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.safety.domain.*
import com.reallanguage.app.feature.safety.presentation.ManualReviewResultViewModel
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Isolated lifecycle/contract fixtures; not a real human-review acceptance claim. */
class ManualReviewResultEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun leavingCancelsTheReadWithoutNavigatingTwice() {
        val visible = mutableStateOf(true)
        var loads = 0
        var cancellations = 0
        var navigation = 0
        val factory = factory(ManualReviewResultRepository {
            loads++
            try { awaitCancellation() } finally { cancellations++ }
        })
        rule.setContent { RlTheme {
            if (visible.value) ManualReviewResultEntry(factory, "same-entry", {}, { _, _ -> }, {}, { navigation++ })
        } }
        rule.waitUntil(5_000) { loads == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        assertEquals(1, cancellations)
        assertEquals(0, navigation)
    }

    @Test fun systemBackDuringReadUsesTheSameExplicitMessagesExitAndCancelsWork() {
        var loads = 0
        var cancellations = 0
        var messages = 0
        var parentBack = 0
        val factory = factory(ManualReviewResultRepository {
            loads++
            try { awaitCancellation() } finally { cancellations++ }
        })
        rule.setContent { RlTheme {
            BackHandler { parentBack++ }
            ManualReviewResultEntry(factory, "back-entry", {}, { _, _ -> }, {}, { messages++ })
        } }
        rule.waitUntil(5_000) { loads == 1 }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, messages)
        assertEquals(0, parentBack)
        assertEquals(1, cancellations)
    }

    @Test fun reopeningTheSameResultEntryReadsCurrentFactsInsteadOfReusingDepartedState() {
        val visible = mutableStateOf(true)
        var loads = 0
        var messages = 0
        val factory = factory(ManualReviewResultRepository { loads++; ManualReviewResult.Unavailable })
        rule.setContent { RlTheme {
            if (visible.value) ManualReviewResultEntry(factory, "same-entry", {}, { _, _ -> }, {}, {
                messages++; visible.value = false
            })
        } }
        rule.onNodeWithTag("manual-review-return-messages").performClick()
        rule.waitUntil(5_000) { messages == 1 }
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        assertEquals(2, loads)
        rule.onNodeWithTag("manual-review-return-messages").performClick()
        rule.waitUntil(5_000) { messages == 2 }
    }

    @Test fun approvedActionDeliversTheExactOwnedReviewedSourceOnce() {
        val source = ReviewedSafetySource("isolated-case", "保留这段完整来源。", SourceType.DIARY,
            SourceContentShape.ARTICLE, "隔离审核理由")
        val opened = mutableListOf<ReviewedSafetySource>()
        var wrongNavigation = 0
        val factory = factory(ManualReviewResultRepository { ManualReviewResult.Ready(ManualReviewOutcome.APPROVED, source) })
        rule.setContent { RlTheme {
            ManualReviewResultEntry(factory, "approved-entry", opened::add, { _, _ -> wrongNavigation++ },
                { wrongNavigation++ }, { wrongNavigation++ })
        } }
        rule.onNodeWithText("进入全量拆句").performClick()
        rule.onNodeWithText("进入全量拆句").performClick()
        rule.waitForIdle()
        assertEquals(listOf(source), opened)
        assertEquals(0, wrongNavigation)
    }

    private fun factory(repository: ManualReviewResultRepository) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ManualReviewResultViewModel("isolated-review", repository) as T
    }
}
