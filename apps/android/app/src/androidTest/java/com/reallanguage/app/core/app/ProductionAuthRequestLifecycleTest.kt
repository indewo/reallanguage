package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Production UI/Graph/session commits with UUID-private incidental storage and memory HTTP.
 * No VM lookup, coordinator shortcut, real account, email, server, AI, or credential is used.
 * Random test passwords remain in memory; this lifecycle test does not capture screenshots. */
class ProductionAuthRequestLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "auth-request-entry-${UUID.randomUUID()}"
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
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        assertEquals(1f, compose.activity.resources.configuration.fontScale, .01f)
    }

    @After fun disposeThenDeleteOnlyThisUuidStorage() {
        compose.runOnIdle { mounted.value = false; compose.activity.viewModelStore.clear() }
        fixture?.releaseOld()
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

    @Test fun leavingLoginForPasswordResetRejectsLateSessionCommitButANewExplicitLoginWorks() {
        val current = render(holdLogin = true)
        fillLogin(current, OLD_EMAIL)
        tap(compose.onNodeWithText("登录"))
        compose.waitUntil(5_000) { current.pending.get() != null }
        val anonymousEpoch = current.runtime.accountEpoch
        tap(compose.onNodeWithText("忘记密码"))
        compose.onNodeWithText("邮箱确认").assertIsDisplayed()
        current.releaseOld()
        compose.waitUntil(5_000) { current.oldReturned.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithText("邮箱确认").assertIsDisplayed()
        compose.onNodeWithTag("password-reset-return-login").assertIsDisplayed()
        assertNoSession(current)
        assertEquals(anonymousEpoch, current.runtime.accountEpoch)

        tap(compose.onNodeWithTag("password-reset-return-login"))
        compose.onNodeWithText(LOGIN_TITLE).assertIsDisplayed()
        fillLogin(current, NEW_EMAIL)
        tap(compose.onNodeWithText("登录"))
        assertNewSessionAndUsableSettings(current)
        assertEquals(listOf(OLD_EMAIL, NEW_EMAIL), current.loginEmails.toList())
        assertEquals(listOf(LOGIN_CALL, LOGIN_CALL), current.calls.toList())
    }

    @Test fun returningFromPendingRegistrationIgnoresItsLateVerificationAndKeepsLoginUsable() {
        val current = render(holdLogin = false)
        tap(compose.onNodeWithText("注册"))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("registration-email-row")))
            .performTextReplacement(OLD_EMAIL)
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("registration-password-row")))
            .performTextReplacement(current.password)
        hideIme()
        tap(compose.onNodeWithTag("birth-year-picker"))
        tap(compose.onNodeWithText("确定")) // actual picker confirms its eighteen-year-old default
        val consents = isToggleable() and hasAnyAncestor(hasTestTag("registration-consent-block"))
        compose.onAllNodes(consents).assertCountEquals(2)
        repeat(2) { tap(compose.onAllNodes(consents)[it]) }
        tap(compose.onNodeWithText("提交注册"))
        compose.waitUntil(5_000) { current.pending.get() != null }
        tap(compose.onNodeWithText("返回登录"))
        compose.onNodeWithText(LOGIN_TITLE).assertIsDisplayed()
        current.releaseOld()
        compose.waitUntil(5_000) { current.oldReturned.get() == 1 }
        compose.waitForIdle()
        compose.onNodeWithText(LOGIN_TITLE).assertIsDisplayed()
        compose.onNodeWithTag("email-verification-paper-sheet").assertDoesNotExist()
        assertNoSession(current)

        fillLogin(current, NEW_EMAIL)
        tap(compose.onNodeWithText("登录"))
        assertNewSessionAndUsableSettings(current)
        assertEquals(listOf(NEW_EMAIL), current.loginEmails.toList())
        assertEquals(listOf(REGISTRATION_CALL, LOGIN_CALL), current.calls.toList())
    }

    private fun render(holdLogin: Boolean): Fixture {
        val current = Fixture(holdLogin)
        fixture = current
        current.graph = ProductionAppGraph(context, current.runtime, HttpTransport { request ->
            val call = "${request.method} ${request.path}"
            current.calls += call
            check(call == LOGIN_CALL || call == REGISTRATION_CALL)
            val body = JSONObject(request.body)
            check(body.getString("password") == current.password)
            when (call) {
                LOGIN_CALL -> {
                    current.loginEmails += body.getString("email")
                    if (holdLogin && current.loginEmails.size == 1) current.awaitOld()
                    else current.authenticated(current.newAccount)
                }
                else -> {
                    check(!holdLogin && body.getString("email") == OLD_EMAIL)
                    check(body.getBoolean("acceptedTerms") && body.getBoolean("acceptedPrivacyPolicy"))
                    check(!YearMonth.parse(body.getString("birthYearMonth")).isAfter(YearMonth.now().minusYears(14)))
                    current.awaitOld()
                }
            }
        }, current.credentials)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                RlTheme { if (mounted.value) ProductionApp(current.graph) }
            }
        }
        compose.onNodeWithText(LOGIN_TITLE).assertIsDisplayed()
        return current
    }

    private fun fillLogin(current: Fixture, email: String) {
        compose.onNode(hasSetTextAction() and hasText("邮箱")).assertIsEnabled().performTextReplacement(email)
        compose.onNode(hasSetTextAction() and hasText("密码")).assertIsEnabled().performTextReplacement(current.password)
        hideIme()
    }
    private fun assertNoSession(current: Fixture) {
        assertEquals(0, current.credentials.saved.size)
        assertNull(runBlocking { current.credentials.load() })
        assertNull(current.runtime.accountId)
        assertNull(current.runtime.accessToken)
    }
    private fun assertNewSessionAndUsableSettings(current: Fixture) {
        compose.waitUntil(5_000) { current.runtime.accountId == current.newAccount }
        compose.onNodeWithTag("settings-expression-paper-sheet").assertIsDisplayed()
        val expected = StoredSession(current.newAccount, "ISOLATED_NEW_REFRESH", false)
        assertEquals(listOf(expected), current.credentials.saved.toList())
        assertEquals(expected, runBlocking { current.credentials.load() })
        assertEquals("ISOLATED_NEW_ACCESS", current.runtime.accessToken)
        val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnyAncestor(hasTestTag("settings-formality-scale"))
        compose.onNode(slider, useUnmergedTree = true).performTouchInput { click(Offset(width * .78f, height * .5f)) }
        tap(compose.onNodeWithTag("settings-slang-disabled"))
        tap(compose.onNodeWithTag("settings-vocabulary-moderate"))
        compose.onNodeWithTag("settings-continue-action").assertIsDisplayed().assertIsEnabled()
        assertTrue(current.runtime.accountEpoch > 0)
    }
    private fun hideIme() {
        compose.activityRule.scenario.onActivity {
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }
    private fun tap(node: SemanticsNodeInteraction) = node.assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }

    private class Credentials : SessionCredentialStore {
        @Volatile private var value: StoredSession? = null
        val saved = CopyOnWriteArrayList<StoredSession>()
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { saved += session; value = session }
        override suspend fun clear() { value = null }
    }
    private class Fixture(private val holdLogin: Boolean) {
        val runtime = SessionRuntime()
        val credentials = Credentials()
        val oldAccount = UUID.randomUUID().toString()
        val newAccount = UUID.randomUUID().toString()
        val password = UUID.randomUUID().toString()
        val calls = CopyOnWriteArrayList<String>()
        val loginEmails = CopyOnWriteArrayList<String>()
        val pending = AtomicReference<Continuation<HttpResponse>?>(null)
        val oldReturned = AtomicInteger()
        lateinit var graph: ProductionAppGraph
        suspend fun awaitOld(): HttpResponse {
            val response = suspendCoroutine<HttpResponse> { check(pending.compareAndSet(null, it)) }
            oldReturned.incrementAndGet()
            return response
        }
        fun releaseOld() { pending.getAndSet(null)?.resume(if (holdLogin) authenticated(oldAccount)
            else HttpResponse(200, """{"status":"EMAIL_VERIFICATION_REQUIRED","registrationId":"isolated-departed-registration"}""")) }
        fun authenticated(account: String): HttpResponse {
            val identity = if (account == newAccount) "NEW" else "OLD"
            return HttpResponse(200,
                """{"status":"AUTHENTICATED","accountId":"$account","accessToken":"ISOLATED_${identity}_ACCESS","refreshToken":"ISOLATED_${identity}_REFRESH","settingsCompleted":false}""")
        }
    }
    private companion object {
        const val DATABASE = "learning.db"
        const val OLD_EMAIL = "old-auth-entry@example.invalid"
        const val NEW_EMAIL = "new-auth-entry@example.invalid"
        const val LOGIN_TITLE = "REAL LANGUAGE · 登录"
        const val LOGIN_CALL = "POST /api/v1/auth/sessions"
        const val REGISTRATION_CALL = "POST /api/v1/auth/registrations"
    }
}
