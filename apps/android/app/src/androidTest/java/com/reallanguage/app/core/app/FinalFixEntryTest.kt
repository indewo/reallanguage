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
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.formation.presentation.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Isolated save adapters test the actual screen/entry lifetime, not a database commit. */
class FinalFixEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val chapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "我喝茶。", true,
        listOf(EnglishCandidate("e1", "I drink tea."))))))

    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun leavingCancelsPendingFixAndDoesNotAnnounceAnUnsavedChapter() {
        val visible = mutableStateOf(true)
        var calls = 0
        var cancellations = 0
        var navigations = 0
        val factory = factory(ContentFixRepository {
            calls++
            try { awaitCancellation() } finally { cancellations++ }
        })
        rule.setContent { RlTheme {
            if (visible.value) FinalFixEntry(factory, "fix-entry", { navigations++ }, { navigations++ })
        } }
        confirm()
        rule.waitUntil(5_000) { calls == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        assertEquals(1, cancellations)
        assertEquals(0, navigations)
    }

    @Test fun sameEntryReopensFreshAndReplaysTheSameFixCommandInsteadOfKeepingConsumedSuccess() {
        val visible = mutableStateOf(true)
        var created = 0
        val requests = mutableListOf<ContentFixRequest>()
        val opened = mutableListOf<String>()
        val factory = factory(ContentFixRepository { requests += it; ContentFixResult.Fixed("saved-chapter") }) { created++ }
        rule.setContent { RlTheme {
            if (visible.value) FinalFixEntry(factory, "same-entry", opened::add, {})
        } }
        confirm(); rule.waitUntil(5_000) { opened.size == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        assertEquals(2, created)
        rule.onNodeWithTag("final_fix_confirm").assertIsEnabled()
        confirm(); rule.waitUntil(5_000) { opened.size == 2 }
        assertEquals(listOf("saved-chapter", "saved-chapter"), opened)
        assertEquals(requests.first(), requests.last())
        assertEquals("stable-fix-command", requests.last().commandId)
    }

    @Test fun systemBackBeforeSubmissionReturnsToTargetsWithoutFixingOrFallingThroughParent() {
        var parentBack = 0
        var returns = 0
        var calls = 0
        val factory = factory(ContentFixRepository { calls++; ContentFixResult.Failed })
        rule.setContent { RlTheme {
            BackHandler { parentBack++ }
            FinalFixEntry(factory, "back-entry", { fail("No fix was submitted") }, { returns++ })
        } }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        assertEquals(1, returns)
        assertEquals(0, calls)
        assertEquals(0, parentBack)
    }

    @Test fun busyFixConsumesSystemBackAndDuplicateConfirmationUntilOneFixedResult() {
        val response = CompletableDeferred<ContentFixResult>()
        var calls = 0
        var parentBack = 0
        var returns = 0
        val opened = mutableListOf<String>()
        val factory = factory(ContentFixRepository { calls++; response.await() })
        rule.setContent { RlTheme {
            BackHandler { parentBack++ }
            FinalFixEntry(factory, "busy-entry", opened::add, { returns++ })
        } }
        confirm(); rule.waitUntil(5_000) { calls == 1 }
        rule.onNodeWithTag("final_fix_confirm").assertIsNotEnabled().performClick()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        assertEquals(0, parentBack)
        assertEquals(0, returns)
        assertTrue(opened.isEmpty())
        rule.runOnIdle { response.complete(ContentFixResult.Fixed("exact-chapter")) }
        rule.waitUntil(5_000) { opened.size == 1 }
        assertEquals(listOf("exact-chapter"), opened)
        assertEquals(1, calls)
    }

    @Test fun pendingFixUsesTheCurrentNavigationCallbackAfterRecomposition() {
        val response = CompletableDeferred<ContentFixResult>()
        val callbackVersion = mutableStateOf("old")
        val opened = mutableListOf<String>()
        var calls = 0
        val factory = factory(ContentFixRepository { calls++; response.await() })
        rule.setContent { RlTheme {
            val capturedVersion = callbackVersion.value
            FinalFixEntry(factory, "callback-entry", { opened += "$capturedVersion:$it" }, {})
        } }
        confirm(); rule.waitUntil(5_000) { calls == 1 }
        rule.runOnIdle { callbackVersion.value = "current" }; rule.waitForIdle()
        rule.runOnIdle { response.complete(ContentFixResult.Fixed("exact-chapter")) }
        rule.waitUntil(5_000) { opened.size == 1 }
        assertEquals(listOf("current:exact-chapter"), opened)
    }

    private fun confirm() { rule.onNodeWithTag("final_fix_confirm").performClick(); rule.waitForIdle() }
    private fun factory(repository: ContentFixRepository, created: () -> Unit = {}) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            created()
            return FinalFixViewModel("stable-flow", "stable-fix-command", chapters, mapOf("u1" to "e1"),
                emptyList(), emptySet(), repository) as T
        }
    }
}
