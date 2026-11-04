package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.safety.data.HttpSafetyHandlingRepository
import com.reallanguage.app.feature.safety.data.SafetyHandlingCommandIds
import com.reallanguage.app.feature.safety.domain.*
import com.reallanguage.app.feature.safety.presentation.SafetyHandlingViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Entry + JSON codec, isolated HTTP responses, not an actual administrator action. */
class SafetyReviewReplayEntryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun landscape() {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(5_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun lostAcknowledgementThenApprovedOpensExistingReviewNotFalsePending() = verify("APPROVED")
    @Test fun lostAcknowledgementThenRejectedOpensExistingReviewNotFalsePending() = verify("NOT_APPROVED")

    private fun verify(status: String) {
        val requests = mutableListOf<HttpRequest>()
        val opened = mutableListOf<String>()
        var wrongNavigation = 0
        val session = SessionRuntime().apply { update("isolated-token", "isolated-owner") }
        val repository = HttpSafetyHandlingRepository({ request ->
            requests += request
            if (requests.size == 1) HttpResponse(503, "") else HttpResponse(200,
                """{"status":"SUBMITTED","reviewRequestId":"isolated-review","reviewStatus":"$status"}""")
        }, session, SafetyHandlingCommandIds("isolated-accept", "isolated-edit", "isolated-same-review"))
        val source = ConfirmedSource("原内容。", SourceType.DIARY, SourceContentShape.ARTICLE)
        val details = SafetyCaseDetails("isolated-case", source, "隔离测试理由", source.copy(text = "脱敏内容。"), emptyList())
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SafetyHandlingViewModel(details, repository) as T
        }
        rule.setContent { RlTheme {
            SafetyHandlingEntry(factory, HomeDiaryRoute.SafetyHandling(details, HomeDiaryRoute.Input(source.text)),
                { wrongNavigation++ }, { wrongNavigation++ }, { wrongNavigation++ }, opened::add)
        } }
        rule.onNodeWithText("提交人工复核").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { requests.size == 1 }; rule.waitForIdle()
        assertTrue(opened.isEmpty())
        rule.onNodeWithText("提交人工复核").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { requests.size == 2 }; rule.waitForIdle()
        assertEquals(listOf("isolated-review"), opened)
        assertEquals(0, wrongNavigation)
        assertEquals(requests[0].body, requests[1].body)
    }
}
