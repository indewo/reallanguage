package com.reallanguage.app.core.app

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Real Graph -> production Home flow -> billing repository. Transport is isolated;
 * no server, AI, payment, private user data or credentials are accessed. */
class ProductionConnectionRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun anObservedNetworkLossCoversAnIdleLocalPageAndAvailabilityAloneDoesNotRestoreIt() {
        val fixture = renderFailure(initialRoute = HomeDiaryRoute.Input("仅保留在当前编辑器的内容。"))
        var listener: ((Boolean) -> Unit)? = null
        var releases = 0
        val observation = fixture.graph.startNetworkObservation { receiver ->
            listener = receiver
            receiver(true)
            AutoCloseable { releases++ }
        }
        try {
            compose.runOnIdle { listener!!(false) }
            compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
            compose.onNodeWithTag("diary-text-input").assertDoesNotExist()
            compose.runOnIdle { listener!!(true) }
            compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
            assertTrue(fixture.calls.isEmpty())
        } finally { observation.close() }
        assertEquals(1, releases)
    }

    @Test fun aRealLoginAfterAnExpiredSessionRetiresOnlyThePreviousConnectionIncident() {
        val fixture = renderFailure(refreshStatus = 401)
        val expired = checkNotNull(fixture.graph.connectionGate.incident.value)
        compose.onNodeWithTag("connection-error-reconnect").performClick()
        compose.waitUntil(5_000) { fixture.logins.get() == 1 }
        assertNull(fixture.graph.connectionGate.incident.value)
        val result = kotlinx.coroutines.runBlocking {
            fixture.graph.loginForApp(com.reallanguage.app.feature.auth.domain.LoginCommand("isolated@example.invalid", "ISOLATED_PASSWORD"))
        }
        assertTrue(result is com.reallanguage.app.feature.auth.domain.LoginResult.Authenticated)
        assertEquals(fixture.accountId, fixture.runtime.accountId)
        assertTrue(fixture.runtime.accountEpoch > expired.owner.epoch)
        assertNull(fixture.graph.connectionGate.incident.value)
        assertEquals("POST /api/v1/auth/sessions", fixture.calls.last())
        assertEquals(4, fixture.calls.size)
    }

    @Test fun offlineReturnUsesTheDiaryDiscardRuleAndContinueThenReconnectPreservesTheEditedText() {
        val fixture = renderFailure(initialRoute = HomeDiaryRoute.Input("原始隔离内容。"))
        val edited = "修改后的隔离内容。不会上传或建立正式草稿。"
        compose.onNodeWithTag("diary-text-input").performTextReplacement(edited)
        compose.runOnIdle { fixture.graph.connectionGate.networkLost() }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithTag("diary-text-input").assertDoesNotExist()
        compose.onNodeWithTag("connection-error-return").performClick()
        compose.onNodeWithText("继续编辑").assertIsDisplayed().performClick()
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithTag("diary-text-input").assertDoesNotExist()
        compose.onNodeWithTag("connection-error-reconnect").performClick()
        compose.waitUntil(5_000) { fixture.graph.connectionGate.incident.value == null }
        compose.onNodeWithTag("diary-text-input").assertTextEquals(edited)
        assertEquals(listOf("GET /api/v1/health", "POST /api/v1/auth/sessions/refresh"), fixture.calls.toList())
    }

    @Test fun reconnectChecksTheServiceAndStoredAccountButNeverRepeatsTheBusinessRead() {
        val fixture = renderFailure()
        compose.onNodeWithText("AI 费用").assertDoesNotExist()
        compose.onNodeWithTag("connection-error-reconnect").performClick()
        compose.waitUntil(5_000) { fixture.graph.connectionGate.incident.value == null }
        compose.onNodeWithTag("connection-error-sheet").assertDoesNotExist()
        compose.onNodeWithText("AI 费用").assertIsDisplayed()
        assertEquals(listOf("GET /api/v1/billing/summary", "GET /api/v1/health", "POST /api/v1/auth/sessions/refresh"), fixture.calls.toList())
        assertEquals("ISOLATED_ROTATED_REFRESH", fixture.credentials.value?.refreshToken)
        assertEquals(fixture.accountId, fixture.runtime.accountId)
        assertEquals(0, fixture.logins.get())
    }

    @Test fun failedServiceProbeLeavesThePageCoveredWithoutRefreshingTheAccount() {
        val fixture = renderFailure(healthStatus = 503)
        compose.onNodeWithTag("connection-error-reconnect").performClick()
        compose.waitUntil(5_000) { fixture.calls.size == 2 }
        compose.onNodeWithText("仍无法连接，请检查网络后重试。").assertIsDisplayed()
        compose.onNodeWithText("AI 费用").assertDoesNotExist()
        assertNotNull(fixture.graph.connectionGate.incident.value)
        assertEquals("ISOLATED_NOT_A_REFRESH", fixture.credentials.value?.refreshToken)
        assertEquals(0, fixture.logins.get())
    }

    @Test fun expiredStoredSessionRequiresLoginAfterItsOwnClearWithoutReopeningCachedContent() {
        val fixture = renderFailure(refreshStatus = 401)
        compose.onNodeWithTag("connection-error-reconnect").performClick()
        compose.waitUntil(5_000) { fixture.logins.get() == 1 }
        compose.onNodeWithText("AI 费用").assertDoesNotExist()
        assertNull(fixture.credentials.value)
        assertNull(fixture.runtime.accountId)
        assertNull(fixture.graph.connectionGate.incident.value)
        compose.onNodeWithText("isolated-login-destination").assertIsDisplayed()
        assertEquals(3, fixture.calls.size)
    }

    @Test fun serverFailureRemainsAnOperationFailureRatherThanClaimingTheConnectionIsDown() {
        val fixture = renderFailure(billingStatus = 503)
        compose.onNodeWithTag("connection-error-sheet").assertDoesNotExist()
        compose.onNodeWithText("AI 费用").assertIsDisplayed()
        assertNull(fixture.graph.connectionGate.incident.value)
        assertEquals(listOf("GET /api/v1/billing/summary"), fixture.calls.toList())
    }

    private fun renderFailure(billingStatus: Int? = null, healthStatus: Int = 200, refreshStatus: Int = 200,
        initialRoute: HomeDiaryRoute = HomeDiaryRoute.Billing()): Fixture {
        val fixture = Fixture()
        fixture.graph = ProductionAppGraph(compose.activity, fixture.runtime, HttpTransport { request ->
            fixture.calls.add("${request.method} ${request.path}")
            when (request.path) {
                "/api/v1/billing/summary" -> {
                    check(request.method == "GET")
                    if (billingStatus == null) throw IOException("Isolated unavailable transport")
                    HttpResponse(billingStatus, "{}")
                }
                "/api/v1/health" -> {
                    check(request.method == "GET")
                    HttpResponse(healthStatus, """{"status":"ok","service":"api","requestId":"isolated-recovery"}""")
                }
                "/api/v1/auth/sessions/refresh" -> {
                    check(request.method == "POST")
                    assertTrue(request.body.contains("ISOLATED_NOT_A_REFRESH"))
                    HttpResponse(refreshStatus, """{"status":"AUTHENTICATED","accountId":"${fixture.accountId}","accessToken":"ISOLATED_ROTATED_ACCESS","refreshToken":"ISOLATED_ROTATED_REFRESH","settingsCompleted":true}""")
                }
                "/api/v1/auth/sessions" -> {
                    check(request.method == "POST")
                    HttpResponse(200, """{"status":"AUTHENTICATED","accountId":"${fixture.accountId}","accessToken":"ISOLATED_LOGIN_ACCESS","refreshToken":"ISOLATED_LOGIN_REFRESH","settingsCompleted":true}""")
                }
                else -> error("Unexpected isolated request: ${request.method} ${request.path}")
            }
        }, fixture.credentials)
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.setContent { RlTheme { RlPaperBackground {
            if (fixture.loginDestination.value) androidx.compose.material3.Text("isolated-login-destination") else
            HomeProductionFlow(fixture.graph, initialRoute = initialRoute, onLoggedOut = {
                fixture.loginDestination.value = true
                fixture.logins.incrementAndGet()
            })
        } } }
        if (initialRoute is HomeDiaryRoute.Billing) compose.waitUntil(5_000) { fixture.calls.isNotEmpty() }
        compose.waitForIdle()
        return fixture
    }

    private class Fixture {
        val accountId = "connection-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("ISOLATED_NOT_A_CREDENTIAL", this@Fixture.accountId) }
        val credentials = MemoryCredentials(StoredSession(accountId, "ISOLATED_NOT_A_REFRESH", true))
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logins = AtomicInteger()
        val loginDestination = androidx.compose.runtime.mutableStateOf(false)
        lateinit var graph: ProductionAppGraph
    }

    private class MemoryCredentials(@Volatile var value: StoredSession?) : SessionCredentialStore {
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }

    @Test fun pageRequestNetworkFailureUsesTheProductionConnectionLayer() {
        val reads = AtomicInteger()
        val runtime = SessionRuntime().apply { update("ISOLATED_NOT_A_CREDENTIAL", "connection-${UUID.randomUUID()}") }
        val graph = ProductionAppGraph(compose.activity, runtime, HttpTransport { request ->
            check(request.method == "GET" && request.path == "/api/v1/billing/summary")
            reads.incrementAndGet()
            throw IOException("Isolated connection failure")
        }, object : SessionCredentialStore {
            override suspend fun load(): StoredSession? = error("No credential reads in this page failure test")
            override suspend fun save(session: StoredSession) = error("No credential writes")
            override suspend fun clear() = error("No credential removal")
        })
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.setContent { RlTheme { RlPaperBackground {
            HomeProductionFlow(graph, initialRoute = HomeDiaryRoute.Billing())
        } } }
        compose.waitUntil(5_000) { reads.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        capture("idle")
        assertEquals("A connection failure must not auto-repeat the original operation", 1, reads.get())
    }

    private fun capture(state: String) {
        val phase = InstrumentationRegistry.getArguments().getString("connectionCapture") ?: return
        require(phase in setOf("before", "after"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "49-production-connection-$state-$phase.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
