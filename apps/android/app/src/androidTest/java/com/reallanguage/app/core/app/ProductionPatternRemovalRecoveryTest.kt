package com.reallanguage.app.core.app

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.Cursor
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionCredentialStore
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.StoredSession
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.domain.ContentFixRequest
import com.reallanguage.app.feature.formation.domain.ContentFixResult
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.FixedSourceUnit
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleDraft
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationRepository
import com.reallanguage.app.feature.formation.domain.KnowledgeExampleGenerationResult
import com.reallanguage.app.feature.formation.domain.KnowledgeTargetDetails
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.knowledge.data.AndroidPatternRemovalRepository
import com.reallanguage.app.feature.knowledge.domain.PatternRemovalResult
import com.reallanguage.app.feature.learning.data.AndroidChapterArchiveRepository
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.data.AndroidLockedPatternReader
import com.reallanguage.app.feature.learning.data.AndroidPatternSpeakingRepository
import com.reallanguage.app.feature.learning.data.CapturedReadSpeakAudio
import com.reallanguage.app.feature.learning.data.ReadSpeakAudioCapture
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import com.reallanguage.app.feature.learning.domain.LockedSpeakingPattern
import com.reallanguage.app.feature.learning.domain.PatternPracticeExample
import com.reallanguage.app.feature.learning.domain.PatternPracticeGenerationResult
import com.reallanguage.app.feature.learning.domain.PatternPracticeSet
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingStage
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingUiState
import com.reallanguage.app.feature.learning.presentation.PatternSpeakingViewModel
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import com.reallanguage.app.testing.explicitSourceFixRepository
import com.reallanguage.app.testing.withExplicitSource
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real Graph factories, fixed-source JCE encryption and UUID SQLite. The accepted practice and
 * module result rows are local fixtures, not evidence of a paid provider call or speaking quality. */
class ProductionPatternRemovalRecoveryTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val account = "pattern-removal-recovery-$runId"
    private val directory = File(base.cacheDir, "pattern-removal-recovery-$runId")
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val privateDb = IsolatedLearningDatabaseContext(base, "pattern-removal-recovery-$runId")
    private val context = object : ContextWrapper(privateDb) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "pattern-removal-recovery-$runId-$name"
            preferences.add(isolatedName)
            return base.getSharedPreferences(isolatedName, mode)
        }
    }
    private lateinit var database: FormationDatabase
    private val db get() = database.writableDatabase
    private val runtime = SessionRuntime().apply { update("isolated-not-real-session", account) }
    private val stores = mutableListOf<ViewModelStore>()
    private val httpCalls = AtomicInteger()
    private val credentialCalls = AtomicInteger()
    private val exampleCalls = AtomicInteger()
    private val captureStarts = AtomicInteger()
    private val captureFinishes = AtomicInteger()
    private val captureCancels = AtomicInteger()
    private val acceptedB = listOf(
        PatternPracticeExample("accepted-b1-$runId", "谈上学方式", "How do you go to school?", "I go to school by bus.", "我乘公交车上学。"),
        PatternPracticeExample("accepted-b2-$runId", "谈旅行方式", "How do you travel?", "I travel by train.", "我乘火车旅行。"),
    )

    @Before fun createOnlyUuidPrivateStorage() {
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
        check(directory.mkdir())
        val file = context.getDatabasePath(LOGICAL_DATABASE).canonicalFile
        check(file.parentFile == base.cacheDir.canonicalFile && file.name.startsWith("pattern-removal-recovery-$runId-"))
        check(!file.exists())
        database = FormationDatabase(context)
        db
    }

    @After fun removeOnlyThisFixturesFilesAndPreferences() = runBlocking {
        withContext(Dispatchers.Main) { stores.forEach(ViewModelStore::clear) }
        if (::database.isInitialized) database.close()
        val file = context.getDatabasePath(LOGICAL_DATABASE).canonicalFile
        check(file.parentFile == base.cacheDir.canonicalFile && file.name.startsWith("pattern-removal-recovery-$runId-") && file.extension == "db")
        check(context.deleteDatabase(LOGICAL_DATABASE) || !file.exists())
        synchronized(preferences) { preferences.toList() }.forEach { name ->
            check(name.startsWith("pattern-removal-recovery-$runId-"))
            base.deleteSharedPreferences(name)
        }
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == "pattern-removal-recovery-$runId")
        check(!directory.exists() || directory.deleteRecursively())
    }

    @Test fun exactRemovalProjectsOldPaidBThroughNewGraphAndSqlWithoutGenerationOrOldEpochAuthority() = runBlocking {
        val old = fix("I used to walk.", "我过去常步行。", listOf(targetA("old-a", "used to walk", 2)))
        seedArchivedOrigin(old)
        val formalA = scalar("select item_id from pattern_items where account_id=?", account)
        val current = fix("I used to write by hand.", "我过去常常手写。", listOf(
            targetA("a", "used to write", 2).copy(knownKnowledgeRef = "PATTERN:$formalA"),
            LearningTarget("b", "s", "by + noun", "说明方式", LearningTargetType.PATTERN,
                structureParts = listOf("by", "noun"), sourceEvidence = SourceEvidence(1, 16, 23, "by hand", "by + noun", listOf("by hand"))),
        ), listOf("PATTERN:$formalA"))
        val originalPatterns = requireNotNull(AndroidLockedPatternReader(context, runtime).read(current.chapterId, current.sentenceId))
        assertEquals(listOf("used to + verb", "by + noun"), originalPatterns.map { it.structure })
        val a = originalPatterns[0]
        val b = originalPatterns[1]
        assertEquals(2, exampleCalls.get())
        val original = acceptedProgress(originalPatterns, b, setOf(a.patternId))
        assertTrue(progress(current).write(original))
        assertEquals(original, readSqlProgress(current))

        assertEquals(PatternRemovalResult.Removed,
            AndroidPatternRemovalRepository(context, runtime).removeFromPatternBook(formalA, "remove-$runId"))
        assertEquals(listOf(a), AndroidLockedPatternReader(context, runtime).readRemoved(current.chapterId, current.sentenceId))
        val protectedRows = allRows().filterKeys { it !in PROGRESS_TABLES }
        val checkpoint = rows("select account_id,chapter_id,module_id,step_id from learning_checkpoints where account_id=?", account)
        val expected = acceptedProgress(listOf(b), b, emptySet())

        val firstGraph = graph()
        val active = requireNotNull(firstGraph.loadSpeakingPatterns(current.chapterId, current.sentenceId))
        assertEquals(listOf(b), active)
        val loadedChapter = (firstGraph.loadReviewFixedChapter(current.chapterId) as FixedChapterLoadResult.Loaded).chapter
        val route = HomeDiaryRoute.PatternSpeaking(
            chapter = loadedChapter,
            focusSentenceId = current.sentenceId,
            baseExplanationZh = "隔离句式恢复说明",
            expressions = emptyList(),
        )
        val first = createModel(firstGraph, route, active)
        assertRestoredB(awaitRestored(first.vm, b), b)
        awaitSqlProgress(current, expected)
        // Independently reopen the actual persistence reader after the Graph factory's projection writes.
        assertEquals(expected, progress(current).read())
        assertEquals(checkpoint, rows("select account_id,chapter_id,module_id,step_id from learning_checkpoints where account_id=?", account))
        assertEquals(protectedRows, allRows().filterKeys { it !in PROGRESS_TABLES })
        // Probe the real repository's rehydrated replay cache; a missed restorePractice would attempt HTTP.
        assertEquals(PatternPracticeGenerationResult.Ready(PatternPracticeSet(b.patternId, acceptedB)), first.repository.generate(b))
        assertNoExternalWork()
        val cancels = captureCancels.get()
        withContext(Dispatchers.Main) { first.store.clear() }
        assertTrue(captureCancels.get() > cancels)
        database.close()
        database = FormationDatabase(context)

        val beforeReentry = allRows()
        val secondGraph = graph()
        val secondActive = requireNotNull(secondGraph.loadSpeakingPatterns(current.chapterId, current.sentenceId))
        assertEquals(listOf(b), secondActive)
        val second = createModel(secondGraph, route, secondActive)
        assertRestoredB(awaitRestored(second.vm, b), b)
        assertEquals(expected, progress(current).read())
        assertEquals(PatternPracticeGenerationResult.Ready(PatternPracticeSet(b.patternId, acceptedB)), second.repository.generate(b))
        assertEquals("An already projected reentry does not rewrite even its working-state row", beforeReentry, allRows())
        withContext(Dispatchers.Main) { second.store.clear() }

        // Main cannot resume this VM's restore after its first suspension until the epoch changes.
        // The factory-created removed reader and store must therefore refuse the old entry, even
        // if the background read managed to finish before Main returns to the coroutine.
        val oldReader = AndroidLockedPatternReader(context, runtime)
        val oldStore = progress(current)
        val oldEpoch = runtime.accountEpoch
        val beforeEpoch = allRows()
        val lateGraph = graph()
        val late = withContext(Dispatchers.Main) {
            createModelOnMain(lateGraph, route, secondActive).also {
                runtime.clear()
                runtime.update("isolated-next-session", account)
                assertTrue(runtime.accountEpoch > oldEpoch)
            }
        }
        withTimeout(5_000) { late.vm.uiState.first { it.progressFailed && !it.submitting } }
        assertFalse(late.vm.uiState.value.completed)
        assertNull(oldReader.readRemoved(current.chapterId, current.sentenceId))
        assertFalse(oldStore.write(expected + ("transcript" to "rejected old entry")))
        assertEquals(PatternPracticeGenerationResult.Failed, late.repository.generate(b))
        assertEquals(beforeEpoch, allRows())
        withContext(Dispatchers.Main) { late.store.clear() }

        val newGraph = graph()
        val newActive = requireNotNull(newGraph.loadSpeakingPatterns(current.chapterId, current.sentenceId))
        assertEquals(listOf(b), newActive)
        val fresh = createModel(newGraph, route, newActive)
        assertRestoredB(awaitRestored(fresh.vm, b), b)
        assertEquals(expected, progress(current).read())
        assertEquals(beforeEpoch, allRows())
        assertEquals("The source JSON fixture was never projected in place", original, acceptedProgress(originalPatterns, b, setOf(a.patternId)))
        assertNoExternalWork()
    }

    private fun targetA(id: String, surface: String, start: Int) = LearningTarget(id, "s", "used to + verb", "表达过去的习惯", LearningTargetType.PATTERN,
        structureParts = listOf("used to", "verb"), sourceEvidence = SourceEvidence(1, start, start + surface.length, surface, "used to + verb", listOf(surface)))

    private suspend fun fix(english: String, chinese: String, targets: List<LearningTarget>, refs: List<String> = emptyList()): FixedIds {
        val examples = KnowledgeExampleGenerationRepository { request ->
            exampleCalls.incrementAndGet()
            val phrases = if (request.display == "by + noun") listOf(
                "I travel by train." to "我乘火车旅行。", "I go by bus." to "我乘公交车去。", "I write by hand." to "我手写。",
            ) else listOf("I used to walk." to "我以前常步行。", "She used to read." to "她以前常阅读。", "We used to cook." to "我们以前常做饭。")
            KnowledgeExampleGenerationResult.Generated(KnowledgeTargetDetails(request.targetId, null, request.display,
                "隔离代表场景：谈论习惯和方式。", phrases.map { (en, zh) -> KnowledgeExampleDraft(en, zh, en) }, true))
        }
        val request = ContentFixRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            listOf(EnglishChapterDraft("chapter", listOf(EnglishSentenceDraft("s", chinese, true, listOf(EnglishCandidate("e", english, refs)))))),
            mapOf("s" to "e"), targets, targets.mapTo(mutableSetOf()) { it.targetId },
        ).withExplicitSource(chinese, listOf(FixedSourceUnit("s", 0, chinese)))
        val fixed = explicitSourceFixRepository(context, runtime, examples).fix(request)
        assertTrue("Real source encryption and fixed transaction must succeed", fixed is ContentFixResult.Fixed)
        val chapterId = (fixed as ContentFixResult.Fixed).firstChapterId
        return FixedIds(chapterId, scalar("select unit_id from fixed_sentences where account_id=? and chapter_id=?", account, chapterId))
    }

    private suspend fun seedArchivedOrigin(ids: FixedIds) {
        val targetId = scalar("select target_id from target_selections where account_id=? and chapter_id=?", account, ids.chapterId)
        insert("writing_batch_completions", "account_id" to account, "chapter_id" to ids.chapterId, "command_id" to "old-writing", "qualification" to "INDEPENDENT")
        for ((ability, target) in listOf("WHOLE_WRITING" to "", "PATTERN_WRITING" to targetId)) insert("writing_result_drafts",
            "account_id" to account, "chapter_id" to ids.chapterId, "sentence_id" to ids.sentenceId, "ability_type" to ability, "target_id" to target, "passed" to 1)
        for (table in listOf("full_speaking_result_drafts", "scene_response_result_drafts")) insert(table,
            "account_id" to account, "chapter_id" to ids.chapterId, "sentence_id" to ids.sentenceId, "passed" to 1)
        assertTrue(AndroidChapterArchiveRepository(context, runtime) { "2026-09-10" }.archive(ids.chapterId, "old-archive") is ChapterArchiveResult.Archived)
    }

    private fun graph() = ProductionAppGraph(context, runtime, HttpTransport {
        httpCalls.incrementAndGet()
        HttpResponse(503, "{}")
    }, object : SessionCredentialStore {
        override suspend fun load(): StoredSession? { credentialCalls.incrementAndGet(); error("No credential access in fixture") }
        override suspend fun save(session: StoredSession) { credentialCalls.incrementAndGet(); error("No credential access in fixture") }
        override suspend fun clear() { credentialCalls.incrementAndGet(); error("No credential access in fixture") }
    }, patternSpeakingCaptureFactory = { _, _ -> object : ReadSpeakAudioCapture {
        override fun start(): Boolean { captureStarts.incrementAndGet(); return false }
        override fun finish(): CapturedReadSpeakAudio? { captureFinishes.incrementAndGet(); return null }
        override fun cancel() { captureCancels.incrementAndGet() }
    } })

    private suspend fun createModel(graph: ProductionAppGraph, route: HomeDiaryRoute.PatternSpeaking, active: List<LockedSpeakingPattern>) =
        withContext(Dispatchers.Main) { createModelOnMain(graph, route, active) }
    private fun createModelOnMain(graph: ProductionAppGraph, route: HomeDiaryRoute.PatternSpeaking, active: List<LockedSpeakingPattern>): Model {
        val store = ViewModelStore().also(stores::add)
        val repository = graph.patternSpeakingSession(route)
        val vm = ViewModelProvider(store, graph.patternSpeakingFactory(route, repository, active))[PatternSpeakingViewModel::class.java]
        return Model(vm, store, repository)
    }
    private suspend fun awaitRestored(vm: PatternSpeakingViewModel, b: LockedSpeakingPattern): PatternSpeakingUiState =
        withTimeout(5_000) { vm.uiState.first { it.progressFailed || (!it.submitting && it.currentPattern == b && it.examples == acceptedB) } }
            .also { assertFalse("The production Graph must recover, not show an unavailable progress state", it.progressFailed) }
    private fun assertRestoredB(state: PatternSpeakingUiState, b: LockedSpeakingPattern) {
        assertEquals(listOf(b), state.lockedPatterns)
        assertEquals(b, state.currentPattern)
        assertEquals(0, state.patternIndex)
        assertEquals(PatternSpeakingStage.READING, state.stage)
        assertEquals(acceptedB, state.examples)
        assertEquals(setOf(acceptedB[0].exampleId), state.completedReadingExampleIds)
        assertEquals(emptySet<String>(), state.processedPatternIds)
        assertEquals(acceptedB[0].targetResponseEn, state.transcript)
        assertEquals("已保存的隔离指读反馈", state.feedbackZh)
        assertFalse(state.recording || state.submitting || state.completed || state.currentResponseCompleted)
        assertTrue(state.audioMuted)
    }

    private fun acceptedProgress(patterns: List<LockedSpeakingPattern>, current: LockedSpeakingPattern, processed: Set<String>): Map<String, String> = buildMap {
        put("version", "2"); put("patternCount", patterns.size.toString())
        patterns.forEachIndexed { index, pattern ->
            put("pattern.$index.id", pattern.patternId); put("pattern.$index.source", pattern.sourceSentenceId)
            put("pattern.$index.structure", pattern.structure); put("pattern.$index.explanation", pattern.explanationZh)
        }
        put("processedPatternCount", processed.size.toString())
        patterns.filter { it.patternId in processed }.forEachIndexed { index, pattern -> put("processedPattern.$index.id", pattern.patternId) }
        put("patternIndex", patterns.indexOf(current).also { require(it >= 0) }.toString())
        put("stage", "READING"); put("exampleCount", acceptedB.size.toString())
        acceptedB.forEachIndexed { index, example ->
            put("example.$index.id", example.exampleId); put("example.$index.scene", example.sceneZh); put("example.$index.prompt", example.aiPromptEn)
            put("example.$index.en", example.targetResponseEn); put("example.$index.zh", example.targetResponseZh); put("example.$index.read", (index == 0).toString())
        }
        put("responseIndex", "0"); put("transcript", acceptedB[0].targetResponseEn); put("feedback", "已保存的隔离指读反馈")
        put("repeat", "false"); put("responseCompleted", "false"); put("muted", "true"); put("completed", "false")
    }

    private fun progress(ids: FixedIds) = AndroidLearningProgressStore(context, runtime, ids.chapterId, ids.sentenceId, "LR-07")
    private suspend fun awaitSqlProgress(ids: FixedIds, expected: Map<String, String>) = withTimeout(5_000) {
        while (readSqlProgress(ids) != expected) delay(10)
    }
    private fun readSqlProgress(ids: FixedIds): Map<String, String> = FormationDatabase(context).use { independent ->
        independent.readableDatabase.rawQuery("select payload from learning_module_progress where account_id=? and chapter_id=? and sentence_id=? and module_id='LR-07'",
            arrayOf(account, ids.chapterId, ids.sentenceId)).use { cursor ->
            check(cursor.moveToFirst())
            val json = JSONObject(cursor.getString(0))
            check(!cursor.moveToNext())
            json.keys().asSequence().associateWith { key -> json.get(key).also { require(it is String) } as String }
        }
    }
    private fun assertNoExternalWork() {
        assertEquals("Restoration and repository replay issue zero HTTP calls", 0, httpCalls.get())
        assertEquals(0, credentialCalls.get())
        assertEquals(0, captureStarts.get())
        assertEquals(0, captureFinishes.get())
        assertEquals("Only two local detail fixtures were needed to fix the two sources", 2, exampleCalls.get())
    }
    private fun allRows(): Map<String, List<List<String?>>> = rows("select name from sqlite_master where type='table' and name not like 'sqlite_%' and name!='android_metadata' order by name")
        .associate { row -> row.single()!!.let { table ->
            check(Regex("[a-z_]+").matches(table))
            table to rows("select rowid,* from $table order by rowid")
        } }
    private fun rows(sql: String, vararg args: String): List<List<String?>> = db.rawQuery(sql, args).use { cursor -> buildList {
        while (cursor.moveToNext()) add(List(cursor.columnCount) { index -> when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_BLOB -> "sha256:" + MessageDigest.getInstance("SHA-256").digest(cursor.getBlob(index)).joinToString("") { "%02x".format(it) }
            else -> cursor.getString(index)
        } })
    } }
    private fun scalar(sql: String, vararg args: String) = rows(sql, *args).single().single()!!
    private fun insert(table: String, vararg values: Pair<String, Any>) {
        db.insertOrThrow(table, null, ContentValues().apply { values.forEach { (name, value) -> when (value) {
            is String -> put(name, value); is Int -> put(name, value); else -> error("Unsupported isolated fixture value")
        } } })
    }
    private data class FixedIds(val chapterId: String, val sentenceId: String)
    private data class Model(val vm: PatternSpeakingViewModel, val store: ViewModelStore, val repository: AndroidPatternSpeakingRepository)
    private companion object {
        const val LOGICAL_DATABASE = "real-language-learning.db"
        val PROGRESS_TABLES = setOf("learning_module_progress", "learning_checkpoints")
    }
}
