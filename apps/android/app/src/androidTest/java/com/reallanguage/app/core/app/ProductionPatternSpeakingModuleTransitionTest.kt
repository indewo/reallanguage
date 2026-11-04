package com.reallanguage.app.core.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import com.reallanguage.app.feature.learning.data.AndroidExplanationCache
import com.reallanguage.app.feature.learning.data.AndroidLearningCheckpointRepository
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.domain.CheckpointSaveResult
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Real production pages, repositories, JSON and SQL; only transport and capture are explicit doubles.
 * Fixed content is preaccepted. No LR06 rounds/results, LR07 progress or writing results are seeded.
 * Synthetic bytes are not sound and cannot prove pronunciation, word highlighting or TTS quality.
 * One pattern per focus intentionally does not certify ordering of multiple patterns in one sentence.
 * The host preserves/restores RECORD_AUDIO permission externally; this test never changes it.
 */
class ProductionPatternSpeakingModuleTransitionTest {
    private val recoveryKeys = com.reallanguage.app.testing.IsolatedFormationPayloadKeys()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val account = "pattern-transition-$runId"
    private val chapterId = "chapter-$runId"
    private val directory = File(base.cacheDir, "pattern-transition-$runId")
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val isolatedDb = IsolatedLearningDatabaseContext(base, "pattern-transition-$runId")
    private val context = object : ContextWrapper(isolatedDb) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "private")
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "pattern-transition-$runId-$name"
            preferences.add(isolatedName)
            return base.getSharedPreferences(isolatedName, mode)
        }
    }
    private data class Example(val id: String, val scene: String, val prompt: String, val en: String, val zh: String)
    private data class Focus(val id: String, val en: String, val zh: String, val targetId: String,
        val structure: String, val meaning: String, val evidenceEnd: Int, val examples: List<Example>)
    private val focuses = listOf(
        Focus("s1", "I drink green tea.", "我喝绿茶。", "pattern-first", "drink + object", "说明喝的东西", 17, listOf(
            Example("first-e1", "早餐饮品", "What do you drink?", "I drink warm milk.", "我喝温牛奶。"),
            Example("first-e2", "运动后的饮品", "What do you drink after exercise?", "I drink cold water.", "我喝凉水。"),
        )),
        Focus("s2", "I take a long walk.", "我长距离散步。", "pattern-second", "take a + noun", "说明进行的活动", 18, listOf(
            Example("second-e1", "忙碌后休息", "What do you do after work?", "I take a short break.", "我稍作休息。"),
            Example("second-e2", "旅行途中留影", "What do you do on the trip?", "I take a photo.", "我拍一张照片。"),
        )),
    )
    private val host = mutableStateOf<ProductionAppGraph?>(null)
    private var mounted = false
    private val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val fixtureFailure = AtomicReference<Throwable?>(null)
    private val credentialsAccess = AtomicInteger()
    private val captureCreations = AtomicInteger()
    private val captureStarts = AtomicInteger()
    private val captureFinishes = AtomicInteger()
    private val attemptFiles = Collections.synchronizedList(mutableListOf<File>())

    @After fun cleanOnlyThisUuidFixture() {
        if (mounted) {
            compose.runOnIdle { host.value = null }
            compose.waitForIdle()
        }
        runBlocking(Dispatchers.IO) {
            context.deleteDatabase("real-language-learning.db")
            synchronized(preferences) { preferences.toList() }.forEach { name ->
                check(name.startsWith("pattern-transition-$runId-")); base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.name == "pattern-transition-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    @Test(timeout = 180_000)
    fun twoFocusPatternsReadRespondAndSkipThenWritingRestoresThroughANewGraph() {
        assertEquals("Host must preserve/restore permission externally; no test-side grant",
            PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(base, Manifest.permission.RECORD_AUDIO))
        val runtime = session()
        awaitIo { seedAcceptedFixedFacts(runtime) }
        val before = awaitIo { allRows() }
        val firstGraph = graph(runtime)
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        host.value = firstGraph
        compose.setContent { RlTheme { RlPaperBackground {
            host.value?.let { current -> key(current) {
                HomeProductionFlow(current, initialRoute = HomeDiaryRoute.IncompleteLearning())
            } }
        } } }
        mounted = true
        openUnfinished()

        focuses.forEachIndexed { focusIndex, focus ->
            waitTag("pattern-speaking-workspace")
            waitTag("learning-focus-${focus.id}")
            assertEquals("A missing capture seam must stop before any real recording action", focusIndex + 1, captureCreations.get())
            waitText(focus.examples.last().en)
            compose.onNodeWithText(focus.structure).assertIsDisplayed()
            compose.onNodeWithText(focuses[1 - focusIndex].structure).assertDoesNotExist()
            focus.examples.forEach { example ->
                compose.onNodeWithText(example.en).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(example.zh).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithText(base.getString(R.string.pattern_speaking_start_responses)).assertIsNotEnabled()
            // Playback quality is outside this fixture. Mute using the actual page action.
            click(R.string.learning_mute)
            repeat(2) { index ->
                val readButton = compose.onAllNodesWithText(base.getString(R.string.pattern_speaking_read_once)).onFirst()
                readButton.performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
                waitText(base.getString(R.string.pattern_speaking_response_finished))
                click(R.string.pattern_speaking_response_finished, scroll = true)
                val reading = awaitProgress(runtime, focus.id, "LR-07") { it["example.$index.read"] == "true" }
                assertEquals(focus.examples[index].id, reading["example.$index.id"])
                assertEquals("READING", reading["stage"])
                compose.waitUntil(15_000) {
                    assertFixtureHealthy()
                    compose.onAllNodesWithText(base.getString(R.string.pattern_speaking_read_complete)).fetchSemanticsNodes().size == index + 1
                }
                assertEquals(index + 1, compose.onAllNodesWithText(base.getString(R.string.pattern_speaking_read_complete)).fetchSemanticsNodes().size)
                if (index == 0) compose.onNodeWithText(base.getString(R.string.pattern_speaking_start_responses)).assertIsNotEnabled()
            }
            click(R.string.pattern_speaking_start_responses)
            waitTag("pattern-speaking-scene-progress")
            compose.onNodeWithTag("pattern-speaking-scene-progress").assertTextEquals("场景 1 / 2")
            val responded = focus.examples.first()
            compose.onNodeWithText(responded.prompt).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(responded.en).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(responded.zh).performScrollTo().assertIsDisplayed()
            click(R.string.pattern_speaking_start_response)
            waitText(base.getString(R.string.pattern_speaking_response_finished))
            click(R.string.pattern_speaking_response_finished)
            val response = awaitProgress(runtime, focus.id, "LR-07") { it["responseCompleted"] == "true" }
            assertEquals(responded.en, response["transcript"])
            assertEquals(responseFeedback(focus.id), response["feedback"])
            compose.onNodeWithText(responseFeedback(focus.id)).performScrollTo().assertIsDisplayed()
            assertEquals((focusIndex + 1) * 4, synchronized(requests) { requests.size })
            click(R.string.pattern_speaking_next_scene)
            awaitProgress(runtime, focus.id, "LR-07") { it["responseIndex"] == "1" && it["responseCompleted"] == "false" }
            val skipped = focus.examples.last()
            compose.onNodeWithTag("pattern-speaking-scene-progress").assertTextEquals("场景 2 / 2")
            compose.onNodeWithText(skipped.prompt).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(skipped.en).performScrollTo().assertIsDisplayed()
            val beforeSkip = synchronized(requests) { requests.size }
            click(R.string.pattern_speaking_skip_scene)
            val complete = awaitProgress(runtime, focus.id, "LR-07") { it["completed"] == "true" }
            assertEquals(focus.targetId, complete["pattern.0.id"])
            assertEquals(focus.id, complete["pattern.0.source"])
            assertEquals("2", complete["exampleCount"])
            focus.examples.forEachIndexed { index, example ->
                assertEquals(example.id, complete["example.$index.id"])
                assertEquals(example.en, complete["example.$index.en"])
                assertEquals(example.zh, complete["example.$index.zh"])
                assertEquals("true", complete["example.$index.read"])
            }
            assertEquals("1", complete["responseIndex"])
            assertEquals("false", complete["responseCompleted"])
            assertEquals("", complete["transcript"])
            // The first focus's transition may immediately generate the second focus's set.
            val allowedAfterSkip = beforeSkip + if (focusIndex == 0) 1 else 0
            assertTrue(synchronized(requests) { requests.size } in beforeSkip..allowedAfterSkip)
            assertFalse("Completed feedback releases temporary synthetic bytes", synchronized(attemptFiles) { attemptFiles.any(File::exists) })
        }

        assertWritingInitialPage()
        val writing = awaitProgress(runtime, "s1", "LR-08") { it["phase"] == "AUDIBLE_SINGLE" }
        assertEquals("0", writing["sentenceIndex"])
        assertEquals("", writing["input"])
        assertEquals("false", writing["helpUsed"])
        assertEquals(setOf("answer:s1", "answer:s2"), writing.keys.filter { it.startsWith("answer:") }.toSet())
        assertTrue(writing.filterKeys { it.startsWith("answer:") }.values.all(String::isEmpty))
        awaitWritingCheckpoint(firstGraph)
        awaitIo { assertWritingBinding(firstGraph) }
        assertRequestBudget()
        assertEquals(6, captureStarts.get()); assertEquals(6, captureFinishes.get())
        val completedProgress = focuses.associate { it.id to awaitProgress(runtime, it.id, "LR-07") { saved -> saved["completed"] == "true" } }
        val after = awaitIo { allRows() }
        val allowedWrites = setOf("learning_module_progress", "learning_checkpoints")
        assertEquals("Visible-target practice cannot write formal results, state, streak, obligation or source changes",
            before.filterKeys { it !in allowedWrites }, after.filterKeys { it !in allowedWrites })

        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        val newRuntime = session()
        val newGraph = graph(newRuntime)
        assertNotSame(firstGraph, newGraph)
        assertEquals(expectedCheckpoint(), awaitIo { newGraph.learningCheckpointRepository.read(chapterId) })
        compose.runOnIdle { host.value = newGraph }
        openUnfinished()
        assertWritingInitialPage()
        awaitWritingCheckpoint(newGraph)
        awaitIo { assertWritingBinding(newGraph) }
        assertEquals(writing, awaitProgress(newRuntime, "s1", "LR-08") { it["phase"] == "AUDIBLE_SINGLE" })
        focuses.forEach { focus -> assertEquals(completedProgress[focus.id], awaitIo { AndroidLearningProgressStore(context, newRuntime, chapterId, focus.id, "LR-07").read() }) }
        val reopenedRows = awaitIo { allRows() }
        assertEquals(after.filterKeys { it !in allowedWrites }, reopenedRows.filterKeys { it !in allowedWrites })
        assertRequestBudget()
        assertEquals(2, captureCreations.get())
        assertEquals(0, credentialsAccess.get())
        assertFalse(synchronized(attemptFiles) { attemptFiles.any(File::exists) })
        Log.i("RealLanguageJourney", "LR07_TWO_FOCUS_TO_LR08_FIRST run=$runId graphRebuilt=true patterns=2 examples=4 responses=2 skippedScenes=2 captures=6 fixtureHttp=8 formalResults=0 realAi=0 realMicrophone=0")
    }

    private fun session() = SessionRuntime().apply { update("synthetic-session-no-real-credential", account) }
    private fun graph(runtime: SessionRuntime) = ProductionAppGraph(context, runtime, HttpTransport { request ->
        try { fixtureResponse(request) }
        catch (failure: Throwable) { fixtureFailure.compareAndSet(null, failure); HttpResponse(500, "{\"error\":\"isolated-fixture-contract-failed\"}") }
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? { credentialsAccess.incrementAndGet(); error("No credential read") }
        override suspend fun save(session: StoredSession) { credentialsAccess.incrementAndGet(); error("No credential save") }
        override suspend fun clear() { credentialsAccess.incrementAndGet(); error("No credential clear") }
    }, patternSpeakingCaptureFactory = { _, owner ->
        check(owner.accountId == account)
        val ordinal = captureCreations.incrementAndGet()
        check(ordinal in 1..2)
        val focus = focuses[ordinal - 1]
        object : ReadSpeakAudioCapture {
            private var active: Int? = null
            private var count = 0
            override fun start(): Boolean {
                check(active == null && count < 3); active = ++count
                captureStarts.incrementAndGet(); return true
            }
            override fun finish(): CapturedReadSpeakAudio {
                val attempt = requireNotNull(active); active = null
                val file = File(context.cacheDir, "synthetic-${focus.id}-$attempt.test")
                check(!file.exists()); file.writeBytes(syntheticBytes(focus.id, attempt))
                attemptFiles.add(file); captureFinishes.incrementAndGet()
                return CapturedReadSpeakAudio(file)
            }
            override fun cancel() { active = null }
        }
    }, patternSpeakingRecoveryKeys = recoveryKeys)

    @Synchronized private fun fixtureResponse(request: HttpRequest): HttpResponse {
        assertEquals("POST", request.method)
        val position = synchronized(requests) { requests.size }
        assertTrue("No unexpected generation, feedback or oral/writing evaluation", position < 8)
        val focus = focuses[position / 4]
        val step = position % 4
        val body = JSONObject(requireNotNull(request.body))
        assertEquals(chapterId, body.getString("chapterId"))
        assertEquals(focus.targetId, body.getString("patternId"))
        assertFalse(body.getString("commandId").isBlank())
        val output = if (step == 0) {
            assertEquals("/api/v1/learning/pattern-practice-sets", request.path)
            assertEquals(focus.id, body.getString("sourceSentenceId"))
            assertEquals(focus.en, body.getString("sourceTextEn"))
            assertEquals(focus.zh, body.getString("sourceTextZh"))
            assertEquals(focus.structure, body.getString("structure"))
            assertEquals(focus.meaning, body.getString("explanationZh"))
            JSONObject().put("status", "READY").put("patternId", focus.targetId).put("examples",
                JSONArray().apply { focus.examples.forEach { example -> put(JSONObject().put("exampleId", example.id)
                    .put("sceneZh", example.scene).put("aiPromptEn", example.prompt)
                    .put("targetResponseEn", example.en).put("targetResponseZh", example.zh)) } })
        } else {
            val example = focus.examples[if (step == 2) 1 else 0]
            assertEquals("/api/v1/learning/pattern-practice-feedback", request.path)
            assertEquals(example.id, body.getString("exampleId"))
            assertEquals(if (step == 3) "RESPONSE" else "READING", body.getString("mode"))
            assertEquals(example.en, body.getString("targetTextEn"))
            assertEquals(focus.structure, body.getString("targetStructure"))
            assertTrue(body.getBoolean("targetVisible"))
            assertEquals("audio/mp4", body.getString("audioMimeType"))
            assertArrayEquals(syntheticBytes(focus.id, step), Base64.getDecoder().decode(body.getString("audioBase64")))
            JSONObject().put("status", "READY").put("repeatRecommended", false).put("transcript", example.en)
                .put("feedbackZh", if (step == 3) responseFeedback(focus.id) else "隔离指读反馈，非真人发音评价。")
                .put("confidence", 0.95)
        }
        requests.add(request)
        return HttpResponse(200, output.toString())
    }

    private fun syntheticBytes(sentenceId: String, attempt: Int) = "NOT_AUDIO:isolated:$runId:$sentenceId:$attempt".toByteArray(Charsets.UTF_8)
    private fun responseFeedback(sentenceId: String) = "隔离回应反馈 $sentenceId：本轮仅训练，不是独立能力判定。"
    private fun assertRequestBudget() {
        assertFixtureHealthy()
        val observed = synchronized(requests) { requests.toList() }
        assertEquals(8, observed.size)
        assertEquals(2, observed.count { it.path.endsWith("/pattern-practice-sets") })
        val feedback = observed.filter { it.path.endsWith("/pattern-practice-feedback") }.map { JSONObject(requireNotNull(it.body)) }
        assertEquals(4, feedback.count { it.getString("mode") == "READING" })
        assertEquals(2, feedback.count { it.getString("mode") == "RESPONSE" })
        assertEquals(8, observed.map { JSONObject(requireNotNull(it.body)).getString("commandId") }.toSet().size)
    }

    private suspend fun seedAcceptedFixedFacts(runtime: SessionRuntime) {
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离句式衔接')", arrayOf(account, "flow-$runId"))
                db.execSQL("insert into chapters values(?,?,?,0,'NOT_STARTED')", arrayOf(account, "flow-$runId", chapterId))
                focuses.forEachIndexed { index, focus ->
                    db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,?,?,?,'FOCUS')",
                        arrayOf<Any>(account, chapterId, focus.id, index * 2, focus.zh, focus.en))
                    val surface = focus.en.substring(2, focus.evidenceEnd)
                    val evidence = JSONObject().put("version", 1).put("startUtf16", 2).put("endUtf16", focus.evidenceEnd)
                        .put("surfaceForm", surface).put("normalizedForm", focus.structure).put("allowedForms", JSONArray().put(surface))
                    db.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary,source_evidence) values(?,?,?,?,'PATTERN',?,?,?,'',?)",
                        arrayOf(account, chapterId, focus.targetId, focus.id, focus.structure, focus.meaning,
                            focus.structure.split(" + ").joinToString("\u001f"), evidence.toString()))
                }
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,'context',1,'然后我去外面。','Then I go outside.','CONTEXT')", arrayOf(account, chapterId))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val cache = AndroidExplanationCache(context)
        val key = UUID.nameUUIDFromBytes("v1:1:sentence:".toByteArray(Charsets.UTF_8)).toString()
        focuses.forEach { focus -> cache.write(account, chapterId, focus.id, key,
            JSONObject().put("kind", "SENTENCE").put("explanationZh", "固定句说明：${focus.zh}").toString()) }
        assertEquals(CheckpointSaveResult.Saved, AndroidLearningCheckpointRepository(context, runtime)
            .save(OrdinaryLearningCheckpoint(chapterId, "LR-07", "s1")))
    }

    private fun allRows(): Map<String, List<List<String?>>> = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val names = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        names.associateWith { name ->
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from $name order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
            } }
        }
    }
    private fun expectedCheckpoint() = OrdinaryLearningCheckpoint(chapterId, "LR-08", "s1")
    private fun awaitWritingCheckpoint(graph: ProductionAppGraph) = awaitIo {
        withTimeout(15_000) { while (graph.learningCheckpointRepository.read(chapterId) != expectedCheckpoint()) delay(20) }
    }
    private suspend fun assertWritingBinding(graph: ProductionAppGraph) {
        val checkpoint = requireNotNull(graph.learningCheckpointRepository.read(chapterId))
        val resolved = graph.resolveResumeRoute(checkpoint.chapterId, checkpoint.moduleId, checkpoint.stepId)
        assertTrue(resolved is HomeDiaryRoute.CompleteWriting)
        val actual = resolved as HomeDiaryRoute.CompleteWriting
        assertEquals("s1", actual.focusSentenceId)
        assertEquals(listOf("s1", "context", "s2"), actual.chapter.sentences.map { it.sentenceId })
        assertEquals("固定句说明：${focuses.first().zh}", actual.baseExplanationZh)
        val targets = requireNotNull(graph.loadWritingTargets(chapterId))
        assertEquals(focuses.map { it.targetId }.toSet(), targets.map { it.targetId }.toSet())
        focuses.forEach { focus ->
            val target = targets.single { it.targetId == focus.targetId }
            assertEquals(focus.id, target.sentenceId)
            assertEquals(listOf(focus.en.substring(2, focus.evidenceEnd)), target.expectedForms)
            assertEquals(2, requireNotNull(target.sourceSpan).startUtf16)
            assertEquals(focus.evidenceEnd, requireNotNull(target.sourceSpan).endUtf16)
        }
    }
    private fun assertWritingInitialPage() {
        waitTag("complete-writing-workspace")
        waitTag("complete-writing-input-single")
        waitText(base.getString(R.string.complete_writing_audible))
        compose.onNodeWithText(base.getString(R.string.complete_writing_audible)).assertIsDisplayed()
        compose.onNodeWithTag("complete-writing-input-single").assertIsEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.onNodeWithTag("complete-writing-input-s2").assertDoesNotExist()
    }
    private fun awaitProgress(runtime: SessionRuntime, sentenceId: String, module: String, ready: (Map<String, String>) -> Boolean): Map<String, String> = awaitIo {
        withTimeout(15_000) {
            var saved: Map<String, String>?
            do {
                saved = AndroidLearningProgressStore(context, runtime, chapterId, sentenceId, module).read()
                if (saved == null || !ready(saved)) delay(20)
            } while (saved == null || !ready(saved))
            requireNotNull(saved)
        }
    }
    private fun openUnfinished() { waitTag("incomplete-learning-resume-$chapterId"); compose.onNodeWithTag("incomplete-learning-resume-$chapterId").performClick() }
    private fun click(resource: Int, scroll: Boolean = false) {
        val text = base.getString(resource); waitText(text)
        val node = compose.onNodeWithText(text)
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(15_000) { assertFixtureHealthy(); compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun waitText(text: String) { compose.waitUntil(15_000) { assertFixtureHealthy(); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun assertFixtureHealthy() { fixtureFailure.get()?.let { throw AssertionError("Isolated pattern HTTP contract mismatch", it) } }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try { compose.waitUntil(20_000) { assertFixtureHealthy(); result.get() != null }; return requireNotNull(result.get()).getOrThrow() }
        finally { job.cancel(); scope.cancel() }
    }
}
