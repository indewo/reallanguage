package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Actual root/auth/reset composition and Graph, isolated transport; sends no real email. */
class ProductionAnonymousConnectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun resetRequestFailureCanRecoverTheSameEmailWithoutReplayingTheEmailSend() {
        val fixture = render()
        compose.onNodeWithText("忘记密码").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(EMAIL)
        compose.onNodeWithText("发送验证邮件").performClick()
        compose.waitUntil(5_000) { fixture.requests.size == 1 }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithText(EMAIL).assertDoesNotExist()
        compose.onNodeWithTag("connection-error-reconnect").performClick()
        compose.waitUntil(5_000) { fixture.graph.connectionGate.incident.value == null }
        compose.onNode(hasSetTextAction()).assertTextContains(EMAIL)
        assertEquals(listOf("POST /api/v1/auth/password-resets", "GET /api/v1/health"), fixture.requests.toList())
        compose.onNodeWithText("发送验证邮件").performClick()
        compose.onNodeWithTag("password-reset-waiting").assertIsDisplayed()
        assertEquals(3, fixture.requests.size)
        assertEquals(2, fixture.sendCount)
        assertNull(fixture.runtime.accountId)
    }

    @Test fun observedAnonymousLossPreservesTheRegistrationFieldsWithoutSubmittingThem() {
        val fixture = render()
        compose.onNodeWithText("注册").performClick()
        val email = hasSetTextAction() and hasAnyAncestor(hasTestTag("registration-email-row"))
        compose.onNode(email).performTextReplacement(EMAIL)
        compose.runOnIdle { fixture.graph.connectionGate.networkLost() }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithTag("registration-paper-sheet").assertDoesNotExist()
        compose.onNodeWithTag("connection-error-reconnect").performClick()
        compose.waitUntil(5_000) { fixture.graph.connectionGate.incident.value == null }
        compose.onNode(email).assertTextContains(EMAIL)
        compose.onNodeWithText("提交注册").assertIsDisplayed()
        assertEquals(listOf("GET /api/v1/health"), fixture.requests.toList())
    }

    @Test fun offlineResetReturnGoesThroughItsExistingLoginRouteAndDoesNotSendAgain() {
        val fixture = render()
        compose.onNodeWithText("忘记密码").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(EMAIL)
        compose.onNodeWithText("发送验证邮件").performClick()
        compose.waitUntil(5_000) { fixture.requests.size == 1 }
        compose.onNodeWithTag("connection-error-return").performClick()
        // Returning does not claim that the connection has been repaired.
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithTag("connection-error-reconnect").performClick()
        compose.waitUntil(5_000) { fixture.graph.connectionGate.incident.value == null }
        compose.onNodeWithText("REAL LANGUAGE · 登录").assertIsDisplayed()
        compose.onNodeWithTag("password-reset-return-login").assertDoesNotExist()
        assertEquals(listOf("POST /api/v1/auth/password-resets", "GET /api/v1/health"), fixture.requests.toList())
    }

    private fun render(): Fixture {
        val fixture = Fixture()
        fixture.graph = ProductionAppGraph(compose.activity, fixture.runtime, HttpTransport { request ->
            fixture.requests += "${request.method} ${request.path}"
            when (request.path) {
                "/api/v1/health" -> HttpResponse(200, """{"status":"ok","service":"api","requestId":"isolated-anonymous"}""")
                "/api/v1/auth/password-resets" -> {
                    assertEquals("POST", request.method)
                    assertTrue(request.body.contains(EMAIL))
                    fixture.sendCount++
                    if (fixture.sendCount == 1) throw IOException("Isolated network loss")
                    HttpResponse(200, """{"status":"SENT","challengeId":"isolated-reset-challenge"}""")
                }
                else -> error("Unexpected isolated request: ${request.method} ${request.path}")
            }
        }, object : SessionCredentialStore {
            override suspend fun load(): StoredSession? = null
            override suspend fun save(session: StoredSession) = error("No login in this isolated test")
            override suspend fun clear() = Unit
        })
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.setContent { RlTheme { ProductionApp(fixture.graph) } }
        compose.onNodeWithText("REAL LANGUAGE · 登录").assertIsDisplayed()
        return fixture
    }

    private class Fixture {
        val runtime = SessionRuntime()
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var sendCount = 0
        lateinit var graph: ProductionAppGraph
    }
    private companion object { const val EMAIL = "isolated-recovery@example.invalid" }
}
