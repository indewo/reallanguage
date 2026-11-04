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
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackRecovery
import com.reallanguage.app.feature.learning.domain.ReadSpeakFeedbackResult
import com.reallanguage.app.feature.learning.presentation.ReadSpeakAction
import com.reallanguage.app.feature.learning.presentation.ReadSpeakEffect
import com.reallanguage.app.feature.learning.presentation.ReadSpeakViewModel
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in three-process protocol. Only the runner's actual force-stops prove process death.
 * Fixed text/NOT_AUDIO and HTTP responses are isolated fixtures, not pronunciation or AI evidence. */
class ReadSpeakUnknownProcessRecoveryTest {
    @Test fun unknownOriginalRequestAndCompletedFeedbackSurviveThreeSeparateProcesses() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("readSpeakUnknownPhase")
        assumeTrue(phase in setOf("write", "read", "verify"))
        val runId = requireNotNull(args.getString("runId")).also {
            require(UUID.fromString(it).toString() == it) { "A canonical isolated UUID is required" }
        }
        withContext(Dispatchers.IO) {
            val context = ProcessContext(InstrumentationRegistry.getInstrumentation().targetContext, runId, phase == "write")
            val account = "read-unknown-account-$runId"
            val chapterId = "read-unknown-chapter-$runId"
            val sentenceId = "read-unknown-sentence-$runId"
            val target = "I read at the café."
            val feedback = "隔离反馈：本句已读完。"
            val signal = ReadSpeakCompletionSignal.TOUCH_REACHED_END
            val namespace = "reallanguage.learning-feedback.payload.test.${runId.replace("-", "")}"
            val runtime = SessionRuntime().apply { update("ISOLATED_PROCESS_TOKEN", account) }
            val keys = AndroidReadSpeakPayloadKeys(namespace, canUseAccount = { it == fixedSourceAccountHash(account) })
            if (phase == "write") seed(context, account, chapterId, sentenceId, target, runId)
            val journal = AndroidReadSpeakRecoveryJournal(context, runtime, chapterId, sentenceId, target, keys)
            val writeEvidence = if (phase == "write") null else context.evidence("write")
            if (writeEvidence != null) {
                assertNotEquals("Run the next phase only after ending the previous process", writeEvidence.getInt("pid"), Process.myPid())
                assertEquals(writeEvidence.getString("protectedSqlSha"), sqlHash(context, account, chapterId, sentenceId, protectedOnly = true))
            }
            val protectedBefore = sqlHash(context, account, chapterId, sentenceId, protectedOnly = true)
            var requests = 0
            var bodySha: String? = null
            var commandSha: String? = null
            val capture = ProcessCapture(context, phase == "write")
            val transport = HttpTransport { request ->
                requests++
                assertTrue("Only write and an explicit read-phase retry may issue HTTP", phase in setOf("write", "read"))
                assertEquals(1, requests)
                assertEquals("POST", request.method)
                assertEquals("/api/v1/learning/reading-feedback", request.path)
                bodySha = sha(request.body)
                commandSha = sha(JSONObject(request.body).getString("commandId"))
                if (phase == "write") HttpResponse(503, "") else {
                    assertEquals("Retry must preserve every request byte", writeEvidence!!.getString("bodySha"), bodySha)
                    assertEquals("Retry must preserve the original technical attempt", writeEvidence.getString("commandSha"), commandSha)
                    HttpResponse(200, JSONObject().put("feedbackZh", feedback).put("transcript", target)
                        .put("contentComplete", true).put("feedbackConfidence", 0.9).put("action", "CONTINUE").toString())
                }
            }
            val repository = AndroidReadSpeakRepository(transport, runtime, chapterId, target, UUID.randomUUID().toString(),
                capture, AndroidLearningStepStore(context), JsonReadSpeakWireCodec(),
                recoveryJournal = journal, recoverySentenceId = sentenceId)
            val progress = AndroidLearningProgressStore(context, runtime, chapterId, sentenceId, "LR-03")
            val viewModels = ViewModelStore()
            var verified = false
            try {
                if (phase == "write") {
                    assertEquals(ReadSpeakFeedbackRecovery.None, repository.restoreFeedback())
                    assertTrue(repository.prepareCapture())
                    assertTrue(repository.startCapture())
                    assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback(sentenceId, signal))
                    assertTrue(repository.hasPendingFeedbackRetry())
                    val saved = journal.load() as ReadSpeakRecoveryLoad.Loaded
                    assertEquals(ReadSpeakRecoveryPhase.Pending, saved.snapshot.phase)
                    assertEquals(bodySha, sha(requireNotNull(saved.snapshot.requestBody)))
                    assertEquals(commandSha, sha(saved.snapshot.commandId))
                    assertNull(saved.snapshot.delivered)
                    assertTraining(context, account, chapterId, sentenceId, expected = 0)
                } else if (phase == "read") {
                    val saved = journal.load() as ReadSpeakRecoveryLoad.Loaded
                    assertEquals(ReadSpeakRecoveryPhase.Pending, saved.snapshot.phase)
                    assertEquals(writeEvidence!!.getString("bodySha"), sha(requireNotNull(saved.snapshot.requestBody)))
                    assertEquals(writeEvidence.getString("commandSha"), sha(saved.snapshot.commandId))
                    assertEquals(ReadSpeakFeedbackRecovery.Pending(signal), repository.restoreFeedback())
                    assertTrue(repository.hasPendingFeedbackRetry())
                    assertTraining(context, account, chapterId, sentenceId, expected = 0)
                    assertEquals(0, requests)
                    capture.assertCounts(0, 0)
                } else {
                    val readEvidence = context.evidence("read")
                    assertNotEquals(readEvidence.getInt("pid"), Process.myPid())
                    assertEquals(readEvidence.getString("allSqlSha"), sqlHash(context, account, chapterId, sentenceId, protectedOnly = false))
                    assertEquals(readEvidence.getString("payloadSha"), context.payloadSha(account))
                    assertEquals(ReadSpeakFeedbackRecovery.Completed(signal, ReadSpeakFeedbackResult.Ready(feedback, true)),
                        repository.restoreFeedback())
                    assertFalse(repository.hasPendingFeedbackRetry())
                    assertTraining(context, account, chapterId, sentenceId, 1, writeEvidence!!.getString("commandSha"))
                }

                val chapter = FixedLearningChapter(chapterId, 1, listOf(FixedLearningSentence(sentenceId, target, "我在咖啡馆读书。", true)))
                val model = withContext(Dispatchers.Main) {
                    ReadSpeakViewModel(chapter, sentenceId, repository, progress).also { viewModels.put("isolated-read-unknown", it) }
                }
                withTimeout(5_000) { model.uiState.first { !it.submitting } }
                assertFalse(model.uiState.value.progressFailed)
                assertFalse(model.uiState.value.listening)
                assertEquals(0, capture.cancels)
                if (phase != "verify") {
                    assertFalse(model.uiState.value.completed)
                    assertTrue(model.uiState.value.captureFailed)
                    assertTrue(model.uiState.value.pendingFeedbackRetry)
                    assertEquals(if (phase == "write") 1 else 0, requests)
                }
                if (phase == "read") {
                    // Only this explicit VM action authorizes retryFeedback; restoring above issued zero HTTP.
                    withContext(Dispatchers.Main) { model.onAction(ReadSpeakAction.RetryRequested) }
                    withTimeout(5_000) { model.uiState.first { !it.submitting } }
                    assertTrue(model.uiState.value.completed)
                    assertEquals(feedback, model.uiState.value.feedbackZh)
                    assertFalse(model.uiState.value.pendingFeedbackRetry)
                }
                val completed = phase != "write"
                withTimeout(5_000) {
                    while (progress.read()?.get("completed") != completed.toString()) delay(10)
                }
                val independentlyRead = AndroidLearningProgressStore(context, runtime, chapterId, sentenceId, "LR-03").read()
                assertEquals(completed.toString(), independentlyRead?.get("completed"))
                if (completed) {
                    assertTrue(model.uiState.value.completed)
                    assertEquals(feedback, independentlyRead?.get("feedbackZh"))
                    assertEquals(feedback, model.uiState.value.feedbackZh)
                    assertFalse(model.uiState.value.progressFailed)
                    val saved = journal.load() as ReadSpeakRecoveryLoad.Loaded
                    assertEquals(ReadSpeakRecoveryPhase.Completed, saved.snapshot.phase)
                    assertNull(saved.snapshot.requestBody)
                    assertEquals(ReadSpeakFeedbackResult.Ready(feedback, true), saved.snapshot.delivered)
                    assertEquals(writeEvidence!!.getString("commandSha"), sha(saved.snapshot.commandId))
                    assertTraining(context, account, chapterId, sentenceId, 1, writeEvidence.getString("commandSha"))
                }
                assertEquals(protectedBefore, sqlHash(context, account, chapterId, sentenceId, protectedOnly = true))
                assertCheckpoint(context, account, chapterId, sentenceId)
                capture.assertCounts(if (phase == "write") 1 else 0, if (phase == "write") 1 else 0)
                assertEquals(if (phase == "verify") 0 else 1, requests)
                when (phase) {
                    "write" -> context.writeEvidence("write", JSONObject().put("pid", Process.myPid())
                        .put("bodySha", requireNotNull(bodySha)).put("commandSha", requireNotNull(commandSha))
                        .put("protectedSqlSha", protectedBefore))
                    "read" -> context.writeEvidence("read", JSONObject().put("pid", Process.myPid())
                        .put("allSqlSha", sqlHash(context, account, chapterId, sentenceId, protectedOnly = false))
                        .put("payloadSha", context.payloadSha(account)))
                    "verify" -> {
                        withContext(Dispatchers.Main) { model.onAction(ReadSpeakAction.ContinueRequested) }
                        assertEquals(ReadSpeakEffect.OpenHandwriting, withTimeout(5_000) { model.effects.first() })
                        // Completion may normalize the legacy interrupted progress marker; protected rows and the real training identity cannot change.
                        assertEquals(protectedBefore, sqlHash(context, account, chapterId, sentenceId, protectedOnly = true))
                        assertTraining(context, account, chapterId, sentenceId, 1, writeEvidence!!.getString("commandSha"))
                        assertEquals(context.evidence("read").getString("payloadSha"), context.payloadSha(account))
                        verified = true
                    }
                }
                println("READ_SPEAK_UNKNOWN phase=$phase pid=${Process.myPid()} http=$requests start=${capture.starts} finish=${capture.finishes}")
            } finally {
                // write/read intentionally do not clear the VM, cancel capture, or dispose the repository.
                // The external runner must actually force-stop; a failed phase retains only this UUID fixture.
                if (verified) {
                    withContext(Dispatchers.Main) { viewModels.clear() }
                    runtime.clear()
                    context.deleteVerifiedFixture(namespace, fixedSourceAccountHash(account))
                }
            }
        }
    }

    private class ProcessCapture(private val context: Context, private val mayCapture: Boolean) : ReadSpeakAudioCapture {
        var starts = 0
        var finishes = 0
        var cancels = 0
        override fun start(): Boolean {
            starts++
            check(mayCapture) { "Restored attempts must not capture again" }
            return true
        }
        override fun finish(): CapturedReadSpeakAudio {
            finishes++
            check(mayCapture && starts == 1 && finishes == 1) { "Only the original isolated capture may finish" }
            val audio = File(context.cacheDir, "original-not-audio.m4a")
            check(audio.createNewFile())
            audio.writeText("NOT_AUDIO_PROCESS_FIXTURE")
            return CapturedReadSpeakAudio(audio, "audio/mp4")
        }
        override fun cancel() { cancels++ }
        fun assertCounts(expectedStarts: Int, expectedFinishes: Int) {
            assertEquals(expectedStarts, starts); assertEquals(expectedFinishes, finishes); assertEquals(0, cancels)
        }
    }

    private fun seed(context: Context, account: String, chapter: String, sentence: String, target: String, runId: String) {
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            listOf(Triple(account, chapter, sentence), Triple("foreign-$runId", "foreign-chapter-$runId", "foreign-sentence-$runId")).forEach { (owner, ownerChapter, ownerSentence) ->
                db.execSQL("insert into formation_flows values (?, ?, 'FIXED', 'DIARY', '隔离进程恢复')", arrayOf(owner, "flow-$owner"))
                db.execSQL("insert into chapters values (?, ?, ?, 0, 'IN_PROGRESS')", arrayOf(owner, "flow-$owner", ownerChapter))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, ?, ?, 0, '我在咖啡馆读书。', ?, 'FOCUS')",
                    arrayOf(owner, ownerChapter, ownerSentence, target))
                db.execSQL("insert into learning_checkpoints values (?, ?, 'LR-03', ?)", arrayOf(owner, ownerChapter, ownerSentence))
            }
            db.execSQL("insert into learning_steps values (?, ?, ?, 'READ_SPEAK', 'COMPLETED', ?)",
                arrayOf("foreign-$runId", "foreign-chapter-$runId", "foreign-sentence-$runId", "foreign-command-$runId"))
        }
    }

    private fun assertTraining(context: Context, account: String, chapter: String, sentence: String, expected: Int, commandSha: String? = null) {
        FormationDatabase(context).use { helper -> helper.readableDatabase.rawQuery(
            "select status,command_id from learning_steps where account_id=? and chapter_id=? and sentence_id=? and step='READ_SPEAK'",
            arrayOf(account, chapter, sentence),
        ).use { cursor ->
            assertEquals(expected, cursor.count)
            if (expected == 1) { assertTrue(cursor.moveToFirst()); assertEquals("COMPLETED", cursor.getString(0)); assertEquals(commandSha, sha(cursor.getString(1))) }
        } }
    }

    private fun assertCheckpoint(context: Context, account: String, chapter: String, sentence: String) {
        FormationDatabase(context).use { helper -> helper.readableDatabase.rawQuery(
            "select module_id,step_id from learning_checkpoints where account_id=? and chapter_id=?", arrayOf(account, chapter),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals("LR-03", cursor.getString(0)); assertEquals(sentence, cursor.getString(1)); assertFalse(cursor.moveToNext())
        } }
    }

    /** Every product table is covered. Only this sentence's three explicitly asserted LR-03 rows may change. */
    private fun sqlHash(context: Context, account: String, chapter: String, sentence: String, protectedOnly: Boolean): String =
        FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null)
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            val rows = tables.flatMap { table ->
                check(table.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")))
                db.rawQuery("select * from \"$table\"", null).use { cursor -> buildList {
                    add("TABLE:$table:${cursor.columnNames.joinToString(",")}")
                    while (cursor.moveToNext()) {
                        fun value(name: String): String? = cursor.getColumnIndex(name).takeIf { it >= 0 }?.let(cursor::getString)
                        val own = value("account_id") == account && value("chapter_id") == chapter
                        val mayChange = own && when (table) {
                            "learning_checkpoints" -> true
                            "learning_module_progress" -> value("sentence_id") == sentence && value("module_id") == "LR-03"
                            "learning_steps" -> value("sentence_id") == sentence && value("step") == "READ_SPEAK"
                            else -> false
                        }
                        if (!protectedOnly || !mayChange) add(table + (0 until cursor.columnCount).joinToString("") { column ->
                            val text = when (cursor.getType(column)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).joinToString("") { "%02x".format(it) }
                                else -> cursor.getString(column)
                            }
                            text?.let { "${it.length}:$it" } ?: "-1:"
                        })
                    }
                } }
            }.sorted()
            sha(rows.joinToString("\n"))
        }

    private class ProcessContext(base: Context, private val runId: String, create: Boolean) : ContextWrapper(base) {
        private val root = base.cacheDir.canonicalFile
        private val directory = File(root, "read-speak-unknown-$runId")
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
            check(phase in setOf("write", "read"))
            return File(directory, "$phase-evidence.json").also { check(it.canonicalFile == it.absoluteFile) }
        }
        fun evidence(phase: String): JSONObject = JSONObject(evidenceFile(phase).readText()).also {
            check(it.getInt("version") == 1 && it.getString("runId") == runId && it.getString("phase") == phase)
        }
        fun writeEvidence(phase: String, data: JSONObject) {
            val file = evidenceFile(phase)
            check(file.createNewFile()) { "An existing process phase must not be overwritten" }
            val text = data.put("version", 1).put("runId", runId).put("phase", phase).toString()
            FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }
        fun payloadSha(account: String): String {
            val ownerDirectory = accountLearningAttemptsDirectory(files, account)
            check(ownerDirectory.canonicalPath.startsWith(files.canonicalPath + File.separator))
            val payload = ownerDirectory.walkTopDown().filter { it.isFile && it.extension == "payload" }.single()
            check(payload.canonicalPath.startsWith(ownerDirectory.canonicalPath + File.separator))
            return sha(payload.readBytes())
        }
        fun deleteVerifiedFixture(namespace: String, accountHash: String) {
            check(namespace == "reallanguage.learning-feedback.payload.test.${runId.replace("-", "")}")
            check(accountHash.matches(Regex("[a-f0-9]{64}")))
            val alias = "$namespace.$accountHash.v1"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(alias)
            check(!store.containsAlias(alias))
            check(directory.name == "read-speak-unknown-$runId" && directory.canonicalFile.parentFile == root)
            check(deleteDatabase("real-language-learning.db"))
            check(directory.deleteRecursively())
        }
    }

    private companion object {
        fun sha(text: String): String = sha(text.toByteArray(Charsets.UTF_8))
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
