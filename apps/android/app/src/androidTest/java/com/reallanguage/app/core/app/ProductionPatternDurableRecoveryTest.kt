package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.*
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.learning.data.*
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Graph/VM/codec/SQLite, isolated NOT_AUDIO transport; a new Graph is NOT process-death proof. */
class ProductionPatternDurableRecoveryTest {
    @Test fun readingUnknownNewGraphRetriesOriginalRequestAndReadsSavedResult() = journey(reading = true)
    @Test fun responseUnknownNewGraphRetriesOriginalRequestAndReadsSavedResult() = journey(reading = false)

    private fun journey(reading: Boolean) = runBlocking { withContext(Dispatchers.IO) {
        val f = Fixture(reading)
        try {
            val before = f.protectedHash()
            val firstGraph = f.graph(); val first = f.model(firstGraph)
            f.idle(first)
            assertFalse(first.uiState.value.progressFailed)
            withContext(Dispatchers.Main) { first.onAction(if (reading) PatternSpeakingAction.StartReadingRequested("e1") else PatternSpeakingAction.StartResponseRequested) }
            withTimeout(5_000) { first.uiState.first { it.recording || it.failureMessageVisible } }
            assertTrue(first.uiState.value.recording)
            withContext(Dispatchers.Main) { first.onAction(if (reading) PatternSpeakingAction.ReadingFinished("e1") else PatternSpeakingAction.FinishResponseRequested) }
            f.idle(first); assertTrue(first.uiState.value.technicalRetryPending)
            assertEquals(1, f.requests.size); val body = f.requests.single().body
            assertEquals(1, f.starts); assertEquals(1, f.finishes)
            f.clearModels()
            val nextGraph = f.graph(); val next = f.model(nextGraph)
            f.idle(next)
            assertTrue("The actual Graph must restore the encrypted unknown request", next.uiState.value.technicalRetryPending)
            assertFalse(next.uiState.value.recording); assertEquals(1, f.requests.size)
            assertEquals(1, f.starts); assertEquals(1, f.finishes)
            f.ready = true
            // Fail only the local accepted progress write, after the paid feedback is delivered.
            f.sql("create trigger fail_pattern_progress before insert on learning_module_progress when NEW.account_id='${f.account}' begin select raise(ABORT,'isolated progress fault'); end")
            withContext(Dispatchers.Main) {
                repeat(3) { next.onAction(if (reading) PatternSpeakingAction.StartReadingRequested("e1") else PatternSpeakingAction.RetryResponseRequested) }
            }
            f.idle(next)
            withTimeout(5_000) { next.uiState.first { it.progressFailed } }
            assertEquals(2, f.requests.size); assertEquals(body, f.requests.last().body)
            assertEquals(JSONObject(body).getString("commandId"), JSONObject(f.requests.last().body).getString("commandId"))
            assertEquals(1, f.starts); assertEquals(1, f.finishes)
            assertEquals(f.feedback, next.uiState.value.feedbackZh)
            f.sql("drop trigger fail_pattern_progress")
            withContext(Dispatchers.Main) { next.onAction(PatternSpeakingAction.RetryProgressRequested) }
            withTimeout(5_000) { next.uiState.first { !it.progressFailed && !it.submitting } }
            val saved = requireNotNull(f.progress().read())
            assertEquals(f.feedback, saved["feedback"])
            assertEquals("true", saved[if (reading) "example.0.read" else "responseCompleted"])
            f.clearModels()
            val final = f.model(f.graph()); f.idle(final)
            assertEquals(f.feedback, final.uiState.value.feedbackZh)
            assertFalse(final.uiState.value.technicalRetryPending)
            if (reading) assertEquals(setOf("e1"), final.uiState.value.completedReadingExampleIds)
            else { assertTrue(final.uiState.value.currentResponseCompleted); assertEquals(0, final.uiState.value.responseIndex) }
            assertEquals(saved, f.progress().read()); assertEquals(2, f.requests.size)
            assertEquals(before, f.protectedHash())
        } finally { f.close() }
    } }

    private class Fixture(reading: Boolean) {
        val id = UUID.randomUUID().toString()
        val account = "pattern-durable-$id"
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val directory = File(base.cacheDir, "pattern-durable-$id").also { check(it.mkdir()) }
        private val preferences = mutableSetOf<String>()
        private val isolated = IsolatedLearningDatabaseContext(base, "pattern-durable-$id")
        val context = object : ContextWrapper(isolated) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val safe = "pattern-durable-$id-$name"; synchronized(preferences) { preferences.add(safe) }
                return base.getSharedPreferences(safe, mode)
            }
        }
        val runtime = SessionRuntime().apply { update("ISOLATED_TOKEN", account) }
        val chapter = FixedLearningChapter("c-$id", 1, listOf(FixedLearningSentence("s-$id", "I would rather have tea.", "我更想喝茶。", true)))
        val pattern = LockedSpeakingPattern("p-$id", "s-$id", "would rather", "表达偏好")
        val examples = listOf(PatternPracticeExample("e1", "点茶", "Tea or coffee?", "I would rather have tea.", "我更想喝茶。"))
        val route = HomeDiaryRoute.PatternSpeaking(chapter, "s-$id", "隔离说明", emptyList())
        val namespace = "reallanguage.pattern-speaking.payload.test.${id.replace("-", "") }"
        val keys = AndroidPatternSpeakingPayloadKeys(namespace, canUseAccount = { it == fixedSourceAccountHash(account) })
        private val stores = mutableListOf<ViewModelStore>()
        val requests = mutableListOf<HttpRequest>(); var ready = false; var starts = 0; var finishes = 0
        val feedback = "隔离反馈：语意完整。"
        init {
            listOf(account, "foreign-$id").forEach { owner ->
                sql("insert into formation_flows values (?,?,'FIXED','DIARY','隔离恢复')", arrayOf(owner, "flow-$owner"))
                sql("insert into chapters values (?,?,?,0,'IN_PROGRESS')", arrayOf(owner, "flow-$owner", chapter.chapterId))
                sql("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?,?,?,0,'我更想喝茶。',?,'FOCUS')", arrayOf(owner, chapter.chapterId, pattern.sourceSentenceId, chapter.sentences.single().textEn))
                sql("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary) values (?,?,?,?,'PATTERN',?,?,'[]','[]')", arrayOf(owner, chapter.chapterId, pattern.patternId, pattern.sourceSentenceId, pattern.structure, pattern.explanationZh))
            }
            val state = PatternSpeakingUiState(chapter, pattern.sourceSentenceId, listOf(pattern),
                stage = if (reading) PatternSpeakingStage.READING else PatternSpeakingStage.RESPONDING,
                examples = examples, completedReadingExampleIds = if (reading) emptySet() else setOf("e1"))
            sql("insert into learning_checkpoints values (?,?,'LR-07',?)", arrayOf(account, chapter.chapterId, pattern.sourceSentenceId))
            sql("insert into learning_module_progress values (?,?,?,'LR-07',?)", arrayOf(account, chapter.chapterId, pattern.sourceSentenceId, JSONObject(encodePatternProgress(state)).toString()))
        }
        fun graph() = ProductionAppGraph(context, runtime, HttpTransport { request ->
            check(request.path == "/api/v1/learning/pattern-practice-feedback" && request.method == "POST")
            requests += request
            if (!ready) HttpResponse(503, "") else HttpResponse(200, JSONObject().put("status", "READY")
                .put("feedbackZh", feedback).put("transcript", examples.single().targetResponseEn)
                .put("repeatRecommended", false).put("confidence", 0.95).toString())
        }, object : SessionCredentialStore {
            override suspend fun load(): StoredSession? = null
            override suspend fun save(session: StoredSession) = error("No authentication expected")
            override suspend fun clear() = error("No authentication expected")
        }, patternSpeakingCaptureFactory = { _, _ -> object : ReadSpeakAudioCapture {
            override fun start(): Boolean { starts++; return true }
            override fun finish(): CapturedReadSpeakAudio { finishes++; return CapturedReadSpeakAudio(File(context.cacheDir, "audio-${UUID.randomUUID()}").apply { writeText("NOT_AUDIO_GRAPH_FIXTURE") }, "audio/mp4") }
            override fun cancel() = Unit
        } }, patternSpeakingRecoveryKeys = keys)
        suspend fun model(graph: ProductionAppGraph): PatternSpeakingViewModel {
            assertEquals(listOf(pattern), graph.loadSpeakingPatterns(chapter.chapterId, pattern.sourceSentenceId))
            val repo = graph.patternSpeakingSession(route)
            return withContext(Dispatchers.Main) {
                val store = ViewModelStore().also { stores.add(it) }
                ViewModelProvider(store, graph.patternSpeakingFactory(route, repo, listOf(pattern)))[PatternSpeakingViewModel::class.java]
            }
        }
        suspend fun idle(vm: PatternSpeakingViewModel) { withTimeout(5_000) { vm.uiState.first { !it.submitting } } }
        fun progress() = AndroidLearningProgressStore(context, runtime, chapter.chapterId, pattern.sourceSentenceId, "LR-07")
        fun sql(query: String, args: Array<String> = emptyArray()) = FormationDatabase(context).use { it.writableDatabase.execSQL(query, args) }
        fun protectedHash(): String = FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            val values = tables.flatMap { table ->
                check(table.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")))
                db.rawQuery("select * from \"$table\"", null).use { cursor -> buildList {
                    while (cursor.moveToNext()) {
                        val owner = cursor.getColumnIndex("account_id").takeIf { it >= 0 }?.let(cursor::getString)
                        val module = cursor.getColumnIndex("module_id").takeIf { it >= 0 }?.let(cursor::getString)
                        if (owner == account && table in setOf("learning_module_progress", "learning_checkpoints") && module == "LR-07") continue
                        add(table + (0 until cursor.columnCount).joinToString("") { if (cursor.isNull(it)) "-1:" else cursor.getString(it).let { value -> "${value.length}:$value" } })
                    }
                } }
            }.sorted().joinToString("\n")
            MessageDigest.getInstance("SHA-256").digest(values.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        suspend fun clearModels() = withContext(Dispatchers.Main) { stores.forEach(ViewModelStore::clear); stores.clear() }
        suspend fun close() {
            clearModels(); runtime.clear()
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias = "$namespace.${fixedSourceAccountHash(account)}.v1"
            check(namespace == "reallanguage.pattern-speaking.payload.test.${id.replace("-", "")}")
            keyStore.deleteEntry(alias)
            synchronized(preferences) { preferences.toList() }.forEach { check(it.startsWith("pattern-durable-$id-")); base.deleteSharedPreferences(it) }
            val db = context.getDatabasePath("real-language-learning.db").canonicalFile
            check(db.parentFile == base.cacheDir.canonicalFile && db.name.startsWith("pattern-durable-$id-"))
            context.deleteDatabase("real-language-learning.db")
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == "pattern-durable-$id")
            check(directory.deleteRecursively())
        }
    }
}
