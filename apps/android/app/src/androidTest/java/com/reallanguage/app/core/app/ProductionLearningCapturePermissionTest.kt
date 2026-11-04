package com.reallanguage.app.core.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.data.AndroidSentenceSpeaker
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.data.SentenceSpeechEngine
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import com.reallanguage.app.feature.learning.presentation.SceneResponseUiState
import com.reallanguage.app.feature.learning.presentation.encodeSceneProgress
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.UUID
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

/** Real Home/Graph/routes and private SQLite; permission registry, HTTP, capture and speech are doubles.
 * No system permission changes/dialogs, microphone, TTS, provider or real account. Fixed inputs are fixtures. */
class ProductionLearningCapturePermissionTest {
    private val recoveryKeys = com.reallanguage.app.testing.IsolatedFormationPayloadKeys()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "learning-capture-permission-${UUID.randomUUID()}"
    private val account = "owner-$prefix"
    private val chapterId = "chapter-$prefix"
    private val directory = File(base.cacheDir, prefix)
    private val privateDb = IsolatedLearningDatabaseContext(base, prefix)
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val microphoneChecks = AtomicInteger()
    private val context = object : ContextWrapper(privateDb) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission == Manifest.permission.RECORD_AUDIO) {
                microphoneChecks.incrementAndGet()
                PackageManager.PERMISSION_DENIED
            } else super.checkPermission(permission, pid, uid)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$prefix-$name"
            preferences.add(isolated)
            return base.getSharedPreferences(isolated, mode)
        }
    }
    private val runtime = SessionRuntime().apply { update("ISOLATED_ACCESS", account) }
    private val credentials = MemoryCredentials(StoredSession(account, "ISOLATED_REFRESH", true))
    private val chapter = FixedLearningChapter(chapterId, 1,
        listOf(FixedLearningSentence("s1", ENGLISH, "我喝绿茶。", true)))
    private val task = FixedSceneResponseTask("t1", "s1", "Talk about your drink.",
        listOf(SceneDialogueLine("AI", "What do you drink?")), ENGLISH)
    private val mounted = mutableStateOf(false)
    private val registry = PendingPermissionRegistry()
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }
    private val calls = CopyOnWriteArrayList<String>()
    private val failure = AtomicReference<Throwable?>(null)
    private val active = AtomicBoolean()
    private val starts = AtomicInteger()
    private val finishes = AtomicInteger()
    private val captureCreations = AtomicInteger()
    private lateinit var module: Module
    private lateinit var graph: ProductionAppGraph
    private var contentSet = false
    private var originalSystemPermission: Int? = null

    @Before fun prepareOnlyThePrivateFixture() {
        originalSystemPermission = ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO)
        assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.mkdir())
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @After fun disposeAndDeleteOnlyThisUuid() {
        if (contentSet) {
            compose.runOnIdle { mounted.value = false; compose.activity.viewModelStore.clear() }
            compose.waitForIdle()
        }
        val database = privateDb.getDatabasePath(DATABASE).canonicalFile
        check(database.parentFile == base.cacheDir.canonicalFile && database.name.startsWith("$prefix-"))
        check(privateDb.deleteDatabase(DATABASE) || !database.exists())
        synchronized(preferences) { preferences.toList() }.forEach { name ->
            check(name.startsWith("$prefix-")); base.deleteSharedPreferences(name)
        }
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == prefix)
        check(!directory.exists() || directory.deleteRecursively())
        originalSystemPermission?.let {
            assertEquals("The fixture must not change platform microphone permission", it,
                ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO))
        }
    }

    @Test fun readSpeakIgnoresPreInterruptionGrantAfterReconnectAndRequiresNewPermission() = staleGrant(Module.READ)
    @Test fun patternIgnoresPreInterruptionGrantAfterReconnectAndRequiresNewPermission() = staleGrant(Module.PATTERN)
    @Test fun sceneIgnoresPreInterruptionGrantAfterReconnectAndRequiresNewPermission() = staleGrant(Module.SCENE)
    @Test fun fullSpeakingIgnoresPreInterruptionGrantAfterReconnectAndRequiresNewPermission() = staleGrant(Module.FULL)

    @Test fun currentReadSpeakPermissionStartsOnlyAfterItsGrantedCallback() {
        mount(Module.READ)
        val protectedBefore = awaitIo { protectedRows() }
        requestPermission(expectedLaunches = 1)
        assertEquals(0, starts.get())
        grant(0)
        compose.waitUntil(5_000) { active.get() }
        assertEquals(1, starts.get())
        assertEquals(0, finishes.get())
        assertTrue(calls.isEmpty())
        assertEquals(protectedBefore, awaitIo { protectedRows() })
        healthy()
    }

    private fun staleGrant(selected: Module) {
        mount(selected)
        val protectedBefore = awaitIo { protectedRows() }
        requestPermission(expectedLaunches = 1)
        val epoch = runtime.accountEpoch
        compose.runOnIdle { graph.connectionGate.networkLost() }
        compose.onNodeWithTag("connection-error-sheet").assertIsDisplayed()
        compose.onNodeWithTag(module.tag).assertDoesNotExist()
        assertEquals(0, starts.get())
        compose.onNodeWithTag("connection-error-reconnect").assertIsDisplayed().performTouchInput { click(center) }
        compose.waitUntil(5_000) { healthy(); graph.connectionGate.incident.value == null }
        waitReady()
        assertEquals(account, runtime.accountId)
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(initialCalls() + listOf(HEALTH, REFRESH), calls.toList())
        assertEquals("Recovery alone does not deliver permission", 1, registry.launches.size)
        assertEquals(0, starts.get())

        // Dispatch through the real remembered launcher, after the whole connection recovery.
        grant(0)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals("A grant owned by the pre-interruption interaction cannot start a microphone", 0, starts.get())
        assertFalse(active.get())
        assertEquals(0, finishes.get())
        assertEquals(protectedBefore, awaitIo { protectedRows() })

        requestPermission(expectedLaunches = 2)
        assertEquals("The new explicit action still waits for its own callback", 0, starts.get())
        grant(1)
        compose.waitUntil(5_000) { active.get() }
        assertEquals(1, starts.get())
        assertEquals(0, finishes.get())
        assertEquals(initialCalls() + listOf(HEALTH, REFRESH), calls.toList())
        assertEquals(protectedBefore, awaitIo { protectedRows() })
        healthy()
    }

    private fun mount(selected: Module) {
        module = selected
        awaitIo { seed() }
        graph = ProductionAppGraph(context, runtime, HttpTransport { request ->
            try { respond(request) } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Throwable) { failure.compareAndSet(null, problem); HttpResponse(500, "{}") }
        }, credentials, fullSentenceSpeakingCaptureFactory = { _, owner -> newCapture(owner) },
            patternSpeakingCaptureFactory = { _, owner -> newCapture(owner) },
            sceneResponseCaptureFactory = { _, owner -> newCapture(owner) },
            readSpeakCaptureFactory = { _, owner -> newCapture(owner) }, patternSpeakingRecoveryKeys = recoveryKeys,
            sceneResponseRecoveryKeys = recoveryKeys)
        mounted.value = true
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalActivityResultRegistryOwner provides registryOwner,
                LocalSentenceSpeakerFactory provides { _, allowed -> AndroidSentenceSpeaker(SilentSpeech(), allowed) },
            ) { RlTheme { RlPaperBackground { if (mounted.value) HomeProductionFlow(graph, initialRoute = route()) } } }
        }
        contentSet = true
        waitReady()
        assertEquals(1, captureCreations.get())
        assertEquals(initialCalls(), calls.toList())
    }

    private fun route(): HomeDiaryRoute = when (module) {
        Module.READ -> HomeDiaryRoute.ReadSpeak(chapter, "s1", "隔离既有讲解", emptyList())
        Module.PATTERN -> HomeDiaryRoute.PatternSpeaking(chapter, "s1", "隔离既有讲解", emptyList())
        Module.SCENE -> HomeDiaryRoute.SceneResponse(chapter, "s1", "隔离既有讲解", emptyList())
        Module.FULL -> HomeDiaryRoute.FullSentenceSpeaking(chapter, "s1", "隔离既有讲解", emptyList())
    }
    private fun newCapture(owner: SessionRuntime): ReadSpeakAudioCapture {
        check(owner === runtime && captureCreations.incrementAndGet() == 1)
        return object : ReadSpeakAudioCapture {
            override fun start(): Boolean { check(active.compareAndSet(false, true)); starts.incrementAndGet(); return true }
            override fun finish(): CapturedReadSpeakAudio? { finishes.incrementAndGet(); return null }
            override fun cancel() { active.set(false) }
            override suspend fun awaitSpeechEnd(): Boolean = CompletableDeferred<Boolean>().await()
        }
    }
    private fun requestPermission(expectedLaunches: Int) {
        val beforeChecks = microphoneChecks.get()
        if (module == Module.READ) {
            val row = compose.onNodeWithTag("learning-english-s1").assertIsDisplayed()
            row.performTouchInput {
                down(centerLeft + androidx.compose.ui.geometry.Offset(8f, 0f))
                moveTo(center, 400L)
                up()
            }
        } else {
            val node = compose.onNodeWithText(base.getString(module.startLabel))
            compose.waitUntil(10_000) { healthy(); runCatching { node.assertIsEnabled(); true }.getOrDefault(false) }
            if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
            node.assertIsDisplayed().assertIsEnabled().performTouchInput { click(center) }
        }
        compose.waitUntil(5_000) { healthy(); registry.launches.size == expectedLaunches }
        assertTrue("The real route must use the local permission check", microphoneChecks.get() > beforeChecks)
        assertFalse(active.get())
    }
    private fun grant(index: Int) = compose.runOnIdle { registry.grant(index) }
    private fun waitReady() {
        compose.waitUntil(10_000) { healthy(); compose.onAllNodesWithTag(module.tag).fetchSemanticsNodes().isNotEmpty() }
        if (module == Module.PATTERN) compose.waitUntil(10_000) {
            healthy(); compose.onAllNodesWithText(PATTERN_ENGLISH).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }
    private suspend fun seed() {
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离权限回调')", arrayOf(account, prefix))
            db.execSQL("insert into chapters values(?,?,?,0,'IN_PROGRESS')", arrayOf(account, prefix, chapterId))
            db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,'s1',0,'我喝绿茶。',?,'FOCUS')", arrayOf(account, chapterId, ENGLISH))
            if (module == Module.PATTERN) {
                val surface = ENGLISH.substring(2, 17)
                val evidence = JSONObject().put("version", 1).put("startUtf16", 2).put("endUtf16", 17)
                    .put("surfaceForm", surface).put("normalizedForm", STRUCTURE).put("allowedForms", JSONArray().put(surface))
                db.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary,source_evidence) values(?,?,'p1','s1','PATTERN',?,'说明喝的饮品',?,'',?)",
                    arrayOf(account, chapterId, STRUCTURE, "drink\u001fnoun", evidence.toString()))
            }
            if (module == Module.SCENE) db.execSQL(
                "insert into scene_response_tasks(account_id,chapter_id,task_id,sentence_id,sentence_order,background_en,context_lines,target_sentence_en) values(?,?,'t1','s1',0,?,?,?)",
                arrayOf(account, chapterId, task.backgroundEn, "AI\u001fWhat do you drink?", ENGLISH))
        }
        if (module == Module.SCENE) assertTrue(AndroidLearningProgressStore(context, runtime, chapterId, "s1", "LR-09")
            .write(encodeSceneProgress(SceneResponseUiState(chapter, listOf(task), audioMuted = true), null)))
    }
    private fun respond(request: HttpRequest): HttpResponse {
        val call = "${request.method} ${request.path}"
        check(call in initialCalls() + listOf(HEALTH, REFRESH) && call !in calls)
        calls += call
        if (call == HEALTH) return HttpResponse(200, """{"status":"ok","service":"api","requestId":"isolated-permission"}""")
        if (call == REFRESH) return HttpResponse(200, """{"status":"AUTHENTICATED","accountId":"$account","accessToken":"ISOLATED_ROTATED_ACCESS","refreshToken":"ISOLATED_ROTATED_REFRESH","settingsCompleted":true}""")
        val json = JSONObject(request.body)
        check(json.getString("chapterId") == chapterId && json.getString("patternId") == "p1")
        check(json.getString("sourceSentenceId") == "s1" && json.getString("sourceTextEn") == ENGLISH)
        check(json.getString("structure") == STRUCTURE && json.getString("commandId").isNotBlank())
        return HttpResponse(200, JSONObject().put("status", "READY").put("patternId", "p1")
            .put("examples", JSONArray().put(JSONObject().put("exampleId", "e1").put("sceneZh", "聊饮品")
                .put("aiPromptEn", "What do you drink?").put("targetResponseEn", PATTERN_ENGLISH).put("targetResponseZh", "我喝红茶。"))).toString())
    }
    private fun initialCalls() = if (module == Module.PATTERN) listOf(PATTERN_GENERATE) else emptyList()
    private fun protectedRows(): Map<String, List<List<String?>>> = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val names = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.filterNot { it in setOf("learning_module_progress", "learning_checkpoints") }
        names.associateWith { name ->
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from $name order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
            } }
        }
    }
    private fun healthy() { failure.get()?.let { throw AssertionError("Isolated permission fixture contract failed", it) } }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(15_000) { healthy(); result.get() != null }
            return requireNotNull(result.get()).getOrThrow()
        } finally { job.cancel(); scope.cancel() }
    }
    private class PendingPermissionRegistry : ActivityResultRegistry() {
        val launches = CopyOnWriteArrayList<Int>()
        private val delivered = mutableSetOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.RequestPermission && input == Manifest.permission.RECORD_AUDIO)
            launches += requestCode // Deliberately do not launch a platform permission UI.
        }
        fun grant(index: Int) {
            check(index in launches.indices && delivered.add(index))
            check(dispatchResult(launches[index], true)) { "The real route launcher is no longer registered" }
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
    private enum class Module(val tag: String, val startLabel: Int) {
        READ("read_speak_status", R.string.read_speak_start),
        PATTERN("pattern-speaking-workspace", R.string.pattern_speaking_read_once),
        SCENE("scene-response-workspace", R.string.pattern_speaking_start_response),
        FULL("full-sentence-workspace", R.string.full_sentence_speaking_start_follow),
    }
    private companion object {
        const val DATABASE = "real-language-learning.db"
        const val ENGLISH = "I drink green tea."
        const val PATTERN_ENGLISH = "I drink black tea."
        const val STRUCTURE = "drink + noun"
        const val HEALTH = "GET /api/v1/health"
        const val REFRESH = "POST /api/v1/auth/sessions/refresh"
        const val PATTERN_GENERATE = "POST /api/v1/learning/pattern-practice-sets"
    }
}
