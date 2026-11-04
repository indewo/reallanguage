package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
import com.reallanguage.app.feature.learning.data.AndroidCompleteWritingRepository
import com.reallanguage.app.feature.learning.data.AndroidExplanationCache
import com.reallanguage.app.feature.learning.data.AndroidLearningCheckpointRepository
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.domain.CheckpointSaveResult
import com.reallanguage.app.feature.learning.domain.FinalWritingBatchResult
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.feature.learning.domain.WritingTargetType
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
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
 * Production Graph, screens, JSON and SQLite. Only the two scene-generation HTTP replies are doubles.
 * Starts with accepted fixed facts, not precompleted writing or pregenerated scene tasks.
 * No microphone action, synthetic audio, permission change, credential access or real AI is needed.
 * Rebuilding the Graph checks local reentry; it is not an operating-system process-death test.
 */
class ProductionCompleteWritingModuleTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val account = "writing-transition-$runId"
    private val chapterId = "chapter-$runId"
    private val directory = File(base.cacheDir, "writing-transition-$runId")
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val isolatedDb = IsolatedLearningDatabaseContext(base, "writing-transition-$runId")
    private val context = object : ContextWrapper(isolatedDb) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "private")
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "writing-transition-$runId-$name"
            preferences.add(isolatedName)
            return base.getSharedPreferences(isolatedName, mode)
        }
    }
    private data class Focus(val id: String, val en: String, val zh: String, val targetId: String,
        val type: String, val display: String, val meaning: String, val start: Int, val end: Int,
        val background: String, val prompt: String)
    private val focuses = listOf(
        Focus("s1", "I drink green tea.", "我喝绿茶。", "word-first", "WORD", "tea", "茶", 14, 17,
            "We are talking about breakfast drinks.", "What do you drink with breakfast?"),
        Focus("s2", "I take a long walk.", "我长距离散步。", "pattern-second", "PATTERN", "take a + noun", "说明进行的活动", 2, 18,
            "After breakfast, we discuss going outside.", "What do you do outside?"),
    )
    private val host = mutableStateOf<ProductionAppGraph?>(null)
    private var mounted = false
    private val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val fixtureFailure = AtomicReference<Throwable?>(null)
    private val credentialsAccess = AtomicInteger()

    @After fun cleanOnlyThisUuidFixture() {
        if (mounted) {
            compose.runOnIdle { host.value = null }
            compose.waitForIdle()
        }
        runBlocking(Dispatchers.IO) {
            val database = context.getDatabasePath("real-language-learning.db").canonicalFile
            check(database.parentFile == base.cacheDir.canonicalFile)
            check(database.name.startsWith("writing-transition-$runId-") && database.extension == "db")
            check(context.deleteDatabase("real-language-learning.db") || !database.exists())
            synchronized(preferences) { preferences.toList() }.forEach { name ->
                check(name.startsWith("writing-transition-$runId-")); base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.name == "writing-transition-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    @Test(timeout = 180_000)
    fun twoFocusFinalWritingFailureRetriesThenSceneRestoresWithoutDuplicateResultsOrGeneration() {
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
        waitTag("complete-writing-input-single")

        focuses.forEachIndexed { index, focus ->
            listOf("AUDIBLE_SINGLE" to R.string.complete_writing_audible,
                "SILENT_SINGLE" to R.string.complete_writing_silent).forEach { (phase, label) ->
                waitText(base.getString(label))
                val training = awaitProgress(runtime, "LR-08") {
                    it["phase"] == phase && it["sentenceIndex"] == index.toString()
                }
                assertEquals("", training["input"])
                assertEquals("false", training["helpUsed"])
                compose.onNodeWithTag("complete-writing-input-single").performScrollTo()
                    .assertIsDisplayed().assertIsEnabled().performTextReplacement(focus.en)
                click(R.string.audio_dictation_submit)
                waitText(base.getString(R.string.complete_writing_correct))
                val checked = awaitProgress(runtime, "LR-08") { it["attemptCorrect"] == "true" }
                assertEquals(focus.en, checked["input"])
                assertEquals(phase, checked["phase"])
                click(R.string.complete_writing_next)
            }
        }

        waitText(base.getString(R.string.complete_writing_final))
        val finalStart = awaitProgress(runtime, "LR-08") { it["phase"] == "FINAL_BATCH" }
        assertEquals("0", finalStart["sentenceIndex"])
        focuses.forEach { focus ->
            compose.onNodeWithTag("complete-writing-input-${focus.id}").performScrollTo()
                .assertIsDisplayed().assertIsEnabled().performTextReplacement(focus.en)
        }
        val completedAnswers = awaitProgress(runtime, "LR-08") { values ->
            focuses.all { values["answer:${it.id}"] == it.en }
        }
        assertEquals("false", completedAnswers["helpUsed"])
        assertEquals(0, requestCount())
        // Actual SQL failure after individual result inserts: the whole final batch must roll back.
        awaitIo { FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("CREATE TRIGGER writing_transition_fail_batch BEFORE INSERT ON writing_batch_completions BEGIN SELECT RAISE(ABORT, 'isolated writing transition failure'); END")
        } }
        click(R.string.audio_dictation_submit)
        waitText(base.getString(R.string.complete_writing_save_failed))
        compose.onNodeWithText(base.getString(R.string.complete_writing_continue)).assertDoesNotExist()
        compose.onNodeWithTag("scene-response-workspace").assertDoesNotExist()
        focuses.forEach { assertAnswer(it) }
        val failedRows = awaitIo { allRows() }
        assertEquals(before.getValue("writing_result_drafts"), failedRows.getValue("writing_result_drafts"))
        assertEquals(before.getValue("writing_batch_completions"), failedRows.getValue("writing_batch_completions"))
        assertNull(awaitIo { AndroidCompleteWritingRepository(context, runtime).loadFinalBatch(chapterId) })
        assertEquals(completedAnswers, awaitProgress(runtime, "LR-08") { it == completedAnswers })
        assertEquals(0, requestCount())

        awaitIo { FormationDatabase(context).use { it.writableDatabase.execSQL("DROP TRIGGER writing_transition_fail_batch") } }
        click(R.string.audio_dictation_submit)
        waitText(base.getString(R.string.complete_writing_final_recorded))
        val accepted = awaitIo { requireNotNull(AndroidCompleteWritingRepository(context, runtime).loadFinalBatch(chapterId)) }
        assertAcceptedBatch(accepted)
        val acceptedRows = awaitIo { allRows() }
        assertEquals(1, acceptedRows.getValue("writing_batch_completions").size)
        assertEquals(4, acceptedRows.getValue("writing_result_drafts").size)
        focuses.forEach { focus ->
            assertAnswer(focus)
            compose.onNodeWithTag("complete-writing-input-${focus.id}").assertIsNotEnabled()
        }
        assertEquals(0, requestCount())

        // Invoke the current real button twice before navigation. A second physical
        // tap after navigation could hit LR09's microphone button, which is out of scope.
        val continueButton = compose.onNodeWithText(base.getString(R.string.complete_writing_continue))
        continueButton.assertIsDisplayed().assertIsEnabled().performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick,
        ) { click -> click(); click() }
        assertInitialScenePage()
        val scene = awaitProgress(runtime, "LR-09") { it["taskCount"] == "2" }
        assertSceneProgress(scene)
        val checkpoint = OrdinaryLearningCheckpoint(chapterId, "LR-09", "s1")
        assertEquals(checkpoint, awaitIo { firstGraph.learningCheckpointRepository.read(chapterId) })
        assertRequestBudget()
        val afterScene = awaitIo { allRows() }
        val mutableTables = setOf("learning_module_progress", "learning_checkpoints", "writing_result_drafts",
            "writing_batch_completions", "scene_response_tasks")
        assertEquals("No writing/scene entry may preemptively archive or alter formal state, obligations or fixed facts",
            before.filterKeys { it !in mutableTables }, afterScene.filterKeys { it !in mutableTables })
        assertEquals(acceptedRows.getValue("writing_result_drafts"), afterScene.getValue("writing_result_drafts"))
        assertEquals(acceptedRows.getValue("writing_batch_completions"), afterScene.getValue("writing_batch_completions"))

        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        val newRuntime = session()
        val newGraph = graph(newRuntime)
        assertNotSame(firstGraph, newGraph)
        assertEquals(checkpoint, awaitIo { newGraph.learningCheckpointRepository.read(chapterId) })
        val restoredRoute = awaitIo { newGraph.resolveResumeRoute(chapterId, "LR-09", "s1") }
        assertTrue(restoredRoute is HomeDiaryRoute.SceneResponse)
        val actual = restoredRoute as HomeDiaryRoute.SceneResponse
        assertEquals("s1", actual.focusSentenceId)
        assertEquals(listOf("s1", "context", "s2"), actual.chapter.sentences.map { it.sentenceId })
        assertEquals(focuses.map { it.en }, actual.chapter.sentences.filter { it.isFocus }.map { it.textEn })
        compose.runOnIdle { host.value = newGraph }
        openUnfinished()
        assertInitialScenePage()
        assertEquals(scene, awaitProgress(newRuntime, "LR-09") { it["taskCount"] == "2" })
        assertEquals(completedAnswers, awaitProgress(newRuntime, "LR-08") { it == completedAnswers })
        assertEquals(accepted, awaitIo { AndroidCompleteWritingRepository(context, newRuntime).loadFinalBatch(chapterId) })
        assertEquals("Fresh Graph must reuse all persisted results/tasks rather than rewrite them", afterScene, awaitIo { allRows() })
        assertRequestBudget()
        assertEquals(0, credentialsAccess.get())
        Log.i("RealLanguageJourney", "LR08_TWO_FOCUS_SAVE_RETRY_TO_LR09 run=$runId trainingSubmissions=4 finalSqlFailures=1 acceptedBatches=1 resultRows=4 sceneTasks=2 graphRebuilt=true fixtureHttp=2 realAi=0 realMicrophone=0")
    }

    private fun session() = SessionRuntime().apply { update("synthetic-session-no-real-credential", account) }
    private fun graph(runtime: SessionRuntime) = ProductionAppGraph(context, runtime, HttpTransport { request ->
        try { fixtureResponse(request) }
        catch (failure: Throwable) {
            fixtureFailure.compareAndSet(null, failure)
            HttpResponse(500, "{\"error\":\"isolated-fixture-contract-failed\"}")
        }
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? { credentialsAccess.incrementAndGet(); error("No credential read") }
        override suspend fun save(session: StoredSession) { credentialsAccess.incrementAndGet(); error("No credential save") }
        override suspend fun clear() { credentialsAccess.incrementAndGet(); error("No credential clear") }
    })

    @Synchronized private fun fixtureResponse(request: HttpRequest): HttpResponse {
        assertEquals("POST", request.method)
        assertEquals("/api/v1/learning/scene-response-tasks", request.path)
        val position = requestCount()
        assertTrue("Only one fixed scene generation per FOCUS is allowed", position < focuses.size)
        val focus = focuses[position]
        val body = JSONObject(requireNotNull(request.body))
        assertEquals(chapterId, body.getString("chapterId"))
        assertEquals(focus.id, body.getString("focusSentenceId"))
        assertEquals(UUID.nameUUIDFromBytes("scene-task:$account:$chapterId:${focus.id}".toByteArray()).toString(), body.getString("commandId"))
        val sentences = body.getJSONArray("chapterSentences")
        assertEquals(3, sentences.length())
        assertEquals(listOf("s1", "context", "s2"), (0 until 3).map { sentences.getJSONObject(it).getString("sentenceId") })
        focuses.forEachIndexed { index, fixed ->
            val actual = sentences.getJSONObject(index * 2)
            assertEquals(fixed.en, actual.getString("textEn")); assertEquals(fixed.zh, actual.getString("textZh"))
            assertTrue(actual.getBoolean("isFocus"))
        }
        assertFalse(sentences.getJSONObject(1).getBoolean("isFocus"))
        assertEquals("Then I go outside.", sentences.getJSONObject(1).getString("textEn"))
        requests.add(request)
        return HttpResponse(200, JSONObject().put("taskId", "scene-${focus.id}-$runId")
            .put("sentenceId", focus.id).put("backgroundEn", focus.background)
            .put("contextLines", JSONArray().put(JSONObject().put("speaker", "AI").put("textEn", focus.prompt)))
            .put("targetSentenceEn", focus.en).toString())
    }

    private suspend fun seedAcceptedFixedFacts(runtime: SessionRuntime) {
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离书写衔接')", arrayOf(account, "flow-$runId"))
                db.execSQL("insert into chapters values(?,?,?,0,'NOT_STARTED')", arrayOf(account, "flow-$runId", chapterId))
                focuses.forEachIndexed { index, focus ->
                    db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,?,?,?,'FOCUS')",
                        arrayOf<Any>(account, chapterId, focus.id, index * 2, focus.zh, focus.en))
                    val surface = focus.en.substring(focus.start, focus.end)
                    val evidence = JSONObject().put("version", 1).put("startUtf16", focus.start).put("endUtf16", focus.end)
                        .put("surfaceForm", surface).put("normalizedForm", focus.display).put("allowedForms", JSONArray().put(surface))
                    db.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary,source_evidence) values(?,?,?,?,?,?,?,?, '',?)",
                        arrayOf(account, chapterId, focus.targetId, focus.id, focus.type, focus.display, focus.meaning,
                            if (focus.type == "PATTERN") focus.display.split(" + ").joinToString("\u001f") else "", evidence.toString()))
                }
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,'context',1,'然后我去外面。','Then I go outside.','CONTEXT')", arrayOf(account, chapterId))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val cache = AndroidExplanationCache(context)
        val cacheKey = UUID.nameUUIDFromBytes("v1:1:sentence:".toByteArray(Charsets.UTF_8)).toString()
        focuses.forEach { focus -> cache.write(account, chapterId, focus.id, cacheKey,
            JSONObject().put("kind", "SENTENCE").put("explanationZh", "固定句说明：${focus.zh}").toString()) }
        assertEquals(CheckpointSaveResult.Saved, AndroidLearningCheckpointRepository(context, runtime)
            .save(OrdinaryLearningCheckpoint(chapterId, "LR-08", "s1")))
    }

    private fun assertAcceptedBatch(value: FinalWritingBatchResult) {
        assertTrue(value is FinalWritingBatchResult.Valid)
        val results = (value as FinalWritingBatchResult.Valid).sentenceResults
        assertEquals(focuses.map { it.id }.toSet(), results.map { it.sentenceId }.toSet())
        focuses.forEach { focus ->
            val result = results.single { it.sentenceId == focus.id }
            assertTrue(result.wholeSentencePassed)
            val target = result.targetResults.single()
            assertEquals(focus.targetId, target.targetId)
            assertEquals(if (focus.type == "WORD") WritingTargetType.MEANING else WritingTargetType.PATTERN, target.type)
            assertTrue(target.passed)
        }
    }
    private fun assertAnswer(focus: Focus) {
        val node = compose.onNodeWithTag("complete-writing-input-${focus.id}").performScrollTo().assertIsDisplayed()
        assertEquals(focus.en, node.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
    private fun assertInitialScenePage() {
        waitTag("scene-response-workspace")
        waitText("AI: ${focuses.first().prompt}")
        compose.onNodeWithTag("scene-dialogue-scroll").assertIsDisplayed()
            .assertTextEquals("AI: ${focuses.first().prompt}")
        compose.onNodeWithTag("scene-background-scroll").assertTextEquals(focuses.first().background)
        compose.onNodeWithTag("scene-transcript-scroll").assertTextEquals(focuses.first().en)
        compose.onNodeWithText(base.getString(R.string.scene_response_round_total, 1)).assertIsDisplayed()
        compose.onNodeWithText(base.getString(R.string.pattern_speaking_start_response)).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(focuses.last().prompt).assertDoesNotExist()
    }
    private fun assertSceneProgress(scene: Map<String, String>) {
        assertEquals("0", scene["taskIndex"]); assertEquals("0", scene["roundIndex"])
        assertEquals("false", scene["completed"]); assertEquals("false", scene["trainingCompleted"])
        assertEquals("", scene["transcript"]); assertEquals("", scene["finalDecision"])
        focuses.forEachIndexed { index, focus ->
            assertEquals("scene-${focus.id}-$runId", scene["task.$index.id"])
            assertEquals(focus.id, scene["task.$index.sentence"])
            assertEquals(focus.en, scene["task.$index.target"])
            assertEquals(focus.background, scene["task.$index.background"])
            assertEquals(focus.prompt, scene["task.$index.line.0.text"])
        }
    }
    private fun requestCount() = synchronized(requests) { requests.size }
    private fun assertRequestBudget() {
        assertFixtureHealthy()
        assertEquals(2, requestCount())
        val commands = synchronized(requests) { requests.map { JSONObject(requireNotNull(it.body)).getString("commandId") } }
        assertEquals(2, commands.toSet().size)
    }
    private fun allRows(): Map<String, List<List<String?>>> = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val names = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        names.associateWith { name ->
            require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from $name order by rowid", null).use { cursor -> buildList {
                while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
            } }
        }
    }
    private fun awaitProgress(runtime: SessionRuntime, module: String, ready: (Map<String, String>) -> Boolean): Map<String, String> = awaitIo {
        withTimeout(15_000) {
            var saved: Map<String, String>?
            do {
                saved = AndroidLearningProgressStore(context, runtime, chapterId, "s1", module).read()
                if (saved == null || !ready(saved)) delay(20)
            } while (saved == null || !ready(saved))
            requireNotNull(saved)
        }
    }
    private fun openUnfinished() {
        waitTag("incomplete-learning-resume-$chapterId")
        compose.onNodeWithTag("incomplete-learning-resume-$chapterId").performClick()
    }
    private fun click(resource: Int) {
        val text = base.getString(resource); waitText(text)
        compose.onNodeWithText(text).assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun waitTag(tag: String) {
        compose.waitUntil(15_000) { assertFixtureHealthy(); compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun waitText(text: String) {
        compose.waitUntil(15_000) { assertFixtureHealthy(); compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun assertFixtureHealthy() {
        fixtureFailure.get()?.let { throw AssertionError("Isolated scene HTTP contract mismatch", it) }
    }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(20_000) { assertFixtureHealthy(); result.get() != null }
            return requireNotNull(result.get()).getOrThrow()
        } finally { job.cancel(); scope.cancel() }
    }
}
