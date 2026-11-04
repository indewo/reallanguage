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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
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
import java.io.IOException
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

/** Real ProductionApp -> login -> password-reset routes and real Graph/JSON repositories.
 * No VM lookup, direct coordinator action, real email, credential, HTTP, TTS, or AI is used.
 * The private context also isolates storage eagerly constructed by the production Graph. */
class PasswordResetEntryLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "password-reset-entry-${UUID.randomUUID()}"
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

    @Before fun prepareUuidStorageAndTheRealMainWindow() {
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
        check(directory.mkdir())
        val database = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(database.parentFile == base.cacheDir.canonicalFile && database.name.startsWith("$prefix-"))
        check(!database.exists())
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        assertEquals("Use the ordinary system font for this entry-lifecycle test", 1f,
            compose.activity.resources.configuration.fontScale, .01f)
    }

    @After fun disposeThenRemoveOnlyThisUuidFixture() {
        compose.runOnIdle {
            mounted.value = false
            compose.activity.viewModelStore.clear()
        }
        fixture?.releaseAll()
        compose.waitForIdle()
        val database = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(database.parentFile == base.cacheDir.canonicalFile && database.name.startsWith("$prefix-") && database.extension == "db")
        check(privateDb.deleteDatabase(DATABASE) || !database.exists())
        synchronized(preferences) { preferences.toList() }.forEach { name ->
            check(name.startsWith("$prefix-"))
            base.deleteSharedPreferences(name)
        }
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == prefix)
        check(!directory.exists() || directory.deleteRecursively())
    }

    @Test fun returningDuringARequestReopensAnEditableEntryAndRejectsTheOldUncancellableResponse() {
        val current = render(holdResponses = true)
        openCleanResetEntry()
        editEmail(FIRST_EMAIL)
        tap(compose.onNodeWithText(SEND))
        compose.waitUntil(5_000) { current.pending.size == 1 }
        compose.onNodeWithText(SEND).assertIsNotEnabled()

        returnToLogin()
        openCleanResetEntry()
        editEmail(SECOND_EMAIL)
        tap(compose.onNodeWithText(SEND))
        compose.waitUntil(5_000) { current.pending.size == 2 }
        assertEquals(listOf(FIRST_EMAIL, SECOND_EMAIL), current.sends.map { it.email })

        // This real transport continuation intentionally ignores cancellation. It completes only
        // after the new entry has its own in-flight request; neither VM instance is looked up.
        current.pending[0].release()
        compose.waitUntil(5_000) { current.returnedResponses.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithText(EMAIL_STEP).assertIsDisplayed()
        assertEmail(SECOND_EMAIL)
        compose.onNodeWithText(SEND).assertIsNotEnabled()
        compose.onNodeWithTag("password-reset-waiting").assertDoesNotExist()
        compose.onNodeWithTag("connection-error-sheet").assertDoesNotExist()
        assertEquals(2, current.sends.size)

        current.pending[1].release()
        compose.waitUntil(5_000) { current.returnedResponses.get() == 2 }
        compose.onNodeWithTag("password-reset-waiting").assertIsDisplayed()
        compose.onNodeWithText(SECOND_EMAIL).assertIsDisplayed()
        compose.onNodeWithText(FIRST_EMAIL).assertDoesNotExist()
        compose.onNodeWithTag("password-reset-resend").assertIsEnabled()
        assertEquals(listOf(RESET_CALL, RESET_CALL), current.calls.toList())
        assertAnonymous(current)
    }

    @Test fun reconnectPreservesTheLiveEmailButReturningAfterSendStartsANewCleanEntry() {
        val current = render(holdResponses = false)
        openCleanResetEntry()
        editEmail(FIRST_EMAIL)
        tap(compose.onNodeWithText(SEND))
        compose.waitUntil(5_000) { current.graph.connectionGate.incident.value != null }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithText(FIRST_EMAIL).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()

        tap(compose.onNodeWithTag("connection-error-reconnect"))
        compose.waitUntil(5_000) { current.graph.connectionGate.incident.value == null }
        compose.onNodeWithText(EMAIL_STEP).assertIsDisplayed()
        assertEmail(FIRST_EMAIL)
        compose.onNode(hasSetTextAction()).assertIsEnabled()
        compose.onNodeWithText(SEND).assertIsEnabled()
        assertEquals("Recovery checks connectivity; it does not resend the reset email", 1, current.sends.size)
        assertEquals(listOf(RESET_CALL, HEALTH_CALL), current.calls.toList())

        tap(compose.onNodeWithText(SEND))
        compose.onNodeWithTag("password-reset-waiting").assertIsDisplayed()
        compose.onNodeWithText(FIRST_EMAIL).assertIsDisplayed()
        assertEquals(listOf(FIRST_EMAIL, FIRST_EMAIL), current.sends.map { it.email })
        assertEquals("An explicit retry in the same entry retains the original command",
            current.sends[0].commandId, current.sends[1].commandId)

        // This is the entry-lifecycle extension beyond ProductionAnonymousConnectionTest:
        // acknowledged WAITING state must not survive leaving and opening a new reset entry.
        returnToLogin()
        openCleanResetEntry()
        editEmail(SECOND_EMAIL)
        tap(compose.onNodeWithText(SEND))
        compose.onNodeWithTag("password-reset-waiting").assertIsDisplayed()
        compose.onNodeWithText(SECOND_EMAIL).assertIsDisplayed()
        compose.onNodeWithText(FIRST_EMAIL).assertDoesNotExist()
        assertEquals(listOf(FIRST_EMAIL, FIRST_EMAIL, SECOND_EMAIL), current.sends.map { it.email })
        assertEquals(listOf(RESET_CALL, HEALTH_CALL, RESET_CALL, RESET_CALL), current.calls.toList())
        assertAnonymous(current)
    }

    private fun render(holdResponses: Boolean): Fixture {
        val current = Fixture(holdResponses)
        fixture = current
        current.graph = ProductionAppGraph(context, current.runtime, HttpTransport { request ->
            current.calls += "${request.method} ${request.path}"
            when ("${request.method} ${request.path}") {
                HEALTH_CALL -> HttpResponse(200,
                    """{"status":"ok","service":"api","requestId":"isolated-reset-entry"}""")
                RESET_CALL -> {
                    val body = JSONObject(request.body)
                    val send = Send(body.getString("email"), body.getString("commandId"))
                    check(send.email == FIRST_EMAIL || send.email == SECOND_EMAIL)
                    check(send.commandId.isNotBlank())
                    current.sends += send
                    val ordinal = current.sends.size
                    if (current.holdResponses) {
                        val response = suspendCoroutine<HttpResponse> { continuation ->
                            current.pending += PendingResponse(continuation, ordinal)
                        }
                        current.returnedResponses.incrementAndGet()
                        response
                    } else {
                        if (ordinal == 1) throw IOException("Isolated reset-request connection failure")
                        sent(ordinal)
                    }
                }
                else -> error("Unexpected isolated request method/path")
            }
        }, object : SessionCredentialStore {
            override suspend fun load(): StoredSession? = null
            override suspend fun save(session: StoredSession) = error("This fixture cannot establish a session")
            override suspend fun clear() = Unit
        })
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                RlTheme { if (mounted.value) ProductionApp(current.graph) }
            }
        }
        compose.onNodeWithText(LOGIN).assertIsDisplayed()
        return current
    }

    private fun openCleanResetEntry() {
        tap(compose.onNodeWithText("忘记密码"))
        compose.onNodeWithText(EMAIL_STEP).assertIsDisplayed()
        compose.onNodeWithTag("password-reset-waiting").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsEnabled()
        assertEmail("")
        compose.onNodeWithText(SEND).assertIsEnabled()
    }

    private fun editEmail(value: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(value)
        assertEmail(value)
        // This test measures entry ownership, not IME fit or keyboard quality.
        compose.activityRule.scenario.onActivity {
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }

    private fun assertEmail(value: String) {
        // A sending field remains visible but intentionally has no SetText action.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)),
        )
    }

    private fun returnToLogin() {
        tap(compose.onNodeWithTag("password-reset-return-login"))
        compose.onNodeWithText(LOGIN).assertIsDisplayed()
        compose.onNodeWithTag("password-reset-return-login").assertDoesNotExist()
    }

    private fun tap(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
    }

    private fun assertAnonymous(current: Fixture) {
        assertNull(current.runtime.accountId)
        assertNull(current.runtime.accessToken)
        assertTrue(current.sends.all { it.commandId.isNotBlank() })
    }

    private data class Send(val email: String, val commandId: String)
    private class PendingResponse(continuation: Continuation<HttpResponse>, private val ordinal: Int) {
        private val pending = AtomicReference<Continuation<HttpResponse>?>(continuation)
        fun release() { pending.getAndSet(null)?.resume(sent(ordinal)) }
    }
    private class Fixture(val holdResponses: Boolean) {
        val runtime = SessionRuntime()
        val calls = CopyOnWriteArrayList<String>()
        val sends = CopyOnWriteArrayList<Send>()
        val pending = CopyOnWriteArrayList<PendingResponse>()
        val returnedResponses = AtomicInteger()
        lateinit var graph: ProductionAppGraph
        fun releaseAll() { pending.forEach { it.release() } }
    }
    private companion object {
        const val DATABASE = "learning.db"
        const val FIRST_EMAIL = "first-reset-entry@example.invalid"
        const val SECOND_EMAIL = "second-reset-entry@example.invalid"
        const val EMAIL_STEP = "邮箱确认"
        const val LOGIN = "REAL LANGUAGE · 登录"
        const val SEND = "发送验证邮件"
        const val RESET_CALL = "POST /api/v1/auth/password-resets"
        const val HEALTH_CALL = "GET /api/v1/health"
        fun sent(ordinal: Int) = HttpResponse(200,
            """{"status":"SENT","challengeId":"isolated-entry-challenge-$ordinal"}""")
    }
}
