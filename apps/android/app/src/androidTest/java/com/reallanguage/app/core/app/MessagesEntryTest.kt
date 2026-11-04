package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.*
import com.reallanguage.app.feature.home.presentation.MessagesViewModel
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Production entry lifecycle with isolated messages, not human manual-review acceptance. */
class MessagesEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }
    @Test fun leavingCancelsThePendingReadWithoutDeliveringADestination() {
        val visible = mutableStateOf(true)
        var reads = 0
        var cancelled = 0
        var destinations = 0
        val factory = factory {
            reads++
            try { awaitCancellation() } finally { cancelled++ }
        }
        rule.setContent { RlTheme {
            if (visible.value) MessagesEntry(factory, "same", { destinations++ }, { destinations++ })
        } }
        rule.waitUntil(5_000) { reads == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        assertEquals(1, cancelled)
        assertEquals(0, destinations)
    }
    @Test fun reenteringTheSameEntryReloadsInsteadOfReusingDepartedMessages() {
        val visible = mutableStateOf(true)
        var reads = 0
        var home = 0
        val factory = factory { reads++; MessagesLoadResult.Ready(emptyList()) }
        rule.setContent { RlTheme {
            if (visible.value) MessagesEntry(factory, "same", {}, { home++; visible.value = false })
        } }
        rule.onNodeWithTag("messages-return-home").performClick()
        rule.waitUntil(5_000) { home == 1 }
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        assertEquals(2, reads)
        rule.onNodeWithTag("messages-return-home").performClick()
        rule.waitUntil(5_000) { home == 2 }
    }
    @Test fun recompositionUsesTheCurrentDestinationCallback() {
        val generation = mutableStateOf(0)
        val callbacks = mutableListOf<Int>()
        val factory = factory { MessagesLoadResult.Ready(emptyList()) }
        rule.setContent { RlTheme {
            val captured = generation.value
            MessagesEntry(factory, "callback", {}, { callbacks += captured })
        } }
        rule.waitForIdle()
        rule.runOnIdle { generation.value = 1 }; rule.waitForIdle()
        rule.onNodeWithTag("messages-return-home").performClick()
        rule.waitUntil(5_000) { callbacks.isNotEmpty() }
        assertEquals(listOf(1), callbacks)
    }
    private fun factory(read: suspend () -> MessagesLoadResult) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MessagesViewModel(object : MessagesRepository {
            override suspend fun load() = read()
            override suspend fun markAllRead() = false
        }) as T
    }
}
