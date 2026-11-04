package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.AndroidFixedSourcePayloadKeys
import com.reallanguage.app.feature.formation.data.AndroidFormationPayloadKeys
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.data.AndroidExplanationCache
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actual production LR05 -> LR06 -> unfinished-entry recovery, using only a UUID database.
 * The fixed sentences and delivered explanation cache are explicitly preaccepted fixtures.
 * This is not evidence of preceding modules, AI quality, TTS quality, or oral completion.
 * No speech attempt/result is seeded, and no microphone, answer-audio, or provider is used.
 */
class ProductionLearningModuleTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val account = "module-transition-$runId"
    private val chapterId = "chapter-$runId"
    private val directory = File(base.cacheDir, "module-transition-$runId")
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val isolatedDb = IsolatedLearningDatabaseContext(base, "module-transition-$runId")
    private val context = object : ContextWrapper(isolatedDb) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "private")
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "module-transition-$runId-$name"
            preferences.add(isolatedName)
            return base.getSharedPreferences(isolatedName, mode)
        }
    }
    private val requests = AtomicInteger()
    private val credentialsAccess = AtomicInteger()
    private val host = mutableStateOf<ProductionAppGraph?>(null)
    private var mounted = false
    private val firstText = "I drink green tea."
    private val secondText = "I take a long walk."
    private val firstExplanation = "第一句只说明喝绿茶。"
    private val secondExplanation = "第二句只说明长距离散步。"

    @After fun cleanOnlyThisUuidFixture() {
        if (mounted) {
            compose.runOnIdle { host.value = null }
            compose.waitForIdle()
        }
        runBlocking(Dispatchers.IO) {
            val hash = MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
                .joinToString("") { "%02x".format(it) }
            AndroidFormationPayloadKeys().deleteAccount(hash)
            AndroidFixedSourcePayloadKeys().deleteAccount(hash)
            context.deleteDatabase("real-language-learning.db")
            synchronized(preferences) { preferences.toList() }.forEach { name ->
                check(name.startsWith("module-transition-$runId-"))
                base.deleteSharedPreferences(name)
            }
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(directory.name == "module-transition-$runId")
            check(!directory.exists() || directory.deleteRecursively())
        }
    }

    @Test(timeout = 90_000)
    fun lastFocusDictationReallySavesThenFirstFocusSpeakingRestoresThroughANewGraph() {
        val firstSession = session()
        val firstGraph = graph(firstSession)
        awaitIo { prepareAcceptedFixture(firstSession) }
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        host.value = firstGraph
        compose.setContent { RlTheme {
            host.value?.let { currentGraph -> key(currentGraph) {
                // Both mounts begin at the real list, never at the desired LR06 endpoint.
                HomeProductionFlow(currentGraph, initialRoute = HomeDiaryRoute.IncompleteLearning())
            } }
        } }
        mounted = true
        openActualUnfinishedRow()
        waitForTag("audio-dictation-input-panel")
        compose.onNodeWithTag("learning-focus-s2").assertExists()
        compose.onNodeWithTag("learning-focus-s1").assertDoesNotExist()
        compose.onNodeWithText("已静音").assertIsDisplayed()
        compose.onNodeWithText("提示").performClick()
        compose.onNodeWithText("long walk：长距离散步").assertExists()
        compose.onNodeWithText("green tea：绿茶").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput(secondText)
        try {
            compose.onNode(hasSetTextAction()).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(secondText)),
            )
            compose.onNodeWithText("提交").assertIsEnabled().assertIsDisplayed().performClick()
            waitForScrollableFeedback("本次听写内容正确。")
        } catch (failure: Throwable) {
            val evidence = submissionFailureEvidence()
            throw AssertionError("Actual isolated dictation submission did not acknowledge completion.\n$evidence", failure)
        }
        val savedDictation = awaitIo {
            withTimeout(15_000) {
                var values: Map<String, String>? = null
                while (values?.get("correct") != "true") {
                    values = AndroidLearningProgressStore(context, firstSession, chapterId, "s2", "LR-05").read()
                    if (values?.get("correct") != "true") delay(20)
                }
                requireNotNull(values)
            }
        }
        assertEquals(secondText, savedDictation["input"])
        awaitIo { assertOnlyActualDictationCompletion() }

        compose.onNodeWithText("继续").performClick()
        assertFirstSpeakingPage()
        awaitCheckpoint(firstGraph)
        awaitIo { assertFirstSentenceBinding(firstGraph) }
        assertEquals(0, requests.get())

        // Destroy all page-owned ViewModels before creating a graph with a fresh runtime.
        compose.runOnIdle { host.value = null }
        compose.waitForIdle()
        val recoveredGraph = graph(session())
        assertNotSame(firstGraph, recoveredGraph)
        assertEquals(expectedCheckpoint(), awaitIo { recoveredGraph.learningCheckpointRepository.read(chapterId) })
        compose.runOnIdle { host.value = recoveredGraph }
        openActualUnfinishedRow()
        assertFirstSpeakingPage()
        awaitCheckpoint(recoveredGraph)
        awaitIo {
            assertFirstSentenceBinding(recoveredGraph)
            assertOnlyActualDictationCompletion()
        }
        assertEquals("Returning to cached learning must not perform HTTP or AI", 0, requests.get())
        assertEquals("No real credential store may be read by this local fixture", 0, credentialsAccess.get())
        Log.i("RealLanguageJourney", "LR05_LAST_TO_LR06_FIRST_RECOVERED run=$runId graphRebuilt=true dictationRows=1 speechResults=0 httpCalls=0")
    }

    private fun openActualUnfinishedRow() {
        val tag = "incomplete-learning-resume-$chapterId"
        waitForTag(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    private fun assertFirstSpeakingPage() {
        waitForTag("full-sentence-workspace")
        compose.onNodeWithText("第 1 章 · 整句会说").assertIsDisplayed()
        compose.onNodeWithTag("learning-focus-s1").assertExists()
        compose.onNodeWithTag("learning-focus-s2").assertDoesNotExist()
        compose.onNodeWithTag("full-sentence-attempt").assertTextEquals("中英跟读 · 第 1 次")
        compose.onNodeWithTag("full-sentence-recording-status").assertDoesNotExist()
    }

    private suspend fun assertFirstSentenceBinding(graph: ProductionAppGraph) {
        val checkpoint = requireNotNull(graph.learningCheckpointRepository.read(chapterId))
        val resolved = graph.resolveResumeRoute(checkpoint.chapterId, checkpoint.moduleId, checkpoint.stepId)
        assertTrue(resolved is HomeDiaryRoute.FullSentenceSpeaking)
        val speaking = resolved as HomeDiaryRoute.FullSentenceSpeaking
        assertEquals("s1", speaking.focusSentenceId)
        assertEquals(firstText, speaking.chapter.sentences.single { it.sentenceId == speaking.focusSentenceId }.textEn)
        assertEquals(firstExplanation, speaking.baseExplanationZh)
        assertNotEquals(secondExplanation, speaking.baseExplanationZh)
        assertTrue(speaking.expressions.any { it.display == "green tea" && it.startOffset == 8 && it.endOffset == 17 })
        assertFalse(speaking.expressions.any { it.display == "long walk" || it.display == "walk" })
        assertEquals(listOf("green tea：绿茶"), graph.loadLockedHints(chapterId, speaking.focusSentenceId))
        assertEquals(listOf("long walk：长距离散步"), graph.loadLockedHints(chapterId, "s2"))
    }

    private fun assertOnlyActualDictationCompletion() = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        db.rawQuery("select sentence_id,step,status from learning_steps where account_id=? and chapter_id=?", arrayOf(account, chapterId)).use {
            assertTrue(it.moveToFirst())
            assertEquals("s2", it.getString(0)); assertEquals("AUDIO_DICTATION", it.getString(1)); assertEquals("COMPLETED", it.getString(2))
            assertFalse(it.moveToNext())
        }
        listOf("full_speaking_result_drafts", "scene_response_result_drafts", "sentence_capabilities", "chapter_archives").forEach { table ->
            db.rawQuery("select count(*) from $table where account_id=?", arrayOf(account)).use {
                assertTrue(it.moveToFirst()); assertEquals("This test must not create oral or archive results: $table", 0, it.getInt(0))
            }
        }
    }

    private suspend fun prepareAcceptedFixture(session: SessionRuntime) {
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("insert into formation_flows values(?,?,'FIXED','DIARY','隔离模块衔接')", arrayOf(account, "flow-$runId"))
                db.execSQL("insert into chapters values(?,?,?,0,'NOT_STARTED')", arrayOf(account, "flow-$runId", chapterId))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,'s1',0,'我喝绿茶。',?,'FOCUS')", arrayOf(account, chapterId, firstText))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,'s2',1,'我长距离散步。',?,'FOCUS')", arrayOf(account, chapterId, secondText))
                listOf(arrayOf("green", "s1", "green tea", "绿茶"), arrayOf("walk", "s2", "long walk", "长距离散步")).forEach { target ->
                    db.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary) values(?,?,?,?,'FIXED_EXPRESSION',?,?,'','')", arrayOf(account, chapterId, target[0], target[1], target[2], target[3]))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val key = UUID.nameUUIDFromBytes("v1:1:sentence:".toByteArray(Charsets.UTF_8)).toString()
        val cache = AndroidExplanationCache(context)
        cache.write(account, chapterId, "s1", key, JSONObject().put("kind", "SENTENCE").put("explanationZh", firstExplanation).toString())
        cache.write(account, chapterId, "s2", key, JSONObject().put("kind", "SENTENCE").put("explanationZh", secondExplanation).toString())
        // Only a resumable, blank and muted LR05 draft is supplied. No completion is seeded.
        assertTrue(AndroidLearningProgressStore(context, session, chapterId, "s2", "LR-05").write(mapOf(
            "version" to "1", "input" to "", "audioLooping" to "false", "hintExpanded" to "false",
            "correct" to "false", "incorrect" to "false",
        )))
    }

    private fun expectedCheckpoint() = OrdinaryLearningCheckpoint(chapterId, "LR-06", "s1")
    /** Only this UUID fixture and its neutral page are exposed, never session/credential data. */
    private fun submissionFailureEvidence(): String {
        val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }
            .getOrElse { "Semantics unavailable: ${it.javaClass.simpleName}" }
        val rows = runCatching { awaitIo {
            FormationDatabase(context).use { helper ->
                val db = helper.readableDatabase
                val progress = db.rawQuery(
                    "select sentence_id,module_id,payload from learning_module_progress where account_id=? and chapter_id=? order by sentence_id,module_id",
                    arrayOf(account, chapterId),
                ).use { cursor -> buildList {
                    while (cursor.moveToNext()) {
                        val payload = JSONObject(cursor.getString(2))
                        add("${cursor.getString(0)}/${cursor.getString(1)} input=${payload.optString("input")} correct=${payload.optString("correct")} incorrect=${payload.optString("incorrect")} audioLooping=${payload.optString("audioLooping")}")
                    }
                } }
                val checkpoint = db.rawQuery("select module_id,step_id from learning_checkpoints where account_id=? and chapter_id=?", arrayOf(account, chapterId))
                    .use { if (it.moveToFirst()) "${it.getString(0)}/${it.getString(1)}" else "missing" }
                val completions = db.rawQuery("select sentence_id,step,status from learning_steps where account_id=? and chapter_id=?", arrayOf(account, chapterId))
                    .use { cursor -> buildList { while (cursor.moveToNext()) add("${cursor.getString(0)}/${cursor.getString(1)}/${cursor.getString(2)}") } }
                "checkpoint=$checkpoint progress=$progress completions=$completions httpCalls=${requests.get()}"
            }
        } }.getOrElse { "Fixture read unavailable: ${it.javaClass.simpleName}" }
        val screenshot = runCatching {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val view = compose.activity.window.decorView
            val drawn = AtomicBoolean()
            val listener = ViewTreeObserver.OnDrawListener { drawn.set(true) }
            instrumentation.runOnMainSync {
                view.viewTreeObserver.addOnDrawListener(listener)
                view.postInvalidateOnAnimation()
            }
            try { compose.waitUntil(3_000) { drawn.get() } }
            finally { instrumentation.runOnMainSync { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener) } }
            runCatching { instrumentation.uiAutomation.waitForIdle(150, 3_000) }
            val file = File(base.getExternalFilesDir(null), "visual-qa/module-transition-$runId-submit-failure.png")
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            // Compose's root crop can be blank when adjustPan has moved its origin above
            // the display. Capture the actual display, including the live IME instead.
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            file.absolutePath
        }.getOrElse { "Screenshot unavailable: ${it.javaClass.simpleName}" }
        val evidence = "$rows\nscreenshot=$screenshot\n$tree"
        evidence.chunked(3_500).forEachIndexed { index, text -> Log.e("RealLanguageJourney", "isolated submission diagnostic[$index] $text") }
        return evidence
    }
    private fun awaitCheckpoint(graph: ProductionAppGraph) = awaitIo {
        withTimeout(15_000) {
            while (graph.learningCheckpointRepository.read(chapterId) != expectedCheckpoint()) delay(20)
        }
    }
    private fun session() = SessionRuntime().apply { update("isolated-preaccepted-session", account) }
    private fun graph(session: SessionRuntime) = ProductionAppGraph(context, session, HttpTransport {
        requests.incrementAndGet()
        error("This cached module transition must not execute any HTTP or AI request")
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? { credentialsAccess.incrementAndGet(); error("Credential read is outside this test") }
        override suspend fun save(session: StoredSession) { credentialsAccess.incrementAndGet(); error("Credential save is outside this test") }
        override suspend fun clear() { credentialsAccess.incrementAndGet(); error("Credential clear is outside this test") }
    })
    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun waitForScrollableFeedback(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        // The response belongs to the real task scroll area above the focused input.
        // Scroll that response into view; never dismiss the IME to reach the action.
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
    private fun <T> awaitIo(block: suspend () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch { result.set(runCatching { block() }) }
        try {
            compose.waitUntil(20_000) { result.get() != null }
            return requireNotNull(result.get()).getOrThrow()
        } finally { job.cancel(); scope.cancel() }
    }
}
