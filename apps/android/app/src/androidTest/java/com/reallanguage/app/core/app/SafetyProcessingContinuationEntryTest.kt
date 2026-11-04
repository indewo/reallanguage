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
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.diary.presentation.ConfirmedDiaryInputValidator
import com.reallanguage.app.feature.diary.presentation.DiaryInputViewModel
import com.reallanguage.app.feature.safety.data.HttpSafetyCheckRepository
import com.reallanguage.app.feature.safety.data.JsonSafetyCheckWireCodec
import com.reallanguage.app.feature.safety.presentation.SafetyCheckViewModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SafetyProcessingContinuationEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun restoringSameSafetyRequestReplaysOneOperationWithTheSameCommand() {
        val visible = mutableStateOf(true)
        val route = HomeDiaryRoute.SafetyCheck(text, entryId = "explicit-request-1")
        val harness = SafetyHttpHarness()
        val delivered = mutableListOf<String>()
        rule.setContent { RlTheme {
            if (visible.value) SafetyCheckEntry(route, harness::factory, { _ -> }, { caseId, _ -> delivered += caseId })
        } }
        rule.waitUntil(5_000) { delivered.size == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        rule.runOnIdle { visible.value = true }; rule.waitForIdle()
        assertEquals(2, harness.commands.size)
        assertEquals(1, harness.operations.size)
        assertEquals(harness.commands[0], harness.commands[1])
        assertEquals(listOf("isolated-case-1", "isolated-case-1"), delivered)
    }

    @Test fun returnedDiaryCanResubmitSameTextDirectlyIntoANewSafetyHandlingCase() {
        val route = mutableStateOf<HomeDiaryRoute>(HomeDiaryRoute.Input(text))
        val harness = SafetyHttpHarness()
        val delivered = mutableListOf<String>()
        rule.setContent { RlTheme {
            when (val current = route.value) {
                is HomeDiaryRoute.Input -> DiaryInputEntry(
                    factory { DiaryInputViewModel(ConfirmedDiaryInputValidator, current.text) }, current.text,
                    onEvent = { route.value = homeDiaryRoute(current, it) },
                )
                is HomeDiaryRoute.SafetyCheck -> SafetyCheckEntry(current, harness::factory, { _ -> }, { caseId, _ ->
                    delivered += caseId
                    route.value = HomeDiaryRoute.Home
                })
                else -> Unit
            }
        } }
        rule.onNodeWithTag("diary-submit").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        // Safety checking is an internal operation, not a user-facing step. It may finish before
        // the next frame, so verify the delivered result instead of requiring the transient route
        // to remain visible.
        rule.waitUntil(5_000) { harness.commands.size == 1 }
        rule.waitUntil(5_000) { delivered.size == 1 }
        rule.runOnIdle { route.value = HomeDiaryRoute.Input(text) }; rule.waitForIdle()
        rule.onNodeWithTag("diary-submit").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        rule.waitUntil(5_000) { delivered.size == 2 }
        assertEquals(listOf("isolated-case-1", "isolated-case-2"), delivered)
        assertEquals(2, harness.operations.size)
        assertNotEquals(harness.commands[0], harness.commands[1])
        assertEquals(listOf(text, text), harness.texts)
    }

    @Test fun serviceFailureRetryKeepsTheCommandAndConfirmedSource() {
        val route = HomeDiaryRoute.SafetyCheck(text, entryId = "retry-request")
        val harness = SafetyHttpHarness(failFirst = true)
        val delivered = mutableListOf<String>()
        rule.setContent { RlTheme {
            SafetyCheckEntry(route, harness::factory, { _ -> }, { caseId, _ -> delivered += caseId })
        } }
        rule.waitUntil(5_000) { harness.commands.size == 1 }
        rule.onNodeWithText("重试").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { delivered.size == 1 }
        assertEquals(2, harness.commands.size)
        assertEquals(harness.commands[0], harness.commands[1])
        assertEquals(listOf(text, text), harness.texts)
    }

    // Isolated HTTP contract fixture, not real safety/AI evidence.
    private class SafetyHttpHarness(private val failFirst: Boolean = false) {
        val commands = mutableListOf<String>()
        val texts = mutableListOf<String>()
        val operations = linkedMapOf<String, String>()
        private val runtime = SessionRuntime().apply { update("isolated-session") }
        private val transport = HttpTransport { request ->
            val json = JSONObject(request.body.orEmpty())
            val command = json.getString("commandId")
            commands += command
            texts += json.getString("text")
            if (failFirst && commands.size == 1) HttpResponse(503, "")
            else {
                val id = operations.getOrPut(command) { "isolated-case-${operations.size + 1}" }
                HttpResponse(200, JSONObject().put("status", "BLOCKED").put("safetyCaseId", id).toString())
            }
        }
        fun factory(source: com.reallanguage.app.feature.safety.domain.ConfirmedSource, commandId: String): ViewModelProvider.Factory = factory {
            SafetyCheckViewModel(source, HttpSafetyCheckRepository(transport, JsonSafetyCheckWireCodec(), runtime, commandId))
        }
    }

    companion object {
        private const val text = "今天完成了一次练习。"
        private fun factory(create: () -> ViewModel) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
        }
    }
}
