package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.fixedSourceAccountHash
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in write -> actual external force-stop -> read -> actual force-stop -> verify protocol.
 * New repository objects alone do not prove process death. NOT_AUDIO/HTTP are isolated fixtures. */
class PatternSpeakingUnknownProcessRecoveryTest {
    @Test fun originalUnknownFeedbackAndConsumedExampleSurviveThreeSeparateProcesses() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("patternUnknownPhase")
        assumeTrue(phase in setOf("write", "read", "verify"))
        val mode = requireNotNull(args.getString("patternUnknownMode")).also { require(it in setOf("reading", "response")) }
        val runId = requireNotNull(args.getString("runId")).also { require(UUID.fromString(it).toString() == it) }
        withContext(Dispatchers.IO) {
            val f = Fixture(requireNotNull(phase), mode, runId)
            var verified = false
            try {
                if (phase == "write") f.seed()
                assertEquals(listOf(f.pattern), AndroidLockedPatternReader(f.context, f.runtime).read(f.chapter.chapterId, f.sentence))
                val previousWrite = if (phase == "write") null else f.context.evidence("write")
                if (previousWrite != null) {
                    assertNotEquals("End the previous process before starting this phase", previousWrite.getInt("pid"), Process.myPid())
                    assertEquals(previousWrite.getString("protectedSqlSha"), f.sqlHash(protectedOnly = true))
                    assertEquals(1, previousWrite.getInt("httpCount"))
                    assertEquals(1, previousWrite.getInt("startCount")); assertEquals(1, previousWrite.getInt("finishCount"))
                }
                val before = f.sqlHash(protectedOnly = true)
                if (phase == "read") {
                    val pending = f.loaded()
                    assertEquals(PatternRecoveryPhase.Pending, pending.snapshot.phase)
                    f.assertIdentity(pending.snapshot)
                    assertEquals(previousWrite!!.getString("bodySha"), sha(requireNotNull(pending.snapshot.requestBody)))
                    assertEquals(previousWrite.getString("commandSha"), sha(JSONObject(pending.snapshot.requestBody!!).getString("commandId")))
                    assertEquals(previousWrite.getString("payloadSha"), f.context.payloadSha(f.account))
                }
                if (phase == "verify") {
                    val previousRead = f.context.evidence("read")
                    assertNotEquals(previousRead.getInt("pid"), Process.myPid())
                    assertNotEquals(previousWrite!!.getInt("pid"), previousRead.getInt("pid"))
                    assertEquals(1, previousRead.getInt("httpCount"))
                    assertEquals(0, previousRead.getInt("startCount")); assertEquals(0, previousRead.getInt("finishCount"))
                    assertEquals(previousRead.getString("allSqlSha"), f.sqlHash(protectedOnly = false))
                    assertEquals(previousRead.getString("payloadSha"), f.context.payloadSha(f.account))
                }
                val model = f.model()
                f.idle(model)
                assertFalse(model.uiState.value.progressFailed)
                assertFalse(model.uiState.value.recording)
                assertEquals(0, f.requests.size); f.capture.assertCounts(0)
                if (phase == "write") {
                    assertFalse(model.uiState.value.technicalRetryPending)
                    withContext(Dispatchers.Main) { model.onAction(f.startAction()) }
                    withTimeout(5_000) { model.uiState.first { it.recording || it.failureMessageVisible || it.progressFailed } }
                    assertTrue(model.uiState.value.recording)
                    withContext(Dispatchers.Main) { model.onAction(if (f.reading) PatternSpeakingAction.ReadingFinished(f.exampleId)
                        else PatternSpeakingAction.FinishResponseRequested) }
                    f.idle(model)
                    assertTrue(model.uiState.value.technicalRetryPending)
                    assertEquals(f.exampleId, model.uiState.value.feedbackExampleId)
                    assertFalse(model.uiState.value.technicalRetryBlocked)
                } else if (phase == "read") {
                    assertTrue(model.uiState.value.technicalRetryPending)
                    assertEquals(f.exampleId, model.uiState.value.feedbackExampleId)
                    // A current-example explicit action, never restoration itself, retries the frozen body.
                    withContext(Dispatchers.Main) { model.onAction(if (f.reading) PatternSpeakingAction.StartReadingRequested(f.exampleId)
                        else PatternSpeakingAction.RetryResponseRequested) }
                    f.idle(model)
                    assertFalse(model.uiState.value.technicalRetryPending)
                    assertEquals(f.feedback, model.uiState.value.feedbackZh)
                }
                val consumed = phase != "write"
                val saved = f.awaitProgress(consumed)
                f.assertCurrentExample(model.uiState.value, consumed)
                assertEquals(if (f.reading) consumed.toString() else "true", saved["example.0.read"])
                assertEquals(if (f.reading) "false" else "true", saved["example.1.read"])
                assertEquals((!f.reading && consumed).toString(), saved["responseCompleted"])
                assertEquals("0", saved["patternIndex"]); assertEquals("0", saved["responseIndex"])
                assertEquals("0", saved["processedPatternCount"]); assertEquals("false", saved["completed"])
                if (consumed) assertEquals(f.feedback, saved["feedback"])
                val stored = f.loaded()
                f.assertIdentity(stored.snapshot)
                if (consumed) {
                    assertEquals(PatternRecoveryPhase.Delivered, stored.snapshot.phase)
                    assertNull(stored.snapshot.requestBody)
                    assertEquals(PatternPracticeFeedbackResult.Ready(PatternPracticeFeedback(f.feedback, false, f.examples[0].targetResponseEn)), stored.snapshot.result)
                    assertEquals(previousWrite!!.getString("attemptSha"), sha(stored.snapshot.attemptId))
                    assertFalse(f.repository.hasPendingFeedbackRetry())
                } else {
                    assertEquals(PatternRecoveryPhase.Pending, stored.snapshot.phase)
                    assertEquals(f.requests.single().bodySha, sha(requireNotNull(stored.snapshot.requestBody)))
                    assertEquals(f.requests.single().commandSha, sha(JSONObject(stored.snapshot.requestBody!!).getString("commandId")))
                    assertNull(stored.snapshot.result)
                }
                assertEquals(before, f.sqlHash(protectedOnly = true))
                f.assertCheckpoint()
                f.capture.assertCounts(if (phase == "write") 1 else 0)
                assertEquals(if (phase == "verify") 0 else 1, f.requests.size)
                when (phase) {
                    "write", "read" -> {
                        val request = f.requests.single()
                        f.context.writeEvidence(phase, JSONObject().put("pid", Process.myPid())
                            .put("bodySha", request.bodySha).put("commandSha", request.commandSha)
                            .put("attemptSha", sha(stored.snapshot.attemptId)).put("protectedSqlSha", before)
                            .put("allSqlSha", f.sqlHash(protectedOnly = false)).put("payloadSha", f.context.payloadSha(f.account))
                            .put("httpCount", f.requests.size).put("startCount", f.capture.starts.get()).put("finishCount", f.capture.finishes.get()))
                    }
                    "verify" -> {
                        // Consumed feedback cannot silently re-record, resubmit, or advance this example.
                        withContext(Dispatchers.Main) { model.onAction(f.startAction()) }
                        f.idle(model)
                        f.assertCurrentExample(model.uiState.value, true)
                        f.capture.assertCounts(0); assertEquals(0, f.requests.size)
                        assertEquals(f.context.evidence("read").getString("allSqlSha"), f.sqlHash(protectedOnly = false))
                        assertEquals(f.context.evidence("read").getString("payloadSha"), f.context.payloadSha(f.account))
                        verified = true
                    }
                }
                println("PATTERN_UNKNOWN phase=$phase mode=$mode pid=${Process.myPid()} http=${f.requests.size} start=${f.capture.starts.get()} finish=${f.capture.finishes.get()}")
            } finally {
                // write/read do not dispose the VM/repository or clear the session to simulate a kill.
                // A failed phase retains only this UUID fixture for diagnosis; successful verify owns cleanup.
                if (verified) f.closeVerified()
            }
        }
    }

    private data class RequestHashes(val bodySha: String, val commandSha: String)
    private class Fixture(val phase: String, mode: String, val runId: String) {
        val reading = mode == "reading"
        val context = ProcessContext(InstrumentationRegistry.getInstrumentation().targetContext, runId, mode, phase == "write")
        val account = "pattern-unknown-account-$runId"; val sentence = "sentence-$runId"
        val chapter = FixedLearningChapter("chapter-$runId", 1, listOf(FixedLearningSentence(sentence, "I used to write by hand.", "我过去常常手写。", true)))
        val pattern = LockedSpeakingPattern("pattern-$runId", sentence, "used to + verb", "过去的习惯")
        val exampleId = "e1-$runId"
        val examples = listOf(PatternPracticeExample(exampleId, "谈放学后的习惯", "What did you do after school?", "I used to read after school.", "我过去常在放学后阅读。"),
            PatternPracticeExample("e2-$runId", "谈周末习惯", "What did you do at weekends?", "I used to walk at weekends.", "我过去常在周末散步。"))
        val practice = PatternPracticeSet(pattern.patternId, examples)
        val runtime = SessionRuntime().apply { update("ISOLATED_PROCESS_TOKEN", account) }
        val feedback = "隔离训练反馈：句意和结构完整。"
        private val namespace = "reallanguage.pattern-speaking.payload.test.${runId.replace("-", "") }"
        private val keys = AndroidPatternSpeakingPayloadKeys(namespace, canUseAccount = { it == fixedSourceAccountHash(account) })
        val capture = ProcessCapture(context, phase == "write")
        val requests = CopyOnWriteArrayList<RequestHashes>()
        private val models = ViewModelStore()
        private fun journal() = AndroidPatternSpeakingRecoveryJournal(context, runtime, chapter.chapterId, sentence, chapter.sentences.single().textEn, pattern, practice, keys)
        val repository = AndroidPatternSpeakingRepository(HttpTransport { request ->
            assertTrue("Only write and explicit read retry may send", phase in setOf("write", "read"))
            assertEquals("POST", request.method); assertEquals("/api/v1/learning/pattern-practice-feedback", request.path)
            val json = JSONObject(request.body)
            assertEquals(if (reading) "READING" else "RESPONSE", json.getString("mode"))
            assertEquals(exampleId, json.getString("exampleId")); assertEquals(pattern.patternId, json.getString("patternId"))
            assertEquals(examples[0].targetResponseEn, json.getString("targetTextEn")); assertTrue(json.getBoolean("targetVisible"))
            val hashes = RequestHashes(sha(request.body), sha(json.getString("commandId")))
            requests += hashes; assertEquals(1, requests.size)
            if (phase == "write") HttpResponse(503, "") else {
                val original = context.evidence("write")
                assertEquals(original.getString("bodySha"), hashes.bodySha); assertEquals(original.getString("commandSha"), hashes.commandSha)
                HttpResponse(200, JSONObject().put("status", "READY").put("feedbackZh", feedback)
                    .put("transcript", examples[0].targetResponseEn).put("repeatRecommended", false).put("confidence", 0.95).toString())
            }
        }, runtime, chapter, capture, JsonPatternSpeakingWireCodec(), recoveryJournalFactory = { fixedPattern, savedPractice ->
            assertEquals(pattern, fixedPattern); assertEquals(practice, savedPractice); journal()
        })
        fun progress() = AndroidLearningProgressStore(context, runtime, chapter.chapterId, sentence, "LR-07")
        suspend fun model() = withContext(Dispatchers.Main) {
            PatternSpeakingViewModel(chapter, sentence, listOf(pattern), repository, progress()).also { models.put("process-pattern", it) }
        }
        suspend fun idle(model: PatternSpeakingViewModel) { withTimeout(5_000) { model.uiState.first { !it.submitting } } }
        fun startAction(): PatternSpeakingAction = if (reading) PatternSpeakingAction.StartReadingRequested(exampleId) else PatternSpeakingAction.StartResponseRequested
        suspend fun loaded() = journal().load() as PatternSpeakingRecoveryLoad.Loaded
        fun assertIdentity(snapshot: PatternSpeakingRecoverySnapshot) {
            assertEquals(exampleId, snapshot.exampleId); assertEquals(if (reading) PatternFeedbackMode.Reading else PatternFeedbackMode.Response, snapshot.mode)
        }
        fun assertCurrentExample(state: PatternSpeakingUiState, consumed: Boolean) {
            assertFalse(state.submitting); assertFalse(state.progressFailed); assertFalse(state.recording); assertFalse(state.completed)
            assertEquals(if (reading) PatternSpeakingStage.READING else PatternSpeakingStage.RESPONDING, state.stage)
            assertEquals(examples, state.examples); assertEquals(pattern, state.currentPattern)
            assertEquals(0, state.responseIndex); assertEquals(0, state.patternIndex); assertEquals(emptySet<String>(), state.processedPatternIds)
            assertEquals(if (reading) { if (consumed) setOf(exampleId) else emptySet() } else examples.map { it.exampleId }.toSet(), state.completedReadingExampleIds)
            assertEquals(!reading && consumed, state.currentResponseCompleted)
            assertEquals(!consumed, state.technicalRetryPending)
            if (consumed) { assertEquals(feedback, state.feedbackZh); assertFalse(state.technicalRetryBlocked) }
        }
        suspend fun awaitProgress(consumed: Boolean): Map<String, String> = withTimeout(5_000) {
            var saved = progress().read()
            while (saved?.get(if (reading) "example.0.read" else "responseCompleted") != consumed.toString() ||
                (consumed && saved?.get("feedback") != feedback)) { delay(10); saved = progress().read() }
            requireNotNull(saved)
        }
        suspend fun seed() {
            FormationDatabase(context).use { helper ->
                val db = helper.writableDatabase
                listOf(account, "foreign-$runId").forEach { owner ->
                    db.execSQL("insert into formation_flows values (?,?,'FIXED','DIARY','隔离句式进程恢复')", arrayOf(owner, "flow-$owner"))
                    db.execSQL("insert into chapters values (?,?,?,0,'IN_PROGRESS')", arrayOf(owner, "flow-$owner", chapter.chapterId))
                    db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?,?,?,0,?,?,'FOCUS')", arrayOf(owner, chapter.chapterId, sentence, chapter.sentences[0].textZh, chapter.sentences[0].textEn))
                    val evidence = JSONObject().put("version", 1).put("startUtf16", 2).put("endUtf16", 15).put("surfaceForm", "used to write")
                        .put("normalizedForm", pattern.structure).put("allowedForms", JSONArray().put("used to write"))
                    db.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary,source_evidence) values (?,?,?,?,'PATTERN',?,?,'','',?)", arrayOf(owner, chapter.chapterId, pattern.patternId, sentence, pattern.structure, pattern.explanationZh, evidence.toString()))
                    db.execSQL("insert into pattern_items(account_id,item_id,semantic_pattern_key,structure_en,meaning_zh,writing_status,speaking_status,writing_streak,speaking_streak,active_validation) values(?,?,?,?,'已有习惯句式','LEARNING','LEARNING',1,1,1)", arrayOf(owner, "known-$runId", "known-key-$runId", pattern.structure))
                    db.execSQL("insert into learning_checkpoints values (?,?,'LR-07',?)", arrayOf(owner, chapter.chapterId, sentence))
                    val state = PatternSpeakingUiState(chapter, sentence, listOf(pattern), stage = if (reading) PatternSpeakingStage.READING else PatternSpeakingStage.RESPONDING,
                        examples = examples, completedReadingExampleIds = if (reading) emptySet() else examples.map { it.exampleId }.toSet())
                    db.execSQL("insert into learning_module_progress values (?,?,?,'LR-07',?)", arrayOf(owner, chapter.chapterId, sentence, JSONObject(encodePatternProgress(state)).toString()))
                }
            }
            assertNotNull(progress().read())
        }
        fun assertCheckpoint() = FormationDatabase(context).use { helper -> helper.readableDatabase.rawQuery(
            "select module_id,step_id from learning_checkpoints where account_id=? and chapter_id=?", arrayOf(account, chapter.chapterId),
        ).use { assertTrue(it.moveToFirst()); assertEquals("LR-07", it.getString(0)); assertEquals(sentence, it.getString(1)); assertFalse(it.moveToNext()) } }
        fun sqlHash(protectedOnly: Boolean) = FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            val rows = tables.flatMap { table ->
                check(table.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")))
                db.rawQuery("select * from \"$table\"", null).use { c -> buildList {
                    add("TABLE:$table:${c.columnNames.joinToString(",")}")
                    while (c.moveToNext()) {
                        fun value(name: String): String? = c.getColumnIndex(name).takeIf { it >= 0 }?.let(c::getString)
                        val own = value("account_id") == account && value("chapter_id") == chapter.chapterId && value("module_id") == "LR-07"
                        val allowed = own && ((table == "learning_module_progress" && value("sentence_id") == sentence) ||
                            (table == "learning_checkpoints" && value("step_id") == sentence))
                        if (!protectedOnly || !allowed) add(table + (0 until c.columnCount).joinToString("") { column ->
                            val text = when (c.getType(column)) { Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_BLOB -> c.getBlob(column).joinToString("") { "%02x".format(it) }; else -> c.getString(column) }
                            text?.let { "${it.length}:$it" } ?: "-1:"
                        })
                    }
                } }
            }.sorted()
            sha(rows.joinToString("\n"))
        }
        suspend fun closeVerified() {
            withContext(Dispatchers.Main) { models.clear() }; runtime.clear()
            context.deleteVerifiedFixture(namespace, fixedSourceAccountHash(account))
        }
    }

    private class ProcessCapture(private val context: Context, private val permitted: Boolean) : ReadSpeakAudioCapture {
        val starts = AtomicInteger(); val finishes = AtomicInteger(); private val cancels = AtomicInteger()
        override fun start(): Boolean { val count = starts.incrementAndGet(); check(permitted && count == 1); return true }
        override fun finish(): CapturedReadSpeakAudio {
            val count = finishes.incrementAndGet()
            check(permitted && starts.get() == 1 && count == 1)
            return CapturedReadSpeakAudio(File(context.cacheDir, "original-not-audio.m4a").also {
                check(it.createNewFile()); it.writeText("NOT_AUDIO_PATTERN_PROCESS_FIXTURE")
            }, "audio/mp4")
        }
        override fun cancel() { cancels.incrementAndGet() }
        fun assertCounts(expected: Int) { assertEquals(expected, starts.get()); assertEquals(expected, finishes.get()); assertEquals(0, cancels.get()) }
    }

    private class ProcessContext(base: Context, private val runId: String, private val mode: String, create: Boolean) : ContextWrapper(base) {
        private val root = base.cacheDir.canonicalFile
        private val directory = File(root, "pattern-speaking-unknown-$runId")
        private val database = File(directory, "formation.db"); private val files = File(directory, "files"); private val cache = File(directory, "cache")
        init {
            check(directory.canonicalFile == directory.absoluteFile && directory.parentFile == root)
            if (create) { check(directory.mkdir()); check(files.mkdir()); check(cache.mkdir()) }
            else check(directory.isDirectory && database.isFile && files.isDirectory && cache.isDirectory)
            listOf(database, files, cache).forEach { check(it.canonicalFile == it.absoluteFile) }
        }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = files
        override fun getCacheDir() = cache
        override fun getNoBackupFilesDir() = files
        override fun getDatabasePath(name: String): File { check(name == "real-language-learning.db"); return database }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        private fun evidenceFile(phase: String): File {
            check(phase in setOf("write", "read"))
            return File(directory, "$phase-evidence.json").also { check(it.canonicalFile == it.absoluteFile) }
        }
        fun evidence(phase: String): JSONObject = JSONObject(evidenceFile(phase).also { check(it.length() in 1..16_384) }.readText()).also {
            check(it.getInt("version") == 1 && it.getString("runSha") == sha(runId) && it.getString("mode") == mode)
        }
        fun writeEvidence(phase: String, data: JSONObject) {
            val file = evidenceFile(phase); check(file.createNewFile()) { "A phase must not overwrite prior evidence" }
            val bytes = data.put("version", 1).put("runSha", sha(runId)).put("mode", mode).toString().toByteArray(Charsets.UTF_8)
            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        }
        fun payloadSha(account: String): String {
            val owned = accountLearningAttemptsDirectory(files, account)
            check(owned.canonicalPath.startsWith(files.canonicalPath + File.separator))
            val payload = owned.walkTopDown().filter { it.isFile && it.extension == "payload" }.single()
            check(payload.canonicalPath.startsWith(owned.canonicalPath + File.separator)); return sha(payload.readBytes())
        }
        fun deleteVerifiedFixture(namespace: String, accountHash: String) {
            check(namespace == "reallanguage.pattern-speaking.payload.test.${runId.replace("-", "")}")
            check(accountHash.matches(Regex("[a-f0-9]{64}")))
            val alias = "$namespace.$accountHash.v1"
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias); check(!containsAlias(alias)) }
            check(directory.name == "pattern-speaking-unknown-$runId" && directory.canonicalFile.parentFile == root)
            check(deleteDatabase("real-language-learning.db")); check(directory.deleteRecursively())
        }
    }
    private companion object {
        fun sha(text: String) = sha(text.toByteArray(Charsets.UTF_8))
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
