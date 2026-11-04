package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.fixedSourceAccountHash
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.FullSentenceSpeakingAction
import com.reallanguage.app.feature.learning.presentation.FullSentenceSpeakingViewModel
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in five-process protocol; the runner must end/force-stop each process between phases.
 * Synthetic NOT_AUDIO and isolated HTTP are not real pronunciation, AI or four-training-round evidence. */
class FullSpeakingUnknownProcessRecoveryTest {
    @Test fun unknownTrainingAsrAndEvaluationSurviveFiveSeparateProcesses() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val requestedPhase = args.getString("fullSpeakingUnknownPhase")
        assumeTrue(requestedPhase in PHASES)
        val phase = requireNotNull(requestedPhase)
        val runId = requireNotNull(args.getString("runId")).also {
            require(UUID.fromString(it).toString() == it) { "A canonical isolated UUID is required" }
        }
        withContext(Dispatchers.IO) {
            val f = Fixture(phase, runId)
            var verified = false
            try {
                f.assertPreviousProcessEvidence()
                val protectedBefore = f.sqlHash(protectedOnly = true)
                when (phase) {
                    "train" -> train(f)
                    "asr" -> asr(f)
                    "transcribed" -> transcribed(f)
                    "evaluate" -> evaluate(f)
                    "verify" -> verify(f)
                }
                assertEquals(protectedBefore, f.sqlHash(protectedOnly = true))
                f.assertCheckpoint()
                f.assertResult(if (phase == "verify") 1 else 0)
                f.capture.assertCounts(if (phase in setOf("train", "asr")) 1 else 0)
                assertEquals(if (phase == "asr") 2 else 1, f.requests.size)
                val sent = f.requests.last()
                f.context.writeEvidence(phase, JSONObject().put("pid", Process.myPid())
                    .put("bodySha", sent.bodySha).put("commandSha", sent.commandSha).put("audioSha", sent.audioSha)
                    .put("protectedSqlSha", protectedBefore).put("allSqlSha", f.sqlHash(protectedOnly = false))
                    .put("payloadSha", f.context.payloadSha(f.account)).put("http", f.requests.size)
                    .put("captureStart", f.capture.starts).put("captureFinish", f.capture.finishes))
                println("FULL_SPEAKING_UNKNOWN phase=$phase pid=${Process.myPid()} http=${f.requests.size} start=${f.capture.starts} finish=${f.capture.finishes}")
                verified = phase == "verify"
            } finally {
                // Earlier phases neither dispose nor clear their repositories to pretend that a process died.
                // A failed phase keeps only this UUID fixture for diagnosis; only final success cleans it.
                if (verified) {
                    withContext(Dispatchers.Main) { f.models.clear() }
                    f.runtime.clear()
                    f.context.deleteVerifiedFixture(f.namespace, fixedSourceAccountHash(f.account))
                }
            }
        }
    }

    private suspend fun train(f: Fixture) {
        val repository = f.repository()
        assertEquals(FullSpeakingRecovery.None, repository.restoreAttempt(f.sentence, 0))
        assertTrue(repository.prepareCapture(f.sentence, 0))
        assertTrue(repository.startCapture())
        assertEquals(ReadingFeedbackResult.Failed, repository.trainingFeedback(f.sentence, SpeakingTrainingMode.BILINGUAL_FOLLOW))
        assertTrue(repository.hasPendingTrainingRetry())
        val saved = f.loaded(0)
        assertEquals(FullSpeakingRecoveryPhase.TrainingPending, saved.snapshot.phase)
        f.requests.single().assertSnapshot(saved.snapshot)
        assertNull(saved.snapshot.training)
        assertEquals("0", f.progress().read()?.get("attemptIndex"))
    }

    private suspend fun asr(f: Fixture) {
        val training = f.repository()
        assertEquals(FullSpeakingRecovery.TrainingPending, training.restoreAttempt(f.sentence, 0))
        assertTrue(training.hasPendingTrainingRetry())
        assertEquals(0, f.requests.size); f.capture.assertCounts(0)
        val delivered = training.trainingFeedback(f.sentence, SpeakingTrainingMode.BILINGUAL_FOLLOW) as ReadingFeedbackResult.Ready
        assertEquals(TRAINING_FEEDBACK, delivered.feedback.feedbackZh)
        assertEquals(ReadingFeedbackAction.CONTINUE, delivered.feedback.action)
        assertEquals(FullSpeakingRecoveryPhase.TrainingDelivered, f.loaded(0).snapshot.phase)
        assertNull(f.loaded(0).snapshot.requestBody)
        assertEquals(FullSpeakingRecovery.TrainingReady(delivered.feedback), f.repository().restoreAttempt(f.sentence, 0))
        assertEquals(1, f.requests.size); f.capture.assertCounts(0)
        // Explicit fixture seed: rounds 1–3 are NOT exercised or claimed as completed by this protocol.
        assertTrue(f.progress().write(progressValues(4)))
        assertEquals(progressValues(4), f.progress().read())
        val repository = f.repository()
        assertEquals(FullSpeakingRecovery.None, repository.restoreAttempt(f.sentence, 4))
        assertTrue(repository.prepareCapture(f.sentence, 4)); assertTrue(repository.startCapture())
        assertEquals(SpeakingTranscriptionResult.Failed, repository.transcribeIndependentRetelling(f.sentence))
        assertTrue(repository.hasPendingTranscriptionRetry())
        val saved = f.loaded(4)
        assertEquals(FullSpeakingRecoveryPhase.TranscriptionPending, saved.snapshot.phase)
        f.requests.last().assertSnapshot(saved.snapshot)
    }

    private suspend fun transcribed(f: Fixture) {
        val repository = f.repository()
        assertEquals(FullSpeakingRecovery.TranscriptionPending, repository.restoreAttempt(f.sentence, 4))
        assertEquals(0, f.requests.size); f.capture.assertCounts(0)
        val result = repository.transcribeIndependentRetelling(f.sentence) as SpeakingTranscriptionResult.Ready
        assertEquals(TRANSCRIPT, result.value.transcript)
        assertEquals(LOW_CONFIDENCE, result.value.lowConfidenceSegments)
        assertEquals(0.64, result.value.confidence!!, 0.0)
        val saved = f.loaded(4)
        assertEquals(FullSpeakingRecoveryPhase.TranscriptionDelivered, saved.snapshot.phase)
        f.requests.single().assertSnapshot(saved.snapshot)
        assertEquals(result.value, saved.snapshot.transcription)
        assertEquals(FullSpeakingRecovery.TranscriptReady(result.value, saved.snapshot.recordingId),
            f.repository().restoreAttempt(f.sentence, 4))
        assertEquals(1, f.requests.size)
    }

    private suspend fun evaluate(f: Fixture) {
        val repository = f.repository()
        val restored = repository.restoreAttempt(f.sentence, 4) as FullSpeakingRecovery.TranscriptReady
        assertEquals(TRANSCRIPT, restored.value.transcript)
        assertEquals(LOW_CONFIDENCE, restored.value.lowConfidenceSegments)
        assertEquals(0, f.requests.size); f.capture.assertCounts(0)
        assertEquals(SpeakingEvaluationResult.Failed, repository.evaluateIndependentRetelling(f.sentence, CONFIRMED, true))
        assertTrue(repository.hasPendingEvaluationRetry())
        val saved = f.loaded(4)
        assertEquals(FullSpeakingRecoveryPhase.EvaluationPending, saved.snapshot.phase)
        f.requests.single().assertSnapshot(saved.snapshot)
        assertEquals(CONFIRMED, saved.snapshot.confirmedTranscript)
        assertTrue(saved.snapshot.answerAudioPlayed)
        assertEquals(restored.value, saved.snapshot.transcription)
        assertNotEquals(f.context.evidence("asr").getString("commandSha"), f.requests.single().commandSha)
    }

    private suspend fun verify(f: Fixture) {
        val repository = f.repository()
        assertEquals(FullSpeakingRecovery.EvaluationPending(CONFIRMED, true), repository.restoreAttempt(f.sentence, 4))
        assertEquals(0, f.requests.size); f.capture.assertCounts(0)
        val result = repository.evaluateIndependentRetelling(f.sentence, CONFIRMED, true) as SpeakingEvaluationResult.Ready
        assertEquals(SpeakingEvaluationDecision.PASS, result.candidate.decision)
        val delivered = f.loaded(4)
        assertEquals(FullSpeakingRecoveryPhase.EvaluationDelivered, delivered.snapshot.phase)
        assertNull(delivered.snapshot.requestBody)
        assertEquals(result.candidate, delivered.snapshot.evaluation)
        assertEquals(LOW_CONFIDENCE, delivered.snapshot.transcription!!.lowConfidenceSegments)
        f.assertResult(0) // Receiving a PASS candidate is not a local independent PASS decision.
        val model = f.model("first-local-save")
        assertTrue(model.uiState.value.evaluationReceived)
        assertNull(model.uiState.value.finalDecision)
        assertTrue(model.uiState.value.answerAudioPlayed)
        assertEquals(CONFIRMED, model.uiState.value.transcript)
        assertEquals(1, f.requests.size); f.capture.assertCounts(0)
        withContext(Dispatchers.Main) { model.onAction(FullSentenceSpeakingAction.ConfirmTranscriptRequested) }
        withTimeout(5_000) { model.uiState.first { !it.submitting && it.finalDecision != null } }
        assertEquals(SpeakingEvaluationDecision.FAIL, model.uiState.value.finalDecision)
        assertFalse(model.uiState.value.decisionSaveFailed)
        assertFalse(model.uiState.value.progressFailed)
        assertEquals(EVALUATION_FEEDBACK, model.uiState.value.feedbackZh)
        withTimeout(5_000) {
            while (f.progress().read()?.get("finalDecision") != "FAIL") delay(10)
        }
        val stored = requireNotNull(f.progress().read())
        assertEquals("PASS", stored["candidateDecision"])
        assertEquals("true", stored["answerAudioPlayed"])
        assertEquals(CONFIRMED, stored["transcript"])
        assertEquals("4", stored["attemptIndex"])
        f.assertResult(1)
        val beforeReadback = f.sqlHash(protectedOnly = false)
        val beforePayload = f.context.payloadSha(f.account)
        val next = f.model("independent-readback")
        assertEquals(SpeakingEvaluationDecision.FAIL, next.uiState.value.finalDecision)
        assertTrue(next.uiState.value.evaluationReceived)
        assertTrue(next.uiState.value.answerAudioPlayed)
        assertFalse(next.uiState.value.progressFailed)
        assertEquals(EVALUATION_FEEDBACK, next.uiState.value.feedbackZh)
        assertEquals(stored, f.progress().read())
        assertEquals(beforeReadback, f.sqlHash(protectedOnly = false))
        assertEquals(beforePayload, f.context.payloadSha(f.account))
        assertEquals(1, f.requests.size); f.capture.assertCounts(0)
    }

    private class Fixture(val phase: String, runId: String) {
        val context = ProcessContext(InstrumentationRegistry.getInstrumentation().targetContext, runId, phase == "train")
        val account = "full-unknown-account-$runId"
        val chapter = "full-unknown-chapter-$runId"
        val sentence = "full-unknown-sentence-$runId"
        val namespace = "reallanguage.full-speaking.payload.test.${runId.replace("-", "")}"
        val runtime = SessionRuntime().apply { update("ISOLATED_FULL_PROCESS_TOKEN", account) }
        private val keys = AndroidFullSpeakingPayloadKeys(namespace, canUseAccount = { it == fixedSourceAccountHash(account) })
        val models = ViewModelStore()
        val requests = mutableListOf<RequestEvidence>()
        val capture = ProcessCapture(context, phase, phase in setOf("train", "asr"))
        init { if (phase == "train") seed(runId) }
        fun journal(round: Int) = AndroidFullSpeakingRecoveryJournal(context, runtime, chapter, sentence, TARGET, round, keys)
        suspend fun loaded(round: Int) = journal(round).load() as FullSpeakingRecoveryLoad.Loaded
        fun progress() = AndroidLearningProgressStore(context, runtime, chapter, sentence, "LR-06")
        fun repository() = AndroidFullSentenceSpeakingRepository(context, HttpTransport { request -> respond(request) }, runtime,
            chapter, TARGET, capture, JsonFullSentenceSpeakingWireCodec(), recoveryJournalFactory = { id, round ->
                check(id == sentence); journal(round)
            })

        suspend fun model(name: String): FullSentenceSpeakingViewModel {
            val learningChapter = FixedLearningChapter(chapter, 1, listOf(FixedLearningSentence(sentence, TARGET, "我在咖啡馆感到平静。", true)))
            val model = withContext(Dispatchers.Main) {
                FullSentenceSpeakingViewModel(learningChapter, sentence, repository(), progress()).also { models.put(name, it) }
            }
            withTimeout(5_000) { model.uiState.first { !it.submitting } }
            assertFalse(model.uiState.value.progressFailed)
            assertFalse(model.uiState.value.recording)
            return model
        }

        fun assertPreviousProcessEvidence() {
            val previous = PHASES.take(PHASES.indexOf(phase))
            previous.forEach { assertNotEquals("Each phase must run in a different process", context.evidence(it).getInt("pid"), Process.myPid()) }
            previous.lastOrNull()?.let {
                val evidence = context.evidence(it)
                assertEquals(evidence.getString("allSqlSha"), sqlHash(protectedOnly = false))
                assertEquals(evidence.getString("protectedSqlSha"), sqlHash(protectedOnly = true))
                assertEquals(evidence.getString("payloadSha"), context.payloadSha(account))
            }
        }

        private fun respond(request: HttpRequest): HttpResponse {
            val index = requests.size
            val path = when {
                phase == "train" || (phase == "asr" && index == 0) -> "/api/v1/learning/reading-feedback"
                phase in setOf("asr", "transcribed") -> "/api/v1/learning/speaking-transcriptions"
                else -> "/api/v1/learning/full-sentence-speaking-evaluations"
            }
            assertTrue("No automatic/repeated HTTP is authorized", index < if (phase == "asr") 2 else 1)
            assertEquals("POST", request.method); assertEquals(path, request.path)
            val json = JSONObject(request.body)
            assertEquals(chapter, json.getString("chapterId")); assertEquals(sentence, json.getString("sentenceId"))
            assertEquals("audio/mp4", json.getString("audioMimeType"))
            val evidence = RequestEvidence(sha(request.body), sha(json.getString("commandId")), sha(json.getString("audioBase64")))
            requests += evidence
            val originalPhase = when {
                phase == "asr" && index == 0 -> "train"
                phase == "transcribed" -> "asr"
                phase == "verify" -> "evaluate"
                else -> null
            }
            originalPhase?.let {
                val original = context.evidence(it)
                assertEquals(original.getString("bodySha"), evidence.bodySha)
                assertEquals(original.getString("commandSha"), evidence.commandSha)
            }
            if (phase in setOf("evaluate", "verify")) {
                assertEquals(context.evidence("asr").getString("audioSha"), evidence.audioSha)
                assertEquals(CONFIRMED, json.getString("confirmedTranscript"))
                assertEquals(TARGET, json.getString("targetTextEn"))
                assertTrue(json.getBoolean("answerAudioPlayed")); assertTrue(json.getBoolean("targetHidden"))
                assertTrue(json.getBoolean("userTranscriptConfirmed"))
            } else if (originalPhase == null) {
                assertEquals(sha("NOT_AUDIO_FULL_PROCESS_$phase"), sha(Base64.getDecoder().decode(json.getString("audioBase64"))))
            }
            return when {
                phase == "asr" && index == 0 -> HttpResponse(200, JSONObject().put("feedbackZh", TRAINING_FEEDBACK)
                    .put("transcript", TARGET).put("contentComplete", true).put("feedbackConfidence", 0.9).put("action", "CONTINUE").toString())
                phase == "transcribed" -> HttpResponse(200, JSONObject().put("transcript", TRANSCRIPT)
                    .put("lowConfidenceSegments", org.json.JSONArray(LOW_CONFIDENCE)).put("confidence", 0.64).toString())
                phase == "verify" -> HttpResponse(200, JSONObject().put("decision", "PASS").put("completenessScore", 92)
                    .put("coreMeaningRecognizable", true).put("pronunciationIntelligible", true)
                    .put("pronunciationConfidence", 0.9).put("feedbackZh", EVALUATION_FEEDBACK).toString())
                else -> HttpResponse(503, "")
            }
        }

        private fun seed(runId: String) = FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            listOf(Triple(account, chapter, sentence), Triple("foreign-$runId", "foreign-chapter-$runId", "foreign-sentence-$runId")).forEach { (owner, c, s) ->
                db.execSQL("insert into formation_flows values (?,?,'FIXED','DIARY','隔离整句进程恢复')", arrayOf(owner, "flow-$owner"))
                db.execSQL("insert into chapters values (?,?,?,0,'IN_PROGRESS')", arrayOf(owner, "flow-$owner", c))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?,?,?,0,'我在咖啡馆感到平静。',?,'FOCUS')", arrayOf(owner, c, s, TARGET))
                db.execSQL("insert into learning_checkpoints values (?,?,'LR-06',?)", arrayOf(owner, c, s))
                db.execSQL("insert into learning_module_progress values (?,?,?,'LR-06',?)", arrayOf(owner, c, s, JSONObject(progressValues(0)).toString()))
            }
            db.execSQL("insert into full_speaking_result_drafts values (?,?,?,1)", arrayOf("foreign-$runId", "foreign-chapter-$runId", "foreign-sentence-$runId"))
            db.execSQL("insert into learning_module_progress values (?,?,?,'LR-05','{\"fixture\":\"protected\"}')", arrayOf(account, chapter, sentence))
        }

        fun assertResult(expected: Int) = FormationDatabase(context).use { it.readableDatabase.rawQuery(
            "select passed from full_speaking_result_drafts where account_id=? and chapter_id=? and sentence_id=?", arrayOf(account, chapter, sentence),
        ).use { cursor -> assertEquals(expected, cursor.count); if (expected == 1) { assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)) } } }
        fun assertCheckpoint() = FormationDatabase(context).use { it.readableDatabase.rawQuery(
            "select module_id,step_id from learning_checkpoints where account_id=? and chapter_id=?", arrayOf(account, chapter),
        ).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("LR-06", cursor.getString(0)); assertEquals(sentence, cursor.getString(1)); assertFalse(cursor.moveToNext()) } }

        /** All product tables/columns participate; only this exact LR06 progress and result may change. */
        fun sqlHash(protectedOnly: Boolean): String = FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null)
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            val rows = tables.flatMap { table ->
                check(table.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")))
                db.rawQuery("select * from \"$table\"", null).use { cursor -> buildList {
                    add("TABLE:$table:${cursor.columnNames.joinToString(",")}")
                    while (cursor.moveToNext()) {
                        fun value(name: String) = cursor.getColumnIndex(name).takeIf { it >= 0 }?.let(cursor::getString)
                        val own = value("account_id") == account && value("chapter_id") == chapter && value("sentence_id") == sentence
                        val mayChange = own && (table == "full_speaking_result_drafts" ||
                            (table == "learning_module_progress" && value("module_id") == "LR-06"))
                        if (!protectedOnly || !mayChange) add(table + (0 until cursor.columnCount).joinToString("") { column ->
                            val value = when (cursor.getType(column)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).joinToString("") { "%02x".format(it) }
                                else -> cursor.getString(column)
                            }
                            value?.let { "${it.length}:$it" } ?: "-1:"
                        })
                    }
                } }
            }.sorted()
            sha(rows.joinToString("\n"))
        }
    }

    private data class RequestEvidence(val bodySha: String, val commandSha: String, val audioSha: String) {
        fun assertSnapshot(snapshot: FullSpeakingRecoverySnapshot) {
            val body = requireNotNull(snapshot.requestBody)
            assertEquals(bodySha, sha(body))
            assertEquals(commandSha, sha(JSONObject(body).getString("commandId")))
            assertEquals(audioSha, sha(JSONObject(body).getString("audioBase64")))
        }
    }

    private class ProcessCapture(private val context: Context, private val phase: String, private val allowed: Boolean) : ReadSpeakAudioCapture {
        var starts = 0; var finishes = 0; var cancels = 0
        override fun start(): Boolean { starts++; check(allowed && starts == 1) { "Restoration cannot start a microphone" }; return true }
        override fun finish(): CapturedReadSpeakAudio {
            finishes++; check(allowed && starts == 1 && finishes == 1) { "Restoration cannot finish another recording" }
            val file = File(context.cacheDir, "$phase-not-audio.m4a")
            check(file.createNewFile()); file.writeText("NOT_AUDIO_FULL_PROCESS_$phase")
            return CapturedReadSpeakAudio(file, "audio/mp4")
        }
        override fun cancel() { cancels++ }
        fun assertCounts(expected: Int) { assertEquals(expected, starts); assertEquals(expected, finishes); assertEquals(0, cancels) }
    }

    private class ProcessContext(base: Context, private val runId: String, create: Boolean) : ContextWrapper(base) {
        private val root = base.cacheDir.canonicalFile
        private val directory = File(root, "full-speaking-unknown-$runId")
        private val database = File(directory, "formation.db")
        private val files = File(directory, "files")
        private val cache = File(directory, "cache")
        init {
            check(directory.canonicalFile == directory.absoluteFile && directory.parentFile == root)
            if (create) { check(directory.mkdir()); check(files.mkdir()); check(cache.mkdir()) }
            else check(directory.isDirectory && database.isFile && files.isDirectory && cache.isDirectory)
            listOf(database, files, cache).forEach { check(it.canonicalFile == it.absoluteFile) }
        }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getNoBackupFilesDir(): File = files
        override fun getDatabasePath(name: String): File {
            check(name == "real-language-learning.db" && database.canonicalFile == database.absoluteFile)
            return database
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        private fun evidenceFile(phase: String): File {
            check(phase in PHASES)
            return File(directory, "$phase-evidence.json").also { check(it.canonicalFile == it.absoluteFile) }
        }
        fun evidence(phase: String): JSONObject = JSONObject(evidenceFile(phase).readText()).also {
            check(it.getInt("version") == 1 && it.getString("runId") == runId && it.getString("phase") == phase)
        }
        fun writeEvidence(phase: String, data: JSONObject) {
            val file = evidenceFile(phase); check(file.createNewFile()) { "Do not overwrite a completed phase" }
            val text = data.put("version", 1).put("runId", runId).put("phase", phase).toString()
            FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }
        fun payloadSha(account: String): String {
            val owned = accountLearningAttemptsDirectory(files, account)
            check(owned.canonicalPath.startsWith(files.canonicalPath + File.separator))
            val payloads = owned.walkTopDown().filter { it.isFile && it.extension == "payload" }.toList()
            check(payloads.isNotEmpty())
            return sha(payloads.map { file ->
                check(file.canonicalPath.startsWith(owned.canonicalPath + File.separator))
                file.relativeTo(owned).path + ":" + sha(file.readBytes())
            }.sorted().joinToString("\n"))
        }
        fun deleteVerifiedFixture(namespace: String, accountHash: String) {
            check(namespace == "reallanguage.full-speaking.payload.test.${runId.replace("-", "")}")
            check(accountHash.matches(Regex("[a-f0-9]{64}")))
            val alias = "$namespace.$accountHash.v1"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(alias); check(!store.containsAlias(alias))
            check(directory.name == "full-speaking-unknown-$runId" && directory.canonicalFile.parentFile == root)
            check(deleteDatabase("real-language-learning.db"))
            check(directory.deleteRecursively())
        }
    }

    private companion object {
        val PHASES = listOf("train", "asr", "transcribed", "evaluate", "verify")
        const val TARGET = "I felt calm at the café."
        const val TRANSCRIPT = "I was quite calm at the café."
        const val CONFIRMED = "I felt calm at the café."
        const val TRAINING_FEEDBACK = "隔离训练反馈：本轮已读完。"
        const val EVALUATION_FEEDBACK = "隔离评价：句意完整；听过答案，因此不算独立通过。"
        val LOW_CONFIDENCE = listOf("quite calm", "café")
        fun progressValues(round: Int) = mapOf("version" to "1", "attemptIndex" to round.toString(),
            "answerAudioPlayed" to "false", "transcript" to "", "feedbackZh" to "", "trainingCanContinue" to "false", "finalDecision" to "")
        fun sha(text: String): String = sha(text.toByteArray(Charsets.UTF_8))
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
