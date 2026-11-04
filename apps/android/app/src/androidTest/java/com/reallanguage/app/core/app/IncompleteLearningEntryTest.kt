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
import com.reallanguage.app.feature.home.domain.*
import com.reallanguage.app.feature.home.presentation.IncompleteLearningViewModel
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Production entry with isolated account-scoped loading fixtures, not a live user recovery claim. */
class IncompleteLearningEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun removingThePageCancelsItsPendingAccountReadWithoutNavigation() {
        val visible = mutableStateOf(true)
        var reads = 0
        var cancelled = 0
        var navigation = 0
        val factory = factory {
            reads++
            try { awaitCancellation() } finally { cancelled++ }
        }
        rule.setContent { RlTheme {
            if (visible.value) IncompleteLearningEntry(factory, "same", { _, _, _ -> navigation++ }, { navigation++ })
        } }
        rule.waitUntil(5_000) { reads == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        assertEquals(1, cancelled)
        assertEquals(0, navigation)
    }

    @Test fun reenteringTheSameListStartsANewReadAndDoesNotReuseDepartedState() {
        val visible = mutableStateOf(true)
        var reads = 0
        var exits = 0
        val factory = factory { reads++; IncompleteLearningLoadResult.Ready(emptyList()) }
        rule.setContent { RlTheme {
            if (visible.value) IncompleteLearningEntry(factory, "same", { _, _, _ -> }, { exits++; visible.value = false })
        } }
        rule.onNodeWithTag("incomplete-learning-return-home").performClick()
        rule.waitUntil(5_000) { exits == 1 }
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        assertEquals(2, reads)
        rule.onNodeWithTag("incomplete-learning-return-home").performClick()
        rule.waitUntil(5_000) { exits == 2 }
    }

    @Test fun systemBackDuringLoadingReturnsHomeOnceAndCancelsTheRead() {
        var reads = 0
        var cancelled = 0
        var home = 0
        var parentBack = 0
        val factory = factory {
            reads++
            try { awaitCancellation() } finally { cancelled++ }
        }
        rule.setContent { RlTheme {
            BackHandler { parentBack++ }
            IncompleteLearningEntry(factory, "back", { _, _, _ -> }, { home++ })
        } }
        rule.waitUntil(5_000) { reads == 1 }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, home)
        assertEquals(0, parentBack)
        assertEquals(1, cancelled)
    }

    private fun factory(repository: IncompleteLearningRepository) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = IncompleteLearningViewModel(repository) as T
    }
}
