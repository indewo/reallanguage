package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real UUID SQLite and test-only Formation keystore. NOT_AUDIO is not speech, HTTP, or AI evidence. */
class AndroidSceneResponseRecoveryJournalTest {
    @Test fun savedRouteFocusMayDifferFromCurrentTaskAndExactBodyRestoresWithNonExportableKey() = isolated {
        assertNotEquals(routeSentence, task.sentenceId)
        val protectedRows = rows()
        assertEquals(SceneResponseRecoveryLoad.Missing, journal().load())
        assertNull(keys().existing(hash(account), 1))
        val snapshot = pending()
        assertEquals(0L, journal().save(null, snapshot))
        assertEquals(SceneResponseRecoveryLoad.Loaded(0, snapshot), journal().load())
        assertNull(keys().existing(hash(account), 1)!!.encoded)
        val payload = payloads().single()
        val scope = File(accountLearningAttemptsDirectory(context.filesDir, account), "scene-response-recovery").canonicalFile
        assertEquals(scope, payload.parentFile!!.parentFile!!.canonicalFile)
        val envelope = payload.readBytes().toString(Charsets.UTF_8)
        listOf(snapshot.recordingId, snapshot.taskId, snapshot.transcriptionBody!!,
            Base64.getEncoder().encodeToString("NOT_AUDIO".toByteArray())).forEach { assertFalse(envelope.contains(it)) }
        assertEquals(protectedRows, rows())
    }

    @Test fun refreshKeepsAccessButReloginAccountSwitchAndSignOutRevokeCapturedFactory() = isolated {
        val original = journal()
        val snapshot = pending()
        assertEquals(0L, original.save(null, snapshot))
        runtime.update("ISOLATED_REFRESH", account)
        assertEquals(SceneResponseRecoveryLoad.Loaded(0, snapshot), original.load())
        assertEquals(1L, original.save(0, snapshot))
        val bytes = payloads().single().readBytes()
        runtime.update("ISOLATED_RELOGIN", account, newSession = true)
        assertEquals(SceneResponseRecoveryLoad.Unavailable, original.load())
        assertNull(original.save(1, cleared(snapshot)))
        val current = journal()
        assertEquals(SceneResponseRecoveryLoad.Loaded(1, snapshot), current.load())
        runtime.update("ISOLATED_OTHER", "other-$account")
        assertEquals(SceneResponseRecoveryLoad.Unavailable, current.load())
        assertNull(current.save(1, cleared(snapshot)))
        runtime.clear()
        assertEquals(SceneResponseRecoveryLoad.Unavailable, journal().load())
        assertNull(journal().save(null, snapshot))
        assertArrayEquals(bytes, payloads().single().readBytes())
    }

    @Test fun changedFixedFactsCheckpointOrSavedProgressCannotReplaceCiphertext() {
        listOf("module", "step", "missing-checkpoint", "completed-chapter", "focus", "english", "chinese",
            "task-background", "task-lines", "task-target", "missing-task", "task-index", "round", "completed-progress",
            "missing-progress", "bad-progress", "typed-progress", "saved-task", "supplied-task", "chapter-number").forEach { change -> isolated {
            val original = journal()
            val snapshot = pending()
            assertEquals(0L, original.save(null, snapshot))
            val bytes = payloads().single().readBytes()
            when (change) {
                "module" -> sql("update learning_checkpoints set module_id='LR-08' where account_id=?", arrayOf(account))
                "step" -> sql("update learning_checkpoints set step_id=? where account_id=?", arrayOf(task.sentenceId, account))
                "missing-checkpoint" -> sql("delete from learning_checkpoints where account_id=?", arrayOf(account))
                "completed-chapter" -> sql("update chapters set status='COMPLETED' where account_id=?", arrayOf(account))
                "focus" -> sql("update fixed_sentences set role='CONTEXT' where account_id=? and unit_id=?", arrayOf(account, task.sentenceId))
                "english" -> sql("update fixed_sentences set text_en='Changed fixed text.' where account_id=? and unit_id=?", arrayOf(account, task.sentenceId))
                "chinese" -> sql("update fixed_sentences set text_zh='已经改变' where account_id=? and unit_id=?", arrayOf(account, routeSentence))
                "task-background" -> sql("update scene_response_tasks set background_en='Changed background.' where account_id=? and task_id=?", arrayOf(account, task.taskId))
                "task-lines" -> sql("update scene_response_tasks set context_lines='AI\u001fChanged context?' where account_id=? and task_id=?", arrayOf(account, task.taskId))
                "task-target" -> sql("update scene_response_tasks set target_sentence_en='Changed target.' where account_id=? and task_id=?", arrayOf(account, task.taskId))
                "missing-task" -> sql("delete from scene_response_tasks where account_id=? and task_id=?", arrayOf(account, task.taskId))
                "task-index" -> progress(taskIndex = 0)
                "round" -> progress(round = 1)
                "completed-progress" -> progress(completed = true)
                "missing-progress" -> sql("delete from learning_module_progress where account_id=?", arrayOf(account))
                "bad-progress" -> sql("update learning_module_progress set payload='bad-json' where account_id=?", arrayOf(account))
                "typed-progress" -> replaceProgress(JSONObject(encoded()).put("roundIndex", 2).toString())
                "saved-task" -> replaceProgress(JSONObject(encoded()).put("task.1.line.0.text", "Different prompt?").toString())
                "supplied-task", "chapter-number" -> Unit
            }
            val changed = when (change) {
                "supplied-task" -> journal(target = task.copy(backgroundEn = "Changed supplied background."))
                "chapter-number" -> journal(chapterValue = chapter.copy(chapterNumber = 2))
                else -> journal()
            }
            if (change !in setOf("supplied-task", "chapter-number")) {
                assertEquals(change, SceneResponseRecoveryLoad.Unavailable, original.load())
                assertNull(original.save(0, cleared(snapshot)))
            }
            assertEquals(change, SceneResponseRecoveryLoad.Unavailable, changed.load())
            assertNull(changed.save(null, snapshot))
            assertArrayEquals(change, bytes, payloads().single().readBytes())
        } }
    }

    @Test fun corruptCiphertextAndMissingKeyNeverBecomeMissingOrCreateReplacementKeys() {
        listOf(false, true).forEach { loseKey -> isolated {
            assertEquals(0L, journal().save(null, pending()))
            val file = payloads().single()
            if (loseKey) keys().deleteAccount(hash(account))
            else file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            val bytes = file.readBytes()
            assertEquals(SceneResponseRecoveryLoad.Unavailable, journal().load())
            assertNull(journal().save(null, pending()))
            assertNull(journal().save(0, cleared(pending())))
            assertArrayEquals(bytes, file.readBytes())
            if (loseKey) assertNull(keys().existing(hash(account), 1))
        } }
    }

    @Test fun anotherRoundTaskAndAccountHaveIndependentPayloadsWithoutChangingTheOriginal() = isolated {
        val original = journal()
        val snapshot = pending()
        assertEquals(0L, original.save(null, snapshot))
        val firstFile = payloads().single(); val bytes = firstFile.readBytes()
        assertEquals(SceneResponseRecoveryLoad.Unavailable, journal(round = 1).load())
        progress(round = 1)
        assertEquals(SceneResponseRecoveryLoad.Unavailable, original.load())
        val previousRound = journal(round = 1)
        assertEquals(SceneResponseRecoveryLoad.Missing, previousRound.load())
        assertEquals(0L, previousRound.save(null, pending(round = 1)))
        progress(taskIndex = 0)
        val firstTask = journal(target = tasks.first())
        assertEquals(SceneResponseRecoveryLoad.Missing, firstTask.load())
        assertEquals(0L, firstTask.save(null, pending(target = tasks.first())))
        progress()
        assertEquals(SceneResponseRecoveryLoad.Loaded(0, snapshot), original.load())
        val otherRuntime = SessionRuntime().apply { update("ISOLATED_OTHER", "other-$account") }
        seed(otherRuntime)
        val other = journal(session = otherRuntime)
        assertEquals(SceneResponseRecoveryLoad.Missing, other.load())
        val otherSnapshot = pending().copy(recordingId = "other-$runId")
        assertEquals(0L, other.save(null, otherSnapshot))
        assertEquals(SceneResponseRecoveryLoad.Loaded(0, otherSnapshot), other.load())
        assertEquals(SceneResponseRecoveryLoad.Loaded(0, snapshot), original.load())
        assertArrayEquals(bytes, firstFile.readBytes())
        assertEquals(4, payloads().size)
    }

    @Test fun snapshotTaskAndRoundMustMatchTheFactoryBeforeAnySave() = isolated {
        val snapshot = pending()
        assertEquals(0L, journal().save(null, snapshot))
        val bytes = payloads().single().readBytes()
        for (bad in listOf(snapshot.copy(taskId = tasks.first().taskId), snapshot.copy(roundIndex = 0),
            cleared(snapshot).copy(taskId = tasks.first().taskId), cleared(snapshot).copy(roundIndex = 1))) {
            assertNull(journal().save(0, bad))
            assertArrayEquals(bytes, payloads().single().readBytes())
        }
        assertEquals(SceneResponseRecoveryLoad.Loaded(0, snapshot), journal().load())
    }

    @Test fun reloginDuringFinalSqlCheckCannotReturnOldEpochPlaintext() = isolated {
        val original = journal(); val snapshot = pending()
        assertEquals(0L, original.save(null, snapshot))
        var reads = 0
        databaseOpened = { reads++ }
        assertEquals(SceneResponseRecoveryLoad.Loaded(0, snapshot), original.load())
        assertTrue(reads > 0)
        val last = reads; reads = 0
        val bytes = payloads().single().readBytes()
        databaseOpened = { if (++reads == last) runtime.update("ISOLATED_RELOGIN", account, newSession = true) }
        assertEquals(SceneResponseRecoveryLoad.Unavailable, original.load())
        assertEquals(last, reads)
        databaseOpened = {}
        assertArrayEquals(bytes, payloads().single().readBytes())
        assertEquals(SceneResponseRecoveryLoad.Loaded(0, snapshot), journal().load())
    }

    private fun isolated(block: suspend Fixture.() -> Unit) = runBlocking { withContext(Dispatchers.IO) {
        val fixture = Fixture()
        try { fixture.seed(fixture.runtime); fixture.block() } finally { fixture.close() }
    } }

    private class Fixture {
        val runId = UUID.randomUUID().toString()
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val directory = File(base.cacheDir, "scene-response-journal-$runId").also { check(it.mkdir()) }
        private val files = File(directory, "files").also { check(it.mkdir()) }
        private val cache = File(directory, "cache").also { check(it.mkdir()) }
        private val scoped = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
            override fun getNoBackupFilesDir(): File = files
        }
        private val isolatedDatabase = IsolatedLearningDatabaseContext(scoped, "scene-response-journal-$runId")
        var databaseOpened: () -> Unit = {}
        val context = object : ContextWrapper(isolatedDatabase) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = isolatedDatabase.getDatabasePath(name).also { databaseOpened() }
        }
        val account = "scene-journal-$runId"
        val routeSentence = "first-$runId"
        val chapter = FixedLearningChapter("chapter-$runId", 1, listOf(
            FixedLearningSentence(routeSentence, "I can join the meeting.", "我可以参加会议。", true),
            FixedLearningSentence("context-$runId", "The meeting is tomorrow.", "会议在明天。", false),
            FixedLearningSentence("second-$runId", "I will bring my notes.", "我会带上笔记。", true)))
        val tasks = chapter.sentences.filter { it.isFocus }.mapIndexed { index, sentence ->
            FixedSceneResponseTask("task-$index-$runId", sentence.sentenceId, "A meeting invitation.",
                listOf(SceneDialogueLine("AI", if (index == 0) "Can you join us?" else "What will you bring?")), sentence.textEn)
        }
        val task = tasks.last()
        val runtime = SessionRuntime().apply { update("ISOLATED_TOKEN", account) }
        private val ownedAccounts = mutableSetOf<String>()
        private val namespace = "reallanguage.formation.payload.test.${runId.replace("-", "") }"
        fun hash(owner: String) = fixedSourceAccountHash(owner)
        fun keys() = AndroidFormationPayloadKeys(namespace, canUseAccount = { candidate -> ownedAccounts.any { hash(it) == candidate } })
        fun journal(session: SessionRuntime = runtime, target: FixedSceneResponseTask = task, round: Int = 2,
            chapterValue: FixedLearningChapter = chapter) = AndroidSceneResponseRecoveryJournal(context, session, chapterValue, target, round, keys())
        fun pending(target: FixedSceneResponseTask = task, round: Int = 2) = SceneResponseRecoverySnapshot(
            "recording-$runId", target.taskId, round, SceneResponseRecoveryPhase.TranscriptionPending,
            transcriptionBody = JSONObject().put("chapterId", chapter.chapterId).put("taskId", target.taskId)
                .put("audioMimeType", "audio/mp4").put("audioBase64", Base64.getEncoder().encodeToString("NOT_AUDIO".toByteArray()))
                .put("commandId", "command-${target.taskId}-$round").toString())
        fun cleared(snapshot: SceneResponseRecoverySnapshot) = snapshot.copy(phase = SceneResponseRecoveryPhase.Cleared, transcriptionBody = null)
        suspend fun seed(session: SessionRuntime) {
            val owner = requireNotNull(session.accountId); ownedAccounts += owner
            val flow = "flow-${UUID.randomUUID()}"
            sql("insert into formation_flows values (?,?,'FIXED','DIARY','隔离情景恢复测试')", arrayOf(owner, flow))
            sql("insert into chapters values (?,?,?,0,'IN_PROGRESS')", arrayOf(owner, flow, chapter.chapterId))
            chapter.sentences.forEachIndexed { index, sentence -> sql(
                "insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,?,?,?,?,?,?)",
                arrayOf(owner, chapter.chapterId, sentence.sentenceId, index.toString(), sentence.textZh, sentence.textEn,
                    if (sentence.isFocus) "FOCUS" else "CONTEXT")) }
            tasks.forEachIndexed { index, target -> sql(
                "insert into scene_response_tasks(account_id,chapter_id,task_id,sentence_id,sentence_order,background_en,context_lines,target_sentence_en) values(?,?,?,?,?,?,?,?)",
                arrayOf(owner, chapter.chapterId, target.taskId, target.sentenceId, index.toString(), target.backgroundEn.orEmpty(),
                    target.contextLines.joinToString("\u001e") { "${it.speaker}\u001f${it.textEn}" }, target.targetSentenceEn)) }
            progress(session = session)
            assertEquals(tasks, AndroidSavedSceneTaskReader(context, session).read(chapter))
        }
        fun encoded(taskIndex: Int = 1, round: Int = 2, completed: Boolean = false): Map<String, String> {
            val candidate = if (completed) SceneEvaluationCandidate(SceneEvaluationDecision.FAIL, false, false, false, "隔离未通过候选") else null
            return encodeSceneProgress(SceneResponseUiState(chapter, tasks, taskIndex = taskIndex, roundIndex = round,
                completed = completed, finalDecision = candidate?.decision), candidate)
        }
        suspend fun progress(session: SessionRuntime = runtime, taskIndex: Int = 1, round: Int = 2, completed: Boolean = false) {
            assertTrue(AndroidLearningProgressStore(context, session, chapter.chapterId, routeSentence, "LR-09")
                .write(encoded(taskIndex, round, completed)))
        }
        fun replaceProgress(value: String) = sql("update learning_module_progress set payload=? where account_id=?", arrayOf(value, account))
        fun sql(query: String, args: Array<String>) = FormationDatabase(context).use { it.writableDatabase.execSQL(query, args) }
        fun payloads() = files.walkTopDown().filter { it.isFile && it.extension == "payload" }.toList()
        fun rows(): Map<String, List<String>> = FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%'", null).use {
                buildList { while (it.moveToNext()) add(it.getString(0)) }
            }
            tables.associateWith { table -> db.rawQuery("select * from \"${table.replace("\"", "\"\"")}\"", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("") {
                    if (cursor.isNull(it)) "-1:" else cursor.getString(it).let { value -> "${value.length}:$value" }
                }) }.sorted()
            } }
        }
        fun close() {
            databaseOpened = {}; runtime.clear()
            try { ownedAccounts.forEach { keys().deleteAccount(hash(it)) } } finally {
                val database = context.getDatabasePath("real-language-learning.db").canonicalFile
                check(database.parentFile == cache.canonicalFile && database.name.startsWith("scene-response-journal-$runId-"))
                context.deleteDatabase("real-language-learning.db")
                check(directory.name == "scene-response-journal-$runId" && directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
                check(directory.deleteRecursively())
            }
        }
    }
}
