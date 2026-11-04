package com.reallanguage.app.core.app

import android.Manifest
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.testing.ReviewNoJudgmentFixture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual Pattern review Graph/route/launcher and UUID-private SQLite.
 * Capture, permission delivery and HTTP are doubles: no microphone, service, real audio or AI evidence.
 * Connection cover is not confirmed review exit; existing exit-control tests cover chapter disposal. */
class ProductionReviewPatternCaptureConnectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = ReviewNoJudgmentFixture(base)
    private var localPermission = PackageManager.PERMISSION_DENIED
    private val microphoneChecks = AtomicInteger()
    private val context = object : ContextWrapper(fixture.context) {
        override fun getApplicationContext() = this
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.RECORD_AUDIO) {
                microphoneChecks.incrementAndGet()
                localPermission
            } else super.checkPermission(permission, pid, uid)
    }
    private val mounted = mutableStateOf(false)
    private var contentSet = false
    private var originalSystemPermission: Int? = null
    private val registry = PendingPermissionRegistry()
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }
    private val credentials = MemoryCredentials(StoredSession(fixture.account, "ISOLATED_REFRESH", true))
    private val calls = CopyOnWriteArrayList<String>()
    private val failure = AtomicReference<Throwable?>()
    private val active = AtomicBoolean()
    private val starts = AtomicInteger()
    private val finishes = AtomicInteger()
    private val liveCancels = AtomicInteger()
    private val captureCreations = AtomicInteger()
    private lateinit var graph: ProductionAppGraph

    @Before fun prepareOnlyTheTestActivity() {
        originalSystemPermission = ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO)
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @After fun disposeAndDeleteOnlyTheUuidFixture() {
        if (contentSet) {
            compose.runOnIdle { mounted.value = false; compose.activity.viewModelStore.clear() }
            compose.waitForIdle()
        }
        fixture.close()
        originalSystemPermission?.let {
            assertEquals("Platform microphone permission must remain unchanged", it,
                ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO))
        }
    }

    @Test fun liveReviewCaptureStopsUnderCoverWithoutFinishingAndRequiresExplicitRestart() {
        mount(permissionGranted = true)
        val formalBefore = awaitIo { fixture.formalSnapshot() }
        val lease = requireNotNull(graph.currentPatternReviewEntry())
        tapText("开始回应")
        compose.waitUntil(5_000) { active.get() }
        assertEquals(1, starts.get())
        compose.onNodeWithText("说完了").assertIsDisplayed()

        loseConnection()
        assertFalse("A hidden review must not keep an unsubmitted microphone live", active.get())
        assertEquals(1, liveCancels.get())
        assertNoSubmission(formalBefore)
        assertSame(lease, graph.currentPatternReviewEntry())

        reconnect()
        assertEquals("Recovery cannot restart recording", 1, starts.get())
        assertFalse(active.get())
        assertSame(lease, graph.currentPatternReviewEntry())
        tapText("开始回应")
        compose.waitUntil(5_000) { active.get() }
        assertEquals(2, starts.get())
        assertTrue(registry.launches.isEmpty())
        assertNoSubmission(formalBefore)
        assertEquals(listOf(GENERATE, HEALTH, REFRESH), calls.toList())
    }

    @Test fun preCoverPermissionGrantCannotStartAfterRecoveryButANewExplicitGrantCan() {
        mount(permissionGranted = false)
        val formalBefore = awaitIo { fixture.formalSnapshot() }
        val lease = requireNotNull(graph.currentPatternReviewEntry())
        requestPermission(expectedLaunches = 1)
        loseConnection()
        assertEquals(0, starts.get())
        reconnect()
        assertSame(lease, graph.currentPatternReviewEntry())
        assertEquals(1, registry.launches.size)
        assertEquals(0, starts.get())

        compose.runOnIdle { registry.grant(0) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals("An old permission interaction cannot survive connection recovery", 0, starts.get())
        assertFalse(active.get())
        assertNoSubmission(formalBefore)

        requestPermission(expectedLaunches = 2)
        assertEquals(0, starts.get())
        compose.runOnIdle { registry.grant(1) }
        compose.waitUntil(5_000) { active.get() }
        assertEquals(1, starts.get())
        assertNoSubmission(formalBefore)
        assertEquals(listOf(GENERATE, HEALTH, REFRESH), calls.toList())
    }

    private fun mount(permissionGranted: Boolean) {
        localPermission = if (permissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        val reviewId = awaitIo {
            fixture.seed()
            // This fixture's abstract structure cannot use the reader's legacy
            // literal fallback. Supply the exact, preaccepted fixed-source span.
            val evidence = JSONObject().put("version", 1).put("startUtf16", 2).put("endUtf16", 14)
                .put("surfaceForm", "used to walk").put("normalizedForm", "used to + verb")
                .put("allowedForms", JSONArray().put("used to walk"))
            assertEquals(1, fixture.db.update("target_selections", ContentValues().apply {
                put("source_evidence", evidence.toString())
            }, "account_id=? and chapter_id='chapter1' and target_id='target1'", arrayOf(fixture.account)))
            fixture.queue().single().reviewChapterId
        }
        graph = ProductionAppGraph(context, fixture.runtime, HttpTransport { request ->
            try { respond(request) } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Throwable) { failure.compareAndSet(null, problem); HttpResponse(500, "{}") }
        }, credentials, reviewPatternCaptureFactory = { _, owner ->
            check(owner === fixture.runtime && captureCreations.incrementAndGet() == 1)
            object : ReadSpeakAudioCapture {
                override fun start(): Boolean { check(active.compareAndSet(false, true)); starts.incrementAndGet(); return true }
                override fun finish(): CapturedReadSpeakAudio? { finishes.incrementAndGet(); active.set(false); return null }
                override fun cancel() { if (active.compareAndSet(true, false)) liveCancels.incrementAndGet() }
            }
        })
        assertNotNull("The UUID seed must pass the actual fixed-pattern reader before UI entry",
            awaitIo { graph.reviewPattern("chapter1", "sentence1", "target1") })
        mounted.value = true
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides registryOwner) {
                RlTheme { RlPaperBackground {
                    if (mounted.value) HomeProductionFlow(graph,
                        initialRoute = HomeDiaryRoute.ReviewChapter(reviewId, "capture-${fixture.runId}"))
                } }
            }
        }
        contentSet = true
        compose.waitUntil(10_000) { healthy(); compose.onAllNodesWithTag("review-chapter-continue").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("review-chapter-continue").assertIsDisplayed().assertIsEnabled().performClick()
        waitReady()
        assertEquals(1, captureCreations.get())
        assertEquals(listOf(GENERATE), calls.toList())
    }

    private fun loseConnection() {
        compose.runOnIdle { graph.connectionGate.networkLost() }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithText("句式独立回应").assertDoesNotExist()
        compose.waitForIdle()
    }
    private fun reconnect() {
        val epoch = fixture.runtime.accountEpoch
        compose.onNodeWithTag("connection-error-reconnect").assertIsDisplayed().performTouchInput { click(center) }
        compose.waitUntil(10_000) { healthy(); graph.connectionGate.incident.value == null }
        waitReady()
        assertEquals(fixture.account, fixture.runtime.accountId)
        assertEquals(epoch, fixture.runtime.accountEpoch)
    }
    private fun requestPermission(expectedLaunches: Int) {
        val before = microphoneChecks.get()
        tapText("开始回应")
        compose.waitUntil(5_000) { healthy(); registry.launches.size == expectedLaunches }
        assertTrue(microphoneChecks.get() > before)
        assertFalse(active.get())
    }
    private fun waitReady() {
        try {
            compose.waitUntil(10_000) { healthy(); captureCreations.get() == 1 && GENERATE in calls }
            compose.waitUntil(10_000) { healthy(); hasText("开始回应") || hasText("说完了") }
        } catch (problem: Throwable) {
            throw AssertionError("Isolated Pattern entry not ready: captureCreations=${captureCreations.get()}, " +
                "calls=${calls.toList()}, cover=${graph.connectionGate.incident.value != null}, " +
                "chapter=${hasTag("review-chapter-continue")}, independent=${hasText("句式独立回应")}, " +
                "failed=${hasText("本次处理失败，未产生有效结果。")}, prompt=${hasText("What changed?")}", problem)
        }
        compose.waitForIdle()
    }
    private fun hasText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun hasTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun tapText(text: String) {
        val node = compose.onNodeWithText(text)
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
    }
    private fun assertNoSubmission(formalBefore: List<List<String?>>) {
        healthy()
        assertEquals(0, finishes.get())
        assertFalse(calls.any { it.contains("pattern-independent-evaluation") })
        assertEquals(formalBefore, awaitIo { fixture.formalSnapshot() })
        assertEquals(0, awaitIo { fixture.count("review_working_state") })
        assertEquals(0, awaitIo { fixture.count("review_chapter_results") })
    }
    private fun respond(request: HttpRequest): HttpResponse {
        val call = "${request.method} ${request.path}"
        check(call in listOf(GENERATE, HEALTH, REFRESH) && call !in calls) { "Unexpected fixture HTTP: $call" }
        calls += call
        if (call == HEALTH) return HttpResponse(200, """{"status":"ok","service":"api","requestId":"isolated-review"}""")
        if (call == REFRESH) return HttpResponse(200, """{"status":"AUTHENTICATED","accountId":"${fixture.account}","accessToken":"ISOLATED_ROTATED_ACCESS","refreshToken":"ISOLATED_ROTATED_REFRESH","settingsCompleted":true}""")
        val body = JSONObject(request.body)
        check(body.getString("chapterId") == "chapter1" && body.getString("patternId") == "target1")
        check(body.getString("sourceSentenceId") == "sentence1" && body.getString("commandId").isNotBlank())
        return HttpResponse(200, """{"status":"READY","patternId":"target1","examples":[{"exampleId":"example","sceneZh":"过去的习惯","aiPromptEn":"What changed?","targetResponseEn":"I used to walk.","targetResponseZh":"我以前常走路。"}]}""")
    }
    private fun healthy() { failure.get()?.let { throw AssertionError("Isolated review fixture contract failed", it) } }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try { compose.waitUntil(15_000) { healthy(); result.get() != null }; return requireNotNull(result.get()).getOrThrow() }
        finally { job.cancel(); scope.cancel() }
    }
    private class PendingPermissionRegistry : ActivityResultRegistry() {
        val launches = CopyOnWriteArrayList<Int>()
        private val delivered = mutableSetOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.RequestPermission && input == Manifest.permission.RECORD_AUDIO)
            launches += requestCode
        }
        fun grant(index: Int) {
            check(index in launches.indices && delivered.add(index))
            check(dispatchResult(launches[index], true)) { "The actual review launcher must still be registered" }
        }
    }
    private class MemoryCredentials(var value: StoredSession?) : SessionCredentialStore {
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }
    private companion object {
        const val GENERATE = "POST /api/v1/learning/pattern-practice-sets"
        const val HEALTH = "GET /api/v1/health"
        const val REFRESH = "POST /api/v1/auth/sessions/refresh"
    }
}
