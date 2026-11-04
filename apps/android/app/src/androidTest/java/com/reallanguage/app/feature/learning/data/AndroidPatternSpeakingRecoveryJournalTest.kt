package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.home.data.AndroidAccountDataCleaner
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** UUID SQLite and test-only non-exportable Android keys. NOT_AUDIO is not speech or AI evidence. */
class AndroidPatternSpeakingRecoveryJournalTest {
    @Test fun exactPendingBodyRestoresThroughFreshFactoryForSavedV1AndV2WithoutExportingAKey() {
        listOf("1", "2").forEach { version -> isolated {
            progress(version = version)
            assertEquals(PatternSpeakingRecoveryLoad.Missing, journal().load())
            assertNull(keys().existing(hash(account), 1))
            assertEquals(0L, journal().save(null, pending))
            assertEquals(PatternSpeakingRecoveryLoad.Loaded(0, pending), journal().load())
            assertNull(keys().existing(hash(account), 1)!!.encoded)
            val bytes = payloads().single().readBytes()
            assertFalse(bytes.toString(Charsets.UTF_8).contains(pending.requestBody!!))
            assertFalse(bytes.toString(Charsets.UTF_8).contains(body.getString("audioBase64")))
            assertFalse(bytes.toString(Charsets.UTF_8).contains(pending.attemptId))
            assertEquals(body.getString("commandId"), JSONObject((journal().load() as PatternSpeakingRecoveryLoad.Loaded)
                .snapshot.requestBody!!).getString("commandId"))
        } }
    }

    @Test fun ordinaryRefreshRetainsAccessButSameAccountNewSessionRevokesTheOldFactory() = isolated {
        val old = journal()
        assertEquals(0L, old.save(null, pending))
        runtime.update("ISOLATED_REFRESH", account)
        assertEquals(PatternSpeakingRecoveryLoad.Loaded(0, pending), old.load())
        assertEquals(1L, old.save(0, pending))
        val bytes = payloads().single().readBytes()
        runtime.update("ISOLATED_NEW_SESSION", account, newSession = true)
        assertEquals(PatternSpeakingRecoveryLoad.Unavailable, old.load())
        assertNull(old.save(1, pending.copy(phase = PatternRecoveryPhase.Cleared, requestBody = null)))
        assertArrayEquals(bytes, payloads().single().readBytes())
        assertEquals(PatternSpeakingRecoveryLoad.Loaded(1, pending), journal().load())
    }

    @Test fun changedFixedScopeOrSavedPracticeFailsClosedWithoutReplacingTheOriginalCiphertext() {
        listOf("module", "step", "missing-checkpoint", "completed-chapter", "context", "fixed-text",
            "target", "practice", "completed-progress", "missing-progress", "bad-progress", "supplied-practice").forEach { change -> isolated {
            val old = journal()
            assertEquals(0L, old.save(null, pending))
            val bytes = payloads().single().readBytes()
            when (change) {
                "module" -> sql("update learning_checkpoints set module_id='LR-08' where account_id=?", arrayOf(account))
                "step" -> sql("update learning_checkpoints set step_id='another-sentence' where account_id=?", arrayOf(account))
                "missing-checkpoint" -> sql("delete from learning_checkpoints where account_id=?", arrayOf(account))
                "completed-chapter" -> sql("update chapters set status='COMPLETED' where account_id=?", arrayOf(account))
                "context" -> sql("update fixed_sentences set role='CONTEXT' where account_id=?", arrayOf(account))
                "fixed-text" -> sql("update fixed_sentences set text_en='Changed fixed content.' where account_id=?", arrayOf(account))
                "target" -> sql("update target_selections set explanation_zh='已经改变的说明' where account_id=?", arrayOf(account))
                "practice" -> progress(set = practice.copy(examples = practice.examples.map { it.copy(aiPromptEn = "A different prompt?") }))
                "completed-progress" -> progress(completed = true)
                "missing-progress" -> sql("delete from learning_module_progress where account_id=?", arrayOf(account))
                "bad-progress" -> sql("update learning_module_progress set payload='bad-json' where account_id=?", arrayOf(account))
                "supplied-practice" -> Unit
            }
            val current = if (change == "supplied-practice") journal(set = practice.copy(examples = practice.examples.map {
                it.copy(targetResponseEn = "I used to swim.")
            })) else journal()
            if (change != "supplied-practice") {
                assertEquals(change, PatternSpeakingRecoveryLoad.Unavailable, old.load())
                assertNull(old.save(0, pending.copy(phase = PatternRecoveryPhase.Cleared, requestBody = null)))
            }
            assertEquals(change, PatternSpeakingRecoveryLoad.Unavailable, current.load())
            assertNull(current.save(null, pending))
            assertArrayEquals(change, bytes, payloads().single().readBytes())
        } }
    }

    @Test fun damagedCiphertextOrMissingKeyCannotBecomeMissingOrBeOverwritten() {
        listOf(false, true).forEach { loseKey -> isolated {
            assertEquals(0L, journal().save(null, pending))
            if (loseKey) keys().deleteAccount(hash(account)) else payloads().single().let { file ->
                file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            }
            val bytes = payloads().single().readBytes()
            assertEquals(PatternSpeakingRecoveryLoad.Unavailable, journal().load())
            assertNull(journal().save(null, pending))
            assertArrayEquals(bytes, payloads().single().readBytes())
            if (loseKey) assertNull(keys().existing(hash(account), 1))
        } }
    }

    @Test fun anotherSentenceAndAnotherAccountCannotReadOrReplaceTheOriginalAttempt() = isolated {
        val original = journal()
        assertEquals(0L, original.save(null, pending))
        val originalFile = payloads().single()
        val originalBytes = originalFile.readBytes()
        val secondSentence = "second-$sentence"
        val secondPattern = pattern.copy(patternId = "second-${pattern.patternId}", sourceSentenceId = secondSentence)
        val secondSet = practice.copy(patternId = secondPattern.patternId)
        sentence(account, chapter, secondSentence, secondPattern, 1)
        checkpoint(account, chapter, secondSentence)
        progress(sentenceId = secondSentence, targetPattern = secondPattern, set = secondSet)
        val second = journal(sentenceId = secondSentence, targetPattern = secondPattern, set = secondSet)
        assertEquals(PatternSpeakingRecoveryLoad.Missing, second.load())
        val secondPending = pending.copy(attemptId = "second-${pending.attemptId}", requestBody = JSONObject(body.toString())
            .put("patternId", secondPattern.patternId).put("commandId", "second-command-$runId").toString())
        assertEquals(0L, second.save(null, secondPending))
        assertEquals(PatternSpeakingRecoveryLoad.Unavailable, original.load())
        checkpoint(account, chapter, sentence)
        assertEquals(PatternSpeakingRecoveryLoad.Loaded(0, pending), original.load())
        val otherRuntime = SessionRuntime().apply { update("ISOLATED_OTHER", "other-$account") }
        seed(otherRuntime)
        val other = journal(session = otherRuntime)
        val otherPending = pending.copy(attemptId = "other-${pending.attemptId}", requestBody = JSONObject(body.toString())
            .put("commandId", "other-command-$runId").toString())
        assertEquals(PatternSpeakingRecoveryLoad.Missing, other.load())
        assertEquals(0L, other.save(null, otherPending))
        assertEquals(PatternSpeakingRecoveryLoad.Loaded(0, otherPending), other.load())
        assertEquals(PatternSpeakingRecoveryLoad.Loaded(0, pending), original.load())
        assertArrayEquals(originalBytes, originalFile.readBytes())
        assertEquals(3, payloads().size)
    }

    @Test fun accountCleanupDeletesOnlyItsPatternKeyAndLeavesTheOtherAccountRecoverable() = isolated {
        assertEquals(0L, journal().save(null, pending))
        val otherRuntime = SessionRuntime().apply { update("ISOLATED_OTHER", "other-$account") }
        seed(otherRuntime)
        val other = journal(session = otherRuntime)
        assertEquals(0L, other.save(null, pending))
        val otherHash = hash(otherRuntime.accountId!!)
        assertNotNull(keys().existing(otherHash, 1))
        // Other key domains are inert: this test must exercise the dedicated pattern key hook.
        val untouched = object : FormationPayloadKeys {
            override fun existing(accountSubjectHash: String, keyVersion: Int): SecretKey? = null
            override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int): SecretKey = error("No unrelated key creation")
            override fun deleteAccount(accountSubjectHash: String) = Unit
        }
        assertTrue(AndroidAccountDataCleaner(context, fixedSourceKeys = untouched, learningFeedbackKeys = untouched,
            fullSpeakingKeys = untouched, patternSpeakingKeys = keys()).clear(account))
        assertNull(keys().existing(hash(account), 1))
        assertNotNull(keys().existing(otherHash, 1))
        assertEquals(PatternSpeakingRecoveryLoad.Loaded(0, pending), other.load())
        assertFalse(accountLearningAttemptsDirectory(context.filesDir, account).exists())
        assertEquals(1, payloads().size)
    }

    private fun isolated(block: suspend Fixture.() -> Unit) = runBlocking { withContext(Dispatchers.IO) {
        val fixture = Fixture()
        try { fixture.seed(fixture.runtime); fixture.block() } finally { fixture.close() }
    } }

    private class Fixture {
        val runId = UUID.randomUUID().toString()
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val directory = File(base.cacheDir, "pattern-speaking-journal-$runId").also { check(it.mkdir()) }
        private val files = File(directory, "files").also { check(it.mkdir()) }
        private val cache = File(directory, "cache").also { check(it.mkdir()) }
        private val scoped = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
            override fun getNoBackupFilesDir(): File = files
        }
        val context = IsolatedLearningDatabaseContext(scoped, "pattern-speaking-journal-$runId")
        val account = "pattern-journal-$runId"; val chapter = "chapter-$runId"; val sentence = "sentence-$runId"
        val target = "I used to write."
        val pattern = LockedSpeakingPattern("pattern-$runId", sentence, "used to + verb", "过去的习惯")
        val practice = PatternPracticeSet(pattern.patternId, listOf(
            PatternPracticeExample("example-$runId", "谈过去的阅读习惯", "What did you do after school?", "I used to read.", "我过去常阅读。")))
        val runtime = SessionRuntime().apply { update("ISOLATED_TOKEN", account) }
        private val ownedAccounts = mutableSetOf<String>()
        private val namespace = "reallanguage.pattern-speaking.payload.test.${runId.replace("-", "") }"
        val body = JSONObject().put("chapterId", chapter).put("patternId", pattern.patternId)
            .put("exampleId", practice.examples.single().exampleId).put("mode", "READING")
            .put("targetTextEn", practice.examples.single().targetResponseEn).put("targetStructure", pattern.structure)
            .put("targetVisible", true).put("audioMimeType", "audio/mp4")
            .put("audioBase64", Base64.getEncoder().encodeToString("NOT_AUDIO".toByteArray()))
            .put("commandId", "command-$runId")
        val pending = PatternSpeakingRecoverySnapshot("attempt-$runId", practice.examples.single().exampleId,
            PatternFeedbackMode.Reading, PatternRecoveryPhase.Pending, body.toString())
        fun hash(owner: String) = fixedSourceAccountHash(owner)
        fun keys() = AndroidPatternSpeakingPayloadKeys(namespace, canUseAccount = { candidate -> ownedAccounts.any { hash(it) == candidate } })
        fun journal(session: SessionRuntime = runtime, sentenceId: String = sentence, targetPattern: LockedSpeakingPattern = pattern,
            set: PatternPracticeSet = practice) = AndroidPatternSpeakingRecoveryJournal(context, session, chapter, sentenceId, target, targetPattern, set, keys())
        suspend fun seed(session: SessionRuntime) {
            val owner = requireNotNull(session.accountId); ownedAccounts += owner
            val flow = "flow-${UUID.randomUUID()}"
            sql("insert into formation_flows values (?,?,'FIXED','DIARY','隔离句式恢复测试')", arrayOf(owner, flow))
            sql("insert into chapters values (?,?,?,0,'IN_PROGRESS')", arrayOf(owner, flow, chapter))
            sentence(owner, chapter, sentence, pattern, 0)
            checkpoint(owner, chapter, sentence)
            progress(session = session)
            assertEquals(listOf(pattern), AndroidLockedPatternReader(context, session).read(chapter, sentence))
        }
        fun sentence(owner: String, chapterId: String, sentenceId: String, targetPattern: LockedSpeakingPattern, order: Int) {
            sql("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?,?,?,?,?,?,'FOCUS')",
                arrayOf(owner, chapterId, sentenceId, order.toString(), "我过去常写字。", target))
            val surface = "used to write"
            val evidence = JSONObject().put("version", 1).put("startUtf16", 2).put("endUtf16", 2 + surface.length)
                .put("surfaceForm", surface).put("normalizedForm", targetPattern.structure).put("allowedForms", JSONArray().put(surface))
            sql("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary,source_evidence) " +
                "values(?,?,?,?,'PATTERN',?,?,'','',?)", arrayOf(owner, chapterId, targetPattern.patternId, sentenceId,
                targetPattern.structure, targetPattern.explanationZh, evidence.toString()))
        }
        fun checkpoint(owner: String, chapterId: String, sentenceId: String) = sql(
            "insert or replace into learning_checkpoints values (?,?,'LR-07',?)", arrayOf(owner, chapterId, sentenceId))
        suspend fun progress(session: SessionRuntime = runtime, sentenceId: String = sentence, targetPattern: LockedSpeakingPattern = pattern,
            set: PatternPracticeSet = practice, version: String = "2", completed: Boolean = false) {
            val state = PatternSpeakingUiState(FixedLearningChapter(chapter, 1,
                listOf(FixedLearningSentence(sentenceId, target, "我过去常写字。", true))), sentenceId, listOf(targetPattern),
                stage = if (completed) PatternSpeakingStage.RESPONDING else PatternSpeakingStage.READING,
                examples = set.examples, completed = completed, currentResponseCompleted = completed,
                completedReadingExampleIds = if (completed) set.examples.map { it.exampleId }.toSet() else emptySet(),
                processedPatternIds = if (completed) setOf(targetPattern.patternId) else emptySet())
            val saved = encodePatternProgress(state).toMutableMap()
            if (version == "1") { saved["version"] = "1"; saved.remove("processedPatternCount"); saved.keys.removeAll { it.startsWith("processedPattern.") } }
            assertTrue(AndroidLearningProgressStore(context, session, chapter, sentenceId, "LR-07").write(saved))
        }
        fun sql(query: String, args: Array<String>) = FormationDatabase(context).use { it.writableDatabase.execSQL(query, args) }
        fun payloads() = files.walkTopDown().filter { it.isFile && it.extension == "payload" }.toList()
        fun close() {
            runtime.clear()
            try { ownedAccounts.forEach { keys().deleteAccount(hash(it)) } } finally {
                val database = context.getDatabasePath("real-language-learning.db").canonicalFile
                check(database.parentFile == cache.canonicalFile && database.name.startsWith("pattern-speaking-journal-$runId-"))
                context.deleteDatabase("real-language-learning.db")
                check(directory.name == "pattern-speaking-journal-$runId" && directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
                check(directory.deleteRecursively())
            }
        }
    }
}
