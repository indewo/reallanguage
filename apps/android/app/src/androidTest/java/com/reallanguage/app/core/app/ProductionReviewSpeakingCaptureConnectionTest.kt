package com.reallanguage.app.core.app

import android.Manifest
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
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
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.learning.data.AndroidSavedSceneTaskReader
import com.reallanguage.app.feature.learning.data.AndroidSentenceSpeaker
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.data.SentenceSpeechEngine
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import com.reallanguage.app.feature.review.data.AndroidReviewChapterRepository
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult
import com.reallanguage.app.testing.ReviewNoJudgmentFixture
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real review chapter -> Full/Scene route, permission launcher and UUID-private SQLite.
 * Only capture/permission delivery/HTTP/speech are doubles; no audio, AI or actual system permission changes.
 * These four tests never submit an attempt or claim an independent learning judgment. */
class ProductionReviewSpeakingCaptureConnectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = ReviewNoJudgmentFixture(base)
    private var localPermission = PackageManager.PERMISSION_DENIED
    private var originalSystemPermission: Int? = null
    private val microphoneChecks = AtomicInteger()
    private val context = object : ContextWrapper(fixture.context) {
        override fun getApplicationContext() = this
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.RECORD_AUDIO) {
                microphoneChecks.incrementAndGet()
                localPermission
            } else super.checkPermission(permission, pid, uid)
    }
    private val registry = PendingPermissionRegistry()
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }
    private val mounted = mutableStateOf(false)
    private var contentSet = false
    private val credentials = MemoryCredentials(StoredSession(fixture.account, "ISOLATED_REFRESH", true))
    private val calls = CopyOnWriteArrayList<String>()
    private val failure = AtomicReference<Throwable?>()
    private val active = AtomicBoolean()
    private val starts = AtomicInteger()
    private val finishes = AtomicInteger()
    private val liveCancels = AtomicInteger()
    private val captureCreations = AtomicInteger()
    private lateinit var module: Module
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
            assertEquals("The real device permission must remain unchanged", it,
                ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO))
        }
    }

    @Test fun fullReviewLiveCaptureStopsUnderCoverAndNeedsExplicitRestart() = liveCapture(Module.FULL)
    @Test fun sceneReviewLiveCaptureStopsUnderCoverAndNeedsExplicitRestart() = liveCapture(Module.SCENE)
    @Test fun fullReviewRejectsPreCoverPermissionGrantAfterReconnect() = stalePermission(Module.FULL)
    @Test fun sceneReviewRejectsPreCoverPermissionGrantAfterReconnect() = stalePermission(Module.SCENE)

    private fun liveCapture(selected: Module) {
        mount(selected, permissionGranted = true)
        val before = awaitIo { protectedRows() }
        tapStart()
        compose.waitUntil(5_000) { active.get() }
        assertEquals(1, starts.get())
        loseConnection()
        assertFalse("The covered ${module.name} review cannot leave an unsubmitted microphone live", active.get())
        assertEquals(1, liveCancels.get())
        assertNoSubmission(before)

        reconnect()
        assertFalse(active.get())
        assertEquals("Connection recovery is not a new recording action", 1, starts.get())
        // Scene's existing capture-failure Retry clears the failure first; it does not start a mic.
        if (module == Module.SCENE) {
            tapLabel(R.string.read_speak_retry)
            assertEquals(1, starts.get())
        }
        tapStart()
        compose.waitUntil(5_000) { active.get() }
        assertEquals(2, starts.get())
        assertEquals(1, liveCancels.get())
        assertTrue(registry.launches.isEmpty())
        assertNoSubmission(before)
    }

    private fun stalePermission(selected: Module) {
        mount(selected, permissionGranted = false)
        val before = awaitIo { protectedRows() }
        requestPermission(1)
        loseConnection()
        assertEquals(0, starts.get())
        reconnect()
        assertEquals(1, registry.launches.size)
        assertEquals(0, starts.get())

        compose.runOnIdle { registry.grant(0) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals("An old permission interaction cannot revive after the connection cover", 0, starts.get())
        assertFalse(active.get())
        assertNoSubmission(before)

        requestPermission(2)
        assertEquals(0, starts.get())
        compose.runOnIdle { registry.grant(1) }
        compose.waitUntil(5_000) { active.get() }
        assertEquals(1, starts.get())
        assertEquals(0, liveCancels.get())
        assertNoSubmission(before)
    }

    private fun mount(selected: Module, permissionGranted: Boolean) {
        module = selected
        localPermission = if (permissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        val reviewId = awaitIo { seedAndCheckReview() }
        graph = ProductionAppGraph(context, fixture.runtime, HttpTransport { request ->
            try { respond(request) } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Throwable) { failure.compareAndSet(null, problem); HttpResponse(500, "{}") }
        }, credentials,
            fullSentenceSpeakingCaptureFactory = { _, owner -> check(module == Module.FULL); newCapture(owner) },
            sceneResponseCaptureFactory = { _, owner -> check(module == Module.SCENE); newCapture(owner) })
        awaitIo {
            val fixed = graph.loadReviewFixedChapter("chapter1") as? FixedChapterLoadResult.Loaded
            assertNotNull("The isolated fixed chapter must pass the production reader", fixed)
            assertEquals(ENGLISH, requireNotNull(fixed).chapter.sentences.single().textEn)
            if (module == Module.SCENE) assertEquals(listOf(task),
                AndroidSavedSceneTaskReader(context, fixture.runtime).read(fixed.chapter))
        }
        mounted.value = true
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides registryOwner,
                LocalSentenceSpeakerFactory provides { _, allowed -> AndroidSentenceSpeaker(SilentSpeech(), allowed) },
            ) { RlTheme { RlPaperBackground {
                if (mounted.value) HomeProductionFlow(graph,
                    initialRoute = HomeDiaryRoute.ReviewChapter(reviewId, "review-capture-${fixture.runId}"))
            } } }
        }
        contentSet = true
        compose.waitUntil(10_000) { healthy(); hasTag("review-chapter-continue") }
        compose.onNodeWithTag("review-chapter-continue").assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
        waitReady()
        assertEquals(1, captureCreations.get())
        assertTrue("Saved Full/Scene review input must not generate again", calls.isEmpty())
    }

    private suspend fun seedAndCheckReview(): String {
        fixture.seed(capability = module.capability)
        assertEquals(1, fixture.db.update("learning_obligations", ContentValues().apply { put("target_id", "") },
            "account_id=? and obligation_id='obligation1'", arrayOf(fixture.account)))
        fixture.db.execSQL("insert into sentence_capabilities(account_id,sentence_id,capability_type,status) values(?,'sentence1',?,'LEARNING')",
            arrayOf(fixture.account, module.capability))
        fixture.db.execSQL("insert into sentence_capabilities(account_id,sentence_id,capability_type,status) values(?,'sentence1',?,'VERIFIED')",
            arrayOf("other-${fixture.runId}", module.capability))
        if (module == Module.SCENE) fixture.db.execSQL(
            "insert into scene_response_tasks(account_id,chapter_id,task_id,sentence_id,sentence_order,background_en,context_lines,target_sentence_en) values(?,'chapter1','t1','sentence1',0,?,?,?)",
            arrayOf(fixture.account, task.backgroundEn, "AI\u001fWhat changed?", ENGLISH))
        val reviewId = fixture.queue().single().reviewChapterId
        val loaded = AndroidReviewChapterRepository(context, fixture.runtime).loadChapter(reviewId) as? ReviewChapterLoadResult.Ready
        assertNotNull("The sentence-level obligation must be readable before UI entry", loaded)
        val ability = requireNotNull(loaded).chapter.abilities.single()
        assertEquals(module.ability, ability.module)
        assertEquals("sentence1", ability.sentenceId)
        assertEquals("", ability.targetId)
        assertTrue(ability.requiresReview)
        return reviewId
    }

    private fun newCapture(owner: SessionRuntime): ReadSpeakAudioCapture {
        check(owner === fixture.runtime && captureCreations.incrementAndGet() == 1)
        return object : ReadSpeakAudioCapture {
            override fun start(): Boolean { check(active.compareAndSet(false, true)); starts.incrementAndGet(); return true }
            override fun finish(): CapturedReadSpeakAudio? { finishes.incrementAndGet(); active.set(false); return null }
            override fun cancel() { if (active.compareAndSet(true, false)) liveCancels.incrementAndGet() }
        }
    }
    private fun loseConnection() {
        compose.runOnIdle { graph.connectionGate.networkLost() }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithTag(module.tag).assertDoesNotExist()
        compose.waitForIdle()
    }
    private fun reconnect() {
        val epoch = fixture.runtime.accountEpoch
        compose.onNodeWithTag("connection-error-reconnect").assertIsDisplayed().performTouchInput { click(center) }
        compose.waitUntil(10_000) { healthy(); graph.connectionGate.incident.value == null }
        waitReady()
        assertEquals(fixture.account, fixture.runtime.accountId)
        assertEquals(epoch, fixture.runtime.accountEpoch)
        assertEquals(listOf(HEALTH, REFRESH), calls.toList())
        assertEquals(1, captureCreations.get())
    }
    private fun requestPermission(expected: Int) {
        val checks = microphoneChecks.get()
        tapStart()
        compose.waitUntil(5_000) { healthy(); registry.launches.size == expected }
        assertTrue(microphoneChecks.get() > checks)
        assertFalse(active.get())
    }
    private fun tapStart() = tapLabel(module.startLabel)
    private fun tapLabel(resource: Int) {
        // Scene exposes the same RetryRequested on its secondary and failed-state primary buttons.
        val node = if (module == Module.SCENE && resource == R.string.read_speak_retry)
            compose.onAllNodesWithText(base.getString(resource)).filter(hasClickAction()).onLast()
        else compose.onNodeWithText(base.getString(resource))
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
        compose.waitForIdle()
    }
    private fun waitReady() {
        compose.waitUntil(10_000) { healthy(); captureCreations.get() == 1 && hasTag(module.tag) }
        if (module == Module.SCENE) compose.waitUntil(10_000) {
            healthy(); compose.onAllNodesWithText(requireNotNull(task.backgroundEn)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }
    private fun hasTag(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun assertNoSubmission(before: Map<String, List<List<String>>>) {
        healthy()
        assertEquals(0, finishes.get())
        assertEquals(before, awaitIo { protectedRows() })
        assertEquals(0, awaitIo { fixture.count("review_working_state") })
        assertEquals(0, awaitIo { fixture.count("review_chapter_results") })
        assertTrue(calls.all { it == HEALTH || it == REFRESH })
    }
    private fun protectedRows(): Map<String, List<List<String>>> {
        val db = fixture.db
        val names = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return names.associateWith { table ->
            check(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from $table order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { index -> when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> "null"
                    Cursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(index)}"
                    Cursor.FIELD_TYPE_FLOAT -> "float:${cursor.getDouble(index)}"
                    Cursor.FIELD_TYPE_BLOB -> "blob:${Base64.getEncoder().encodeToString(cursor.getBlob(index))}"
                    else -> "string:${cursor.getString(index)}"
                } })
            } }
        }
    }
    private fun respond(request: HttpRequest): HttpResponse {
        val call = "${request.method} ${request.path}"
        check(call in listOf(HEALTH, REFRESH) && call !in calls) { "Unexpected isolated HTTP: $call" }
        calls += call
        return if (call == HEALTH) HttpResponse(200, """{"status":"ok","service":"api","requestId":"isolated-review-capture"}""")
        else HttpResponse(200, """{"status":"AUTHENTICATED","accountId":"${fixture.account}","accessToken":"ISOLATED_ROTATED_ACCESS","refreshToken":"ISOLATED_ROTATED_REFRESH","settingsCompleted":true}""")
    }
    private fun healthy() { failure.get()?.let { throw AssertionError("Isolated ${module.name} review fixture failed", it) } }
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
            check(dispatchResult(launches[index], true)) { "The actual review permission launcher must still be registered" }
        }
    }
    private class MemoryCredentials(var value: StoredSession?) : SessionCredentialStore {
        override suspend fun load() = value
        override suspend fun save(session: StoredSession) { value = session }
        override suspend fun clear() { value = null }
    }
    private class SilentSpeech : SentenceSpeechEngine {
        override suspend fun awaitReady() = true
        override fun start(text: String, rate: Float, locale: Locale, finished: (Boolean) -> Unit): Boolean { finished(true); return true }
        override fun stop() = Unit
        override fun close() = Unit
    }
    private enum class Module(val capability: String, val ability: ReviewAbilityModule, val tag: String, val startLabel: Int) {
        FULL("FULL_SPEAKING", ReviewAbilityModule.FULL_SENTENCE_SPEAKING, "full-sentence-workspace", R.string.full_sentence_speaking_start_follow),
        SCENE("SCENE_RESPONSE", ReviewAbilityModule.SCENE_RESPONSE, "scene-response-workspace", R.string.pattern_speaking_start_response),
    }
    private companion object {
        const val ENGLISH = "I used to walk."
        const val HEALTH = "GET /api/v1/health"
        const val REFRESH = "POST /api/v1/auth/sessions/refresh"
        val task = FixedSceneResponseTask("t1", "sentence1", "Talk about a past habit.",
            listOf(SceneDialogueLine("AI", "What changed?")), ENGLISH)
    }
}
