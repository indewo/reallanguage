package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.time.YearMonth
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Full login -> registration form/consents -> real Graph verification route, not a VM lookup.
 * All transport responses and credentials are isolated memory fixtures. No real mail, service,
 * account, TTS, or AI; synthetic passwords are neither logged nor captured in screenshots. */
class ProductionEmailVerificationConnectionLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "verification-entry-${UUID.randomUUID()}"
    private val directory = File(base.cacheDir, prefix)
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val privateDb = IsolatedLearningDatabaseContext(base, prefix)
    private val context = object : ContextWrapper(privateDb) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix-$name"
            preferences.add(isolated)
            return base.getSharedPreferences(isolated, mode)
        }
    }
    private val mounted = mutableStateOf(true)
    private var fixture: Fixture? = null

    @Before fun preparePrivateContextAndMainWindow() {
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.mkdir())
        val database = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(database.parentFile == base.cacheDir.canonicalFile && database.name.startsWith("$prefix-") && !database.exists())
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        assertEquals(1f, compose.activity.resources.configuration.fontScale, .01f)
    }

    @After fun removeOnlyThisTestEntryAndItsUuidStorage() {
        compose.runOnIdle { mounted.value = false; compose.activity.viewModelStore.clear() }
        fixture?.pending?.forEach { it.release("WAITING") }
        compose.waitForIdle()
        val database = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(database.parentFile == base.cacheDir.canonicalFile && database.name.startsWith("$prefix-") && database.extension == "db")
        check(privateDb.deleteDatabase(DATABASE) || !database.exists())
        synchronized(preferences) { preferences.toList() }.forEach { name ->
            check(name.startsWith("$prefix-")); base.deleteSharedPreferences(name)
        }
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == prefix)
        check(!directory.exists() || directory.deleteRecursively())
    }

    @Test fun interruptedStatusCannotLoginAndRecoveryStartsOneCurrentMonitorWithoutResending() {
        val current = renderAndRegister()
        val registration = current.registrations.single()
        compose.runOnIdle { current.graph.connectionGate.networkLost() }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithTag("email-verification-paper-sheet").assertDoesNotExist()
        compose.onNodeWithText(FIRST_EMAIL).assertDoesNotExist()
        current.pending[0].release("VERIFIED")
        compose.waitUntil(5_000) { current.returnedStatus.get() == 1 }
        compose.waitForIdle()
        assertEquals("An old verification callback under the cover must not log in", 0, current.logins.get())
        assertNull(current.runtime.accountId)
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()

        tap(compose.onNodeWithTag("connection-error-reconnect"))
        compose.waitUntil(5_000) { current.graph.connectionGate.incident.value == null && current.pending.size == 2 }
        compose.onNodeWithTag("email-verification-paper-sheet").assertIsDisplayed()
        compose.onNodeWithText(FIRST_EMAIL).assertIsDisplayed()
        assertEquals(listOf(registration, registration), current.pending.map { it.registrationId })
        assertEquals(1, current.health.get())
        assertTrue("Restoring a monitor must not send another verification email", current.resends.isEmpty())
        assertEquals(1, current.registrations.size)

        // A VERIFIED response belonging to the new, unblocked monitor may now follow the existing
        // production auto-login rule. Holding every read prevents a timing-dependent polling budget.
        current.pending[1].release("VERIFIED")
        compose.waitUntil(5_000) { current.logins.get() == 1 && current.runtime.accountId == current.accountId }
        compose.onNodeWithTag("settings-vocabulary-basic").assertIsDisplayed()
        assertEquals(2, current.pending.size)
        assertEquals(2, current.returnedStatus.get())
        assertTrue(current.resends.isEmpty())
        assertEquals(5, current.calls.size) // registration + old/new status + health + verified login
    }

    @Test fun returningUnderTheCoverThenRegisteringAgainOwnsANewVerificationEntry() {
        val current = renderAndRegister()
        val oldRegistration = current.registrations.single()
        compose.runOnIdle { current.graph.connectionGate.networkLost() }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        tap(compose.onNodeWithTag("connection-error-return"))
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithTag("registration-paper-sheet").assertDoesNotExist()
        tap(compose.onNodeWithTag("connection-error-reconnect"))
        compose.waitUntil(5_000) { current.graph.connectionGate.incident.value == null }
        compose.onNodeWithTag("registration-paper-sheet").assertIsDisplayed()
        emailInput().assertTextContains(FIRST_EMAIL).performTextReplacement(SECOND_EMAIL)
        hideIme()
        assertEquals(1, current.registrations.size)
        tap(compose.onNodeWithText("提交注册"))
        compose.waitUntil(5_000) { current.registrations.size == 2 && current.pending.size == 2 }
        val newRegistration = current.registrations[1]
        assertTrue(oldRegistration != newRegistration)
        assertEquals(newRegistration, current.pending[1].registrationId)

        current.pending[0].release("VERIFIED")
        compose.waitUntil(5_000) { current.returnedStatus.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("email-verification-paper-sheet").assertIsDisplayed()
        compose.onNodeWithText(SECOND_EMAIL).assertIsDisplayed()
        compose.onNodeWithText(FIRST_EMAIL).assertDoesNotExist()
        assertEquals(0, current.logins.get())
        assertNull(current.runtime.accountId)
        assertTrue(current.resends.isEmpty())

        tap(compose.onNodeWithTag("email-verification-resend"))
        compose.waitUntil(5_000) { current.resends.size == 1 }
        assertEquals(listOf(newRegistration), current.resends.toList())
        compose.onNodeWithTag("email-verification-resend").assertIsNotEnabled()
        tap(compose.onNodeWithTag("email-verification-change-email"))
        compose.onNodeWithTag("registration-paper-sheet").assertIsDisplayed()
        emailInput().assertTextContains(SECOND_EMAIL)
        assertEquals(2, current.pending.size)
        assertEquals(1, current.health.get())
        assertEquals(6, current.calls.size) // two registrations/status reads, health, explicit resend
    }

    private fun renderAndRegister(): Fixture {
        val current = Fixture()
        fixture = current
        current.graph = ProductionAppGraph(context, current.runtime, HttpTransport { request ->
            current.calls += "${request.method} ${request.path}"
            when {
                request.method == "POST" && request.path == "/api/v1/auth/registrations" -> {
                    val body = JSONObject(request.body)
                    check(body.getString("email") in setOf(FIRST_EMAIL, SECOND_EMAIL))
                    check(body.getString("password") == current.password)
                    check(body.getBoolean("acceptedTerms") && body.getBoolean("acceptedPrivacyPolicy"))
                    check(!YearMonth.parse(body.getString("birthYearMonth")).isAfter(YearMonth.now().minusYears(14)))
                    val id = UUID.randomUUID().toString()
                    current.registrations += id
                    HttpResponse(200, """{"status":"EMAIL_VERIFICATION_REQUIRED","registrationId":"$id"}""")
                }
                request.method == "GET" && request.path == "/api/v1/health" -> {
                    current.health.incrementAndGet()
                    HttpResponse(200, """{"status":"ok","service":"api","requestId":"isolated-verification"}""")
                }
                request.method == "GET" && request.path.endsWith("/verification-status") -> {
                    val id = request.path.removePrefix("/api/v1/auth/registrations/").removeSuffix("/verification-status")
                    check(id in current.registrations)
                    val response = suspendCoroutine<HttpResponse> { current.pending += StatusResponse(id, it) }
                    current.returnedStatus.incrementAndGet()
                    response
                }
                request.method == "POST" && request.path.endsWith("/verification-email") -> {
                    val id = request.path.removePrefix("/api/v1/auth/registrations/").removeSuffix("/verification-email")
                    check(id in current.registrations)
                    current.resends += id
                    HttpResponse(200, """{"status":"RESENT","retryAfterSeconds":60}""")
                }
                request.method == "POST" && request.path == "/api/v1/auth/sessions" -> {
                    current.logins.incrementAndGet()
                    HttpResponse(200, """{"status":"AUTHENTICATED","accountId":"${current.accountId}","accessToken":"ISOLATED_VERIFICATION_ACCESS","refreshToken":"ISOLATED_VERIFICATION_REFRESH","settingsCompleted":false}""")
                }
                else -> error("Unexpected isolated verification request method/path")
            }
        }, object : SessionCredentialStore {
            private var value: StoredSession? = null
            override suspend fun load() = value
            override suspend fun save(session: StoredSession) { value = session }
            override suspend fun clear() { value = null }
        })
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                RlTheme { if (mounted.value) ProductionApp(current.graph) }
            }
        }
        compose.onNodeWithText("REAL LANGUAGE · 登录").assertIsDisplayed()
        tap(compose.onNodeWithText("注册"))
        emailInput().performTextReplacement(FIRST_EMAIL)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("registration-password-row")))
            .performTextReplacement(current.password)
        hideIme()
        tap(compose.onNodeWithTag("birth-year-picker"))
        tap(compose.onNodeWithText("确定")) // real picker default is current month, eighteen years earlier
        val consents = isToggleable() and hasAnyAncestor(hasTestTag("registration-consent-block"))
        compose.onAllNodes(consents).assertCountEquals(2)
        repeat(2) { tap(compose.onAllNodes(consents)[it]) }
        tap(compose.onNodeWithText("提交注册"))
        compose.waitUntil(5_000) { current.pending.size == 1 }
        compose.onNodeWithTag("email-verification-paper-sheet").assertIsDisplayed()
        return current
    }

    private fun emailInput() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("registration-email-row")))
    private fun hideIme() {
        compose.activityRule.scenario.onActivity {
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }
    private fun tap(node: SemanticsNodeInteraction) = node.assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }

    private class StatusResponse(val registrationId: String, continuation: Continuation<HttpResponse>) {
        private val pending = AtomicReference<Continuation<HttpResponse>?>(continuation)
        fun release(status: String) { pending.getAndSet(null)?.resume(HttpResponse(200, """{"status":"$status"}""")) }
    }
    private class Fixture {
        val runtime = SessionRuntime()
        val accountId = UUID.randomUUID().toString()
        val password = UUID.randomUUID().toString()
        val calls = CopyOnWriteArrayList<String>()
        val registrations = CopyOnWriteArrayList<String>()
        val pending = CopyOnWriteArrayList<StatusResponse>()
        val resends = CopyOnWriteArrayList<String>()
        val returnedStatus = AtomicInteger()
        val health = AtomicInteger()
        val logins = AtomicInteger()
        lateinit var graph: ProductionAppGraph
    }
    private companion object {
        const val DATABASE = "learning.db"
        const val FIRST_EMAIL = "verification-entry-one@example.invalid"
        const val SECOND_EMAIL = "verification-entry-two@example.invalid"
    }
}
