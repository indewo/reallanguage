package com.reallanguage.app.core.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.*
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.learning.data.*
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Ordinary LR09 Graph/VM/SQLite and real test Keystore. NOT_AUDIO and HTTP are isolated.
 * The first two methods prove new-Graph wiring, NOT process death. The opt-in method requires
 * the runner to end, external force-stop, and a distinct process for each write/read/verify. */
class SceneResponseUnknownProcessRecoveryTest {
    @Test fun ordinaryGraphRestoresUnknownTranscriptionWithoutNewCapture() = sameProcess("transcription")
    @Test fun ordinaryGraphRestoresUnknownEvaluationAndSavesOnlyItsSceneDraft() = sameProcess("evaluation")

    private fun sameProcess(mode: String) = runBlocking { withContext(Dispatchers.IO) {
        val runId = UUID.randomUUID().toString()
        for (phase in listOf("write", "read", "verify")) runPhase(phase, mode, runId, requireNewPid = false)
    } }

    @Test fun unknownOriginalRequestSurvivesThreeDistinctInstrumentationProcesses() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("sceneUnknownPhase")
        assumeTrue(phase in setOf("write", "read", "verify"))
        val mode = requireNotNull(args.getString("sceneUnknownMode")).also { require(it in setOf("transcription", "evaluation")) }
        val runId = requireNotNull(args.getString("runId")).also { require(UUID.fromString(it).toString() == it) }
        withContext(Dispatchers.IO) { runPhase(requireNotNull(phase), mode, runId, requireNewPid = true) }
    }

    private suspend fun runPhase(phase: String, mode: String, runId: String, requireNewPid: Boolean) {
        val f = Fixture(phase, mode, runId)
        var verified = false
        try {
            if (phase == "write") f.seed()
            val original = if (phase == "write") null else f.context.evidence("write")
            val previous = if (phase == "verify") f.context.evidence("read") else original
            if (original != null) {
                if (requireNewPid) assertNotEquals("External force-stop is required between phases", original.getInt("pid"), Process.myPid())
                assertEquals(original.getString("protectedSqlSha"), f.sqlHash(protectedOnly = true))
                assertEquals(previous!!.getString("allSqlSha"), f.sqlHash(protectedOnly = false))
                assertEquals(previous.getString("payloadSha"), f.context.payloadSha(f.account))
                if (phase == "verify" && requireNewPid) {
                    assertNotEquals(original.getInt("pid"), previous.getInt("pid"))
                    assertNotEquals(previous.getInt("pid"), Process.myPid())
                }
            }
            val before = f.sqlHash(protectedOnly = true)
            val model = f.model()
            f.idle(model)
            assertFalse(model.uiState.value.progressFailed)
            assertFalse(model.uiState.value.recording)
            assertEquals(f.task, model.uiState.value.currentTask)
            assertEquals(f.round, model.uiState.value.roundIndex)
            assertEquals(0, f.requests.size); f.assertCaptureCount(0)
            when (phase) {
                "write" -> {
                    f.action(model, SceneResponseAction.StartRequested)
                    withTimeout(5_000) { model.uiState.first { it.recording || it.captureFailed || it.progressFailed } }
                    assertTrue(model.uiState.value.recording)
                    f.action(model, SceneResponseAction.FinishedRequested); f.idle(model)
                    if (f.evaluation) {
                        assertTrue(model.uiState.value.transcriptReady)
                        f.action(model, SceneResponseAction.TranscriptChanged("  $TEXT  "))
                        f.action(model, SceneResponseAction.ConfirmTranscriptRequested); f.idle(model)
                    }
                    assertTrue(model.uiState.value.technicalRetryPending)
                    assertFalse(model.uiState.value.technicalRetryBlocked)
                }
                "read" -> {
                    assertTrue("Restoration itself must not send the paid request", model.uiState.value.technicalRetryPending)
                    if (f.evaluation) assertEquals(TEXT, model.uiState.value.transcript)
                    // Same-frame duplicate actions must not queue a second retry or a fresh recording.
                    withContext(Dispatchers.Main) { repeat(3) { model.onAction(SceneResponseAction.RetryRequested) } }
                    f.idle(model)
                    assertFalse(model.uiState.value.technicalRetryPending)
                    assertFalse(model.uiState.value.captureFailed)
                }
                "verify" -> {
                    assertFalse(model.uiState.value.technicalRetryPending)
                    assertFalse(model.uiState.value.technicalRetryBlocked)
                }
            }
            val recordingSha = original?.getString("recordingSha") ?: sha(requireNotNull(f.repository.currentRecordingId()))
            val saved = f.awaitProgress(model, recordingSha)
            val journalResult = f.journal().load()
            // Once the final result has been saved, current LR09 access is deliberately closed.
            // Verify that SQL can restore the completed VM, not that old audio stays readable.
            val loaded = if (f.evaluation && phase != "write") {
                assertEquals(SceneResponseRecoveryLoad.Unavailable, journalResult); null
            } else (journalResult as SceneResponseRecoveryLoad.Loaded).also {
                assertEquals(f.task.taskId, it.snapshot.taskId); assertEquals(f.round, it.snapshot.roundIndex)
                assertEquals(recordingSha, sha(it.snapshot.recordingId))
            }
            if (phase == "write") {
                requireNotNull(loaded)
                assertEquals(if (f.evaluation) SceneResponseRecoveryPhase.FeedbackPending else SceneResponseRecoveryPhase.TranscriptionPending, loaded.snapshot.phase)
                val frozen = if (f.evaluation) loaded.snapshot.feedbackBody else loaded.snapshot.transcriptionBody
                assertEquals(f.requests.last().bodySha, sha(requireNotNull(frozen)))
                assertEquals(f.requests.last().commandSha, sha(JSONObject(frozen!!).getString("commandId")))
            } else {
                assertEquals(TEXT, model.uiState.value.transcript.trim())
                if (f.evaluation) {
                    assertTrue(model.uiState.value.completed)
                    assertEquals(SceneEvaluationDecision.PASS, model.uiState.value.finalDecision)
                    assertEquals(FEEDBACK, model.uiState.value.feedbackZh)
                    assertEquals("true", saved["completed"])
                } else {
                    requireNotNull(loaded)
                    assertEquals(SceneResponseRecoveryPhase.TranscriptionDelivered, loaded.snapshot.phase)
                    assertTrue(model.uiState.value.transcriptReady)
                    assertFalse(model.uiState.value.completed)
                    val body = requireNotNull(loaded.snapshot.transcriptionBody)
                    assertEquals(original!!.getString("bodySha"), sha(body))
                    val result = JsonSceneResponseWireCodec().transcriptionResult(requireNotNull(loaded.snapshot.transcriptionResultBody)) as SpeakingTranscriptionResult.Ready
                    assertEquals(TEXT, result.value.transcript)
                    assertTrue(result.value.lowConfidenceSegments.isNotEmpty())
                }
            }
            f.assertDraftCount(if (f.evaluation && phase != "write") 1 else 0)
            assertEquals(before, f.sqlHash(protectedOnly = true))
            f.assertCaptureCount(if (phase == "write") 1 else 0)
            assertEquals(when (phase) { "write" -> if (f.evaluation) 2 else 1; "read" -> 1; else -> 0 }, f.requests.size)
            if (phase != "verify") {
                val request = f.requests.last()
                f.context.writeEvidence(phase, JSONObject().put("pid", Process.myPid())
                    .put("bodySha", request.bodySha).put("commandSha", request.commandSha)
                    .put("recordingSha", recordingSha).put("protectedSqlSha", before)
                    .put("allSqlSha", f.sqlHash(false)).put("payloadSha", f.context.payloadSha(f.account))
                    .put("httpCount", f.requests.size).put("startCount", f.starts.get()).put("finishCount", f.finishes.get()))
            } else {
                // Read-only reconstruction of the accepted result cannot generate, recapture, or archive.
                assertEquals(previous!!.getString("allSqlSha"), f.sqlHash(false))
                assertEquals(previous.getString("payloadSha"), f.context.payloadSha(f.account))
                verified = true
            }
            println("SCENE_UNKNOWN phase=$phase mode=$mode pid=${Process.myPid()} http=${f.requests.size} start=${f.starts.get()} finish=${f.finishes.get()}")
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("sceneRecoveryPid", Process.myPid().toString())
                putString("sceneRecoveryPhase", phase)
                putString("sceneRecoveryMode", mode)
            })
        } finally {
            // Process protocol deliberately does not dispose during write/read to imitate a kill.
            // The non-process controls do dispose their old Graph VM before recreating it.
            if (!requireNewPid) f.clearModels()
            if (verified) f.closeVerified()
        }
    }

    private data class RequestHashes(val bodySha: String, val commandSha: String)
    private class Fixture(val phase: String, val mode: String, val id: String) {
        val evaluation = mode == "evaluation"
        val round = if (evaluation) 2 else 0 // Final-round seed, not proof of the preceding two training rounds.
        val context = ProcessContext(InstrumentationRegistry.getInstrumentation().targetContext, id, mode, phase == "write")
        val account = "scene-process-$id"
        val sentence = "sentence-$id"
        val chapter = FixedLearningChapter("chapter-$id", 1, listOf(FixedLearningSentence(sentence, TEXT, "我想要一杯绿茶。", true)))
        val task = FixedSceneResponseTask("task-$id", sentence, "At a cafe", listOf(SceneDialogueLine("AI", "What would you like?")), TEXT)
        val route = HomeDiaryRoute.SceneResponse(chapter, sentence, "隔离说明", emptyList())
        val runtime = SessionRuntime().apply { update("ISOLATED_SCENE_PROCESS_TOKEN", account) }
        val namespace = "reallanguage.formation.payload.test.${id.replace("-", "") }"
        val keys = AndroidFormationPayloadKeys(namespace, canUseAccount = { it == fixedSourceAccountHash(account) })
        val requests = CopyOnWriteArrayList<RequestHashes>()
        val starts = AtomicInteger(); val finishes = AtomicInteger()
        val models = ViewModelStore()
        lateinit var repository: AndroidSceneResponseRepository
        private var originalAudioSha: String? = null
        fun journal() = AndroidSceneResponseRecoveryJournal(context, runtime, chapter, task, round, keys)
        fun progress() = AndroidLearningProgressStore(context, runtime, chapter.chapterId, sentence, "LR-09")
        private fun graph() = ProductionAppGraph(context, runtime, HttpTransport { request ->
            check(phase != "verify" && request.method == "POST")
            check(request.path == TRANSCRIBE || evaluation && request.path == EVALUATE)
            val json = JSONObject(request.body)
            assertEquals(chapter.chapterId, json.getString("chapterId")); assertEquals(sentence, json.getString("sentenceId"))
            val hashes = RequestHashes(sha(request.body), sha(json.getString("commandId")))
            requests += hashes
            val unknownPath = if (evaluation) EVALUATE else TRANSCRIBE
            if (phase == "read") {
                assertEquals(unknownPath, request.path)
                assertEquals(context.evidence("write").getString("bodySha"), hashes.bodySha)
                assertEquals(context.evidence("write").getString("commandSha"), hashes.commandSha)
                assertEquals(1, requests.size)
            }
            if (request.path == TRANSCRIBE) originalAudioSha = sha(json.getString("audioBase64"))
            else {
                assertEquals(task.taskId, json.getString("taskId")); assertEquals(2, json.getInt("roundIndex"))
                assertEquals(TEXT, json.getString("confirmedTranscript")); assertTrue(json.getBoolean("userTranscriptConfirmed"))
                assertTrue(json.getBoolean("targetHidden"))
                if (phase == "write") assertEquals(originalAudioSha, sha(json.getString("audioBase64")))
            }
            if (phase == "write" && request.path == unknownPath) HttpResponse(503, "")
            else if (request.path == TRANSCRIBE) HttpResponse(200, JSONObject().put("transcript", TEXT).put("confidence", 0.8)
                .put("lowConfidenceSegments", JSONArray().put("green tea")).toString())
            else HttpResponse(200, JSONObject().put("decision", "PASS").put("correctTurn", true)
                .put("targetSentenceComplete", true).put("pronunciationIntelligible", true)
                .put("pronunciationConfidence", 0.95).put("feedbackZh", FEEDBACK).toString())
        }, object : SessionCredentialStore {
            override suspend fun load(): StoredSession? = null
            override suspend fun save(session: StoredSession) = error("No login is part of this isolated fixture")
            override suspend fun clear() = error("No logout is part of this isolated fixture")
        }, sceneResponseCaptureFactory = { _, _ -> object : ReadSpeakAudioCapture {
            override fun start(): Boolean { check(phase == "write"); assertEquals(1, starts.incrementAndGet()); return true }
            override fun finish(): CapturedReadSpeakAudio {
                check(phase == "write"); assertEquals(1, finishes.incrementAndGet())
                return CapturedReadSpeakAudio(File(context.cacheDir, "not-audio.m4a").also {
                    check(it.createNewFile()); it.writeText("NOT_AUDIO_SCENE_PROCESS_FIXTURE")
                }, "audio/mp4")
            }
            override fun cancel() = Unit
        } }, sceneResponseRecoveryKeys = keys)
        suspend fun model(): SceneResponseViewModel {
            val graph = graph(); repository = graph.sceneResponseSession()
            assertTrue("Ordinary Production Graph must inject the guarded recovery factory", repository.requiresAttemptRecovery)
            return withContext(Dispatchers.Main) {
                ViewModelProvider(models, graph.sceneResponseFactory(route, repository))[SceneResponseViewModel::class.java]
            }
        }
        suspend fun action(model: SceneResponseViewModel, action: SceneResponseAction) = withContext(Dispatchers.Main) { model.onAction(action) }
        suspend fun idle(model: SceneResponseViewModel) { withTimeout(5_000) { model.uiState.first { !it.loading && !it.submitting && !it.transcribing } } }
        suspend fun awaitProgress(model: SceneResponseViewModel, recordingSha: String): Map<String, String> = withTimeout(5_000) {
            while (true) {
                val saved = progress().read()
                if (saved != null && saved["transcript"] == model.uiState.value.transcript &&
                    saved["completed"] == model.uiState.value.completed.toString() &&
                    saved["recordingId"]?.let(::sha) == recordingSha && saved["roundIndex"] == round.toString() &&
                    saved["feedback"] == model.uiState.value.feedbackZh.orEmpty() &&
                    saved["finalDecision"] == model.uiState.value.finalDecision?.name.orEmpty()) return@withTimeout saved
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }
        fun assertCaptureCount(expected: Int) { assertEquals(expected, starts.get()); assertEquals(expected, finishes.get()) }
        fun seed() = FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            listOf(account, "foreign-$id").forEach { owner ->
                db.execSQL("insert into formation_flows values (?,?,'FIXED','DIARY','隔离场景恢复')", arrayOf(owner, "flow-$owner"))
                db.execSQL("insert into chapters values (?,?,?,0,'IN_PROGRESS')", arrayOf(owner, "flow-$owner", chapter.chapterId))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?,?,?,0,?,?,'FOCUS')", arrayOf(owner, chapter.chapterId, sentence, chapter.sentences[0].textZh, TEXT))
                db.execSQL("insert into scene_response_tasks(account_id,chapter_id,task_id,sentence_id,sentence_order,background_en,context_lines,target_sentence_en) values (?,?,?,?,0,?,?,?)", arrayOf(owner, chapter.chapterId, task.taskId, sentence, task.backgroundEn, "AI\u001fWhat would you like?", TEXT))
                db.execSQL("insert into learning_checkpoints values (?,?,'LR-09',?)", arrayOf(owner, chapter.chapterId, sentence))
                val state = SceneResponseUiState(chapter, listOf(task), roundIndex = round, audioMuted = true)
                db.execSQL("insert into learning_module_progress values (?,?,?,'LR-09',?)", arrayOf(owner, chapter.chapterId, sentence, JSONObject(encodeSceneProgress(state, null)).toString()))
                if (owner != account) db.execSQL("insert into scene_response_result_drafts values (?,?,?,0)", arrayOf(owner, chapter.chapterId, sentence))
            }
        }
        fun assertDraftCount(count: Int) = FormationDatabase(context).use { helper ->
            helper.readableDatabase.rawQuery("select passed from scene_response_result_drafts where account_id=? and chapter_id=?", arrayOf(account, chapter.chapterId)).use {
                assertEquals(count, it.count); if (count == 1) { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            }
        }
        fun sqlHash(protectedOnly: Boolean) = FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            val rows = tables.flatMap { table ->
                check(table.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")))
                db.rawQuery("select * from \"$table\"", null).use { c -> buildList {
                    add("TABLE:$table:${c.columnNames.joinToString(",")}")
                    while (c.moveToNext()) {
                        fun value(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }?.let(c::getString)
                        val own = value("account_id") == account && value("chapter_id") == chapter.chapterId
                        val allowed = own && value("sentence_id") == sentence &&
                            (table == "scene_response_result_drafts" || table == "learning_module_progress" && value("module_id") == "LR-09")
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
        suspend fun clearModels() = withContext(Dispatchers.Main) { models.clear() }
        suspend fun closeVerified() { clearModels(); runtime.clear(); context.deleteVerified(namespace, fixedSourceAccountHash(account)) }
    }

    private class ProcessContext(private val base: Context, private val id: String, private val kind: String, create: Boolean) : ContextWrapper(base) {
        private val root = base.cacheDir.canonicalFile
        private val directory = File(root, "scene-unknown-$id")
        private val database = File(directory, "formation.db"); private val files = File(directory, "files"); private val cache = File(directory, "cache")
        private val preferences = mutableSetOf<String>()
        init {
            check(directory.canonicalFile == directory.absoluteFile && directory.parentFile == root)
            if (create) { check(directory.mkdir()); check(files.mkdir()); check(cache.mkdir()) }
            else check(directory.isDirectory && database.isFile && files.isDirectory && cache.isDirectory)
        }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = files
        override fun getCacheDir() = cache
        override fun getNoBackupFilesDir() = files
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = "scene-unknown-$id-$name"; synchronized(preferences) { preferences.add(scoped) }
            return base.getSharedPreferences(scoped, mode)
        }
        override fun getDatabasePath(name: String): File {
            check(name == "real-language-learning.db"); check(database.canonicalFile == database.absoluteFile); return database
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        private fun evidenceFile(phase: String) = File(directory, "$phase-evidence.json").also { check(phase in setOf("write", "read") && it.canonicalFile == it.absoluteFile) }
        fun evidence(phase: String) = JSONObject(evidenceFile(phase).also { check(it.length() in 1..16_384) }.readText()).also {
            check(it.getInt("version") == 1 && it.getString("runSha") == sha(id) && it.getString("mode") == kind)
        }
        fun writeEvidence(phase: String, value: JSONObject) {
            val file = evidenceFile(phase); check(file.createNewFile())
            val bytes = value.put("version", 1).put("runSha", sha(id)).put("mode", kind).toString().toByteArray()
            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        }
        fun payloadSha(account: String): String {
            val owned = accountLearningAttemptsDirectory(files, account)
            check(owned.canonicalPath.startsWith(files.canonicalPath + File.separator))
            val payload = owned.walkTopDown().filter { it.isFile && it.extension == "payload" }.single()
            check(payload.canonicalPath.startsWith(owned.canonicalPath + File.separator)); return sha(payload.readBytes())
        }
        fun deleteVerified(namespace: String, accountHash: String) {
            check(namespace == "reallanguage.formation.payload.test.${id.replace("-", "")}")
            val alias = "$namespace.$accountHash.v1"
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias); check(!containsAlias(alias)) }
            synchronized(preferences) { preferences.toList() }.forEach { check(it.startsWith("scene-unknown-$id-")); base.deleteSharedPreferences(it) }
            check(directory.name == "scene-unknown-$id" && directory.canonicalFile.parentFile == root)
            check(deleteDatabase("real-language-learning.db")); check(directory.deleteRecursively())
        }
    }
    private companion object {
        const val TRANSCRIBE = "/api/v1/learning/speaking-transcriptions"
        const val EVALUATE = "/api/v1/learning/scene-response-evaluations"
        const val TEXT = "I would like a cup of green tea."
        const val FEEDBACK = "隔离评价：场景回应完整。"
        fun sha(text: String) = sha(text.toByteArray(Charsets.UTF_8))
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
