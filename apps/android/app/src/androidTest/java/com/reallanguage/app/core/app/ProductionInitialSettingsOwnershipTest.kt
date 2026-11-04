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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.navigation.AppCoordinatorAction
import com.reallanguage.app.core.navigation.AppCoordinatorViewModel
import com.reallanguage.app.core.navigation.AppRoute
import com.reallanguage.app.core.navigation.AppStartupResult
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.auth.domain.LoginCommand
import com.reallanguage.app.feature.auth.domain.LoginResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
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

/** Real ProductionApp/Graph/coordinator and on-screen inputs, not an Activity-owned setup VM lookup.
 * All HTTP and stored sessions are memory fixtures. Any incidental Graph storage is UUID-private;
 * no user database, actual login, real server, AI, or password rendering is involved. */
class ProductionInitialSettingsOwnershipTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val prefix = "initial-settings-owner-$runId"
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
    private var appCoordinator: AppCoordinatorViewModel? = null

    @Before fun preparePrivateStorageAndTheRealMainWindowPolicy() {
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
        check(directory.mkdir())
        val databaseFile = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(databaseFile.parentFile == base.cacheDir.canonicalFile && databaseFile.name.startsWith("$prefix-"))
        check(!databaseFile.exists())
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        assertEquals("Run this ownership fixture at the ordinary system font", 1f,
            compose.activity.resources.configuration.fontScale, .01f)
    }

    @After fun disposeTheTestEntryAndRemoveOnlyThisUuidStorage() {
        fixture?.releaseSave()
        compose.runOnIdle {
            mounted.value = false
            compose.activity.viewModelStore.clear()
        }
        compose.waitForIdle()
        val databaseFile = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(databaseFile.parentFile == base.cacheDir.canonicalFile && databaseFile.name.startsWith("$prefix-") && databaseFile.extension == "db")
        check(privateDb.deleteDatabase(DATABASE) || !databaseFile.exists())
        synchronized(preferences) { preferences.toList() }.forEach { name ->
            check(name.startsWith("$prefix-"))
            base.deleteSharedPreferences(name)
        }
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == prefix)
        check(!directory.exists() || directory.deleteRecursively())
    }

    @Test fun realLoginAsAnotherAccountRestartsSetupAtAnEmptyFirstStep() = assertNewLoginClearsDraft(sameAccount = false)

    @Test fun realLoginAsTheSameAccountStartsANewSetupSessionWithoutOldPreferences() = assertNewLoginClearsDraft(sameAccount = true)

    @Test fun lateSavedResponseForAccountACannotOpenHomeOrPopulateAccountBsSetup() = assertLateSaveCannotEscape(sameAccount = false)

    @Test fun lateSavedResponseForAnOlderSameAccountSessionCannotEscapeNewSetup() = assertLateSaveCannotEscape(sameAccount = true)

    @Test fun sameOwnerRefreshKeepsTheCurrentPreferencesAndDoesNotSaveThem() {
        val current = render()
        enterPreferencesAndType()
        val epoch = current.runtime.accountEpoch
        assertEquals(AppStartupResult.SignedIn(false), runBlocking { current.graph.resolveStartupForApp() })
        compose.waitForIdle()
        assertEquals(epoch, current.runtime.accountEpoch)
        assertEquals(AppRoute.InitialSettings, coordinator().uiState.value.route)
        compose.onNodeWithTag("settings-preferences-editorial-intro").assertIsDisplayed()
        compose.onNodeWithTag("settings-style-reference-input").assertTextEquals(STYLE)
        assertEquals(0, current.settingsRequests.get())
        assertEquals(listOf("POST /api/v1/auth/sessions/refresh", "POST /api/v1/auth/sessions/refresh"), current.calls.toList())
    }

    @Test fun reconnectOfIncompleteSettingsKeepsTheActualStepAndNeverSubmitsOrOpensHome() {
        val current = render()
        enterPreferencesAndType()
        compose.runOnIdle { current.graph.connectionGate.networkLost() }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings-style-reference-input").assertDoesNotExist()
        tap("connection-error-reconnect")
        compose.waitUntil(5_000) { current.graph.connectionGate.incident.value == null }
        compose.onNodeWithTag("settings-style-reference-input").assertTextEquals(STYLE)
        assertEquals(AppRoute.InitialSettings, coordinator().uiState.value.route)
        assertEquals(0, current.settingsRequests.get())
        assertEquals(listOf("POST /api/v1/auth/sessions/refresh", "GET /api/v1/health", "POST /api/v1/auth/sessions/refresh"), current.calls.toList())
    }

    @Test fun expiredSettingsSessionReturnsToAnAccessibleLoginWithoutOldContentOrAnotherRefresh() {
        val current = render()
        enterPreferencesAndType()
        current.refreshStatus = 401
        compose.runOnIdle { current.graph.connectionGate.networkLost() }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        tap("connection-error-reconnect")
        compose.waitUntil(5_000) { coordinator().uiState.value.route == AppRoute.Login }
        compose.onNodeWithText("REAL LANGUAGE · 登录").assertIsDisplayed()
        compose.onNodeWithTag("connection-error-sheet").assertDoesNotExist()
        compose.onNodeWithTag("settings-style-reference-input").assertDoesNotExist()
        assertNull(current.credentials.value)
        assertNull(current.runtime.accountId)
        assertEquals(listOf("POST /api/v1/auth/sessions/refresh", "GET /api/v1/health", "POST /api/v1/auth/sessions/refresh"), current.calls.toList())
    }

    private fun assertNewLoginClearsDraft(sameAccount: Boolean) {
        val current = render()
        enterPreferencesAndType()
        loginAndRouteToSetup(current, sameAccount)
        assertCleanFirstStepAndBlankPreferences()
        assertEquals(0, current.settingsRequests.get())
        assertEquals(listOf("POST /api/v1/auth/sessions/refresh", "POST /api/v1/auth/sessions"), current.calls.toList())
    }

    private fun assertLateSaveCannotEscape(sameAccount: Boolean) {
        val current = render()
        enterPreferencesAndType()
        tap("settings-continue-action")
        compose.onNodeWithTag("settings-voice-editorial-intro").assertIsDisplayed()
        tap("settings-voice-toggle")
        tap("settings-autoplay-toggle")
        adjustSlider("settings-speech-speed")
        compose.onNodeWithText("完成设置").assertIsDisplayed()
        tap("settings-continue-action")
        compose.waitUntil(5_000) { current.pendingSave.get() != null }
        assertEquals(1, current.settingsRequests.get())
        compose.onNodeWithTag("settings-continue-action").assertIsNotEnabled()
        try {
            loginAndRouteToSetup(current, sameAccount)
        } finally {
            // The old transport deliberately ignores cancellation, so post-response ownership is real.
            current.releaseSave()
        }
        compose.waitUntil(5_000) { current.saveResponses.get() == 1 }
        compose.waitForIdle()
        assertEquals("A late settings acknowledgement must not navigate the new session", AppRoute.InitialSettings,
            coordinator().uiState.value.route)
        assertCleanFirstStepAndBlankPreferences()
        assertEquals(1, current.settingsRequests.get())
        assertEquals(listOf("POST /api/v1/auth/sessions/refresh", "PUT /api/v1/settings/current/initial",
            "POST /api/v1/auth/sessions"), current.calls.toList())
    }

    private fun enterPreferencesAndType() {
        completeBasicsFromTheScreen()
        tap("settings-purpose-gre")
        compose.onNodeWithTag("settings-style-reference-input").assertIsDisplayed().performTextReplacement(STYLE)
        compose.onNodeWithTag("settings-style-reference-input").assertTextEquals(STYLE)
        // This is not an IME fit test; keep the ordinary viewport for the remaining pointer actions.
        compose.activityRule.scenario.onActivity {
            it.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }

    private fun completeBasicsFromTheScreen() {
        compose.onNodeWithTag("settings-expression-paper-sheet").assertIsDisplayed()
        adjustSlider("settings-formality-scale")
        tap("settings-slang-disabled")
        tap("settings-vocabulary-moderate")
        tap("settings-continue-action")
        compose.onNodeWithTag("settings-preferences-editorial-intro").assertIsDisplayed()
    }

    private fun assertCleanFirstStepAndBlankPreferences() {
        compose.onNodeWithTag("settings-expression-paper-sheet").assertIsDisplayed()
        compose.onNodeWithTag("settings-style-reference-input").assertDoesNotExist()
        compose.onNodeWithTag("settings-continue-action").assertIsNotEnabled()
        val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and
            hasAnyAncestor(hasTestTag("settings-formality-scale"))
        assertEquals("The previous formality selection must not survive", 3f,
            compose.onNode(slider, useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo].current, .01f)
        completeBasicsFromTheScreen()
        compose.onNodeWithTag("settings-style-reference-input").assertTextEquals("")
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
    }

    private fun adjustSlider(container: String) {
        val slider = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnyAncestor(hasTestTag(container))
        compose.onNode(slider, useUnmergedTree = true).assertIsDisplayed().performTouchInput {
            click(Offset(width * .78f, height * .5f))
        }
    }

    private fun loginAndRouteToSetup(current: Fixture, sameAccount: Boolean) {
        val epoch = current.runtime.accountEpoch
        val destination = if (sameAccount) current.accountA else current.accountB
        current.loginAccount.set(destination)
        val result = runBlocking {
            current.graph.loginForApp(LoginCommand("setup-fixture@example.invalid", "ISOLATED_TEST_PASSWORD"))
        }
        assertTrue(result is LoginResult.Authenticated && !result.settingsCompleted)
        assertEquals(destination, current.runtime.accountId)
        assertEquals(destination, current.credentials.value?.accountId)
        assertTrue("Real login, including same-account login, must advance the epoch", current.runtime.accountEpoch > epoch)
        compose.runOnIdle { coordinator().onAction(AppCoordinatorAction.Authenticated(false)) }
        compose.waitForIdle()
    }

    private fun coordinator(): AppCoordinatorViewModel = checkNotNull(appCoordinator)

    private fun render(): Fixture {
        val current = Fixture().also { fixture = it }
        current.graph = ProductionAppGraph(context, current.runtime, HttpTransport { request ->
            current.calls.add("${request.method} ${request.path}")
            when (request.path) {
                "/api/v1/auth/sessions/refresh" -> {
                    check(request.method == "POST")
                    if (current.refreshStatus == 401) HttpResponse(401, "{}") else current.sessionResponse(checkNotNull(current.credentials.value).accountId)
                }
                "/api/v1/health" -> HttpResponse(200, """{"status":"ok","service":"api","requestId":"isolated-settings-recovery"}""")
                "/api/v1/auth/sessions" -> {
                    check(request.method == "POST")
                    current.sessionResponse(current.loginAccount.get())
                }
                "/api/v1/settings/current/initial" -> {
                    check(request.method == "PUT")
                    assertEquals("Bearer ${current.access(current.accountA)}", request.headers["Authorization"])
                    val payload = JSONObject(request.body)
                    assertEquals(STYLE, payload.getString("styleReference"))
                    assertEquals("CET_6", payload.getString("basicVocabulary"))
                    assertEquals("GRE", payload.getJSONArray("purposeVocabularies").getString(0))
                    current.settingsRequests.incrementAndGet()
                    val response = suspendCoroutine<HttpResponse> { continuation ->
                        check(current.pendingSave.compareAndSet(null, continuation))
                    }
                    current.saveResponses.incrementAndGet()
                    response
                }
                else -> HttpResponse(503, "{}") // Fail closed; the final budget rejects every extra operation.
            }
        }, current.credentials)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                RlTheme { if (mounted.value) ProductionApp(current.graph) }
            }
        }
        compose.waitUntil(5_000) { current.calls.isNotEmpty() && current.runtime.accessToken == current.access(current.accountA) }
        compose.waitForIdle()
        compose.runOnIdle {
            appCoordinator = ViewModelProvider(compose.activity, current.graph.coordinatorFactory)[AppCoordinatorViewModel::class.java]
        }
        assertEquals(AppRoute.InitialSettings, coordinator().uiState.value.route)
        assertNull(current.graph.connectionGate.incident.value)
        return current
    }

    private inner class Fixture {
        val accountA = "$prefix-a"
        val accountB = "$prefix-b"
        val runtime = SessionRuntime().apply { update("ISOLATED_START_ACCESS", accountA, newSession = true) }
        val credentials = MemoryCredentials(StoredSession(accountA, "ISOLATED_START_REFRESH", false))
        val loginAccount = AtomicReference(accountA)
        val calls = CopyOnWriteArrayList<String>()
        val settingsRequests = AtomicInteger()
        val saveResponses = AtomicInteger()
        val pendingSave = AtomicReference<Continuation<HttpResponse>?>(null)
        @Volatile var refreshStatus = 200
        lateinit var graph: ProductionAppGraph
        fun access(account: String) = "ISOLATED_ACCESS_$account"
        fun sessionResponse(account: String) = HttpResponse(200, JSONObject()
            .put("status", "AUTHENTICATED").put("accountId", account).put("accessToken", access(account))
            .put("refreshToken", "ISOLATED_REFRESH_$account").put("settingsCompleted", false).toString())
        fun releaseSave() { pendingSave.getAndSet(null)?.resume(HttpResponse(200, """{"status":"COMPLETED"}""")) }
    }

    private class MemoryCredentials(@Volatile var value: StoredSession?) : SessionCredentialStore {
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }

    private companion object {
        const val DATABASE = "real-language-learning.db"
        const val STYLE = "隔离设置草稿：清晰直接，不属于下一次登录。"
    }
}
