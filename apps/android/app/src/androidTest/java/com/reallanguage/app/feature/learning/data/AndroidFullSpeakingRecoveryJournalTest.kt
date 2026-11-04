package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Private UUID SQLite + test-only non-exportable key; no service or real audio is used. */
class AndroidFullSpeakingRecoveryJournalTest {
    @Test fun reloginDuringFinalSqlOwnershipCheckCannotReturnOldEpochPayload() = isolated {
        val old = journal(0)
        assertEquals(0L, old.save(null, pending))
        var reads = 0
        databaseOpened = { reads++ }
        assertEquals(FullSpeakingRecoveryLoad.Loaded(0, pending), old.load())
        assertTrue(reads > 0)
        val last = reads
        reads = 0
        val before = payloads().single().readBytes()
        databaseOpened = {
            if (++reads == last) { runtime.clear(); runtime.update("ISOLATED_RELOGIN", account) }
        }
        assertEquals(FullSpeakingRecoveryLoad.Unavailable, old.load())
        assertEquals(last, reads)
        databaseOpened = {}
        assertArrayEquals(before, payloads().single().readBytes())
        assertEquals(FullSpeakingRecoveryLoad.Loaded(0, pending), journal(0).load())
    }
    @Test fun exactBodyRestoresThroughFreshFactoryButRoundCannotBeConfusedWithSameMode() = isolated {
        val first = journal(0)
        assertEquals(FullSpeakingRecoveryLoad.Missing, first.load())
        assertNull(keys().existing(fixedSourceAccountHash(account), 1))
        assertEquals(0L, first.save(null, pending))
        assertEquals(FullSpeakingRecoveryLoad.Loaded(0, pending), journal(0).load())
        assertNull(keys().existing(fixedSourceAccountHash(account), 1)!!.encoded)
        val encrypted = payloads().single().readBytes()
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("NOT_AUDIO"))
        assertEquals(FullSpeakingRecoveryLoad.Unavailable, journal(1).load())
        round(1)
        assertEquals(FullSpeakingRecoveryLoad.Unavailable, first.load())
        assertNull(first.save(0, pending.copy(phase = FullSpeakingRecoveryPhase.Blocked, requestBody = null)))
        assertEquals(FullSpeakingRecoveryLoad.Unavailable, journal(0).load())
        assertEquals(FullSpeakingRecoveryLoad.Missing, journal(1).load())
        assertArrayEquals(encrypted, payloads().single().readBytes())
    }

    @Test fun changedCheckpointFixedTextAndSessionRejectOldRequestsWithoutOverwritingFiles() {
        listOf("module", "text", "context", "completed", "session", "bad-progress").forEach { change -> isolated {
            val original = journal(0)
            assertEquals(0L, original.save(null, pending))
            val encrypted = payloads().single().readBytes()
            when (change) {
                "module" -> sql("update learning_checkpoints set module_id='LR-07' where account_id=?", arrayOf(account))
                "text" -> sql("update fixed_sentences set text_en='Changed.' where account_id=?", arrayOf(account))
                "context" -> sql("update fixed_sentences set role='CONTEXT' where account_id=?", arrayOf(account))
                "completed" -> sql("update chapters set status='COMPLETED' where account_id=?", arrayOf(account))
                "session" -> { runtime.clear(); runtime.update("ISOLATED_RELOGIN", account) }
                "bad-progress" -> sql("insert into learning_module_progress values (?,?,?,'LR-06','bad-json')", arrayOf(account, chapter, sentence))
            }
            assertEquals(change, FullSpeakingRecoveryLoad.Unavailable, original.load())
            assertNull(original.save(0, pending.copy(phase = FullSpeakingRecoveryPhase.Cleared, requestBody = null)))
            assertArrayEquals(encrypted, payloads().single().readBytes())
        } }
    }

    @Test fun damagedPayloadOrMissingKeyFailsClosedAndDoesNotRegenerateKey() {
        listOf(false, true).forEach { loseKey -> isolated {
            assertEquals(0L, journal(0).save(null, pending))
            if (loseKey) keys().deleteAccount(fixedSourceAccountHash(account))
            else payloads().single().let { file -> file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }) }
            val bytes = payloads().single().readBytes()
            assertEquals(FullSpeakingRecoveryLoad.Unavailable, journal(0).load())
            assertNull(journal(0).save(null, pending))
            assertArrayEquals(bytes, payloads().single().readBytes())
            if (loseKey) assertNull(keys().existing(fixedSourceAccountHash(account), 1))
        } }
    }

    @Test fun clearingOneAccountRemovesItsSpeakingKeyAndPreservesOtherAccount() = isolated {
        assertEquals(0L, journal(0).save(null, pending))
        val other = "other-$runId"
        seed(other, "other-$chapter", "other-$sentence")
        val otherRuntime = SessionRuntime().apply { update("ISOLATED_OTHER", other) }
        val otherJournal = AndroidFullSpeakingRecoveryJournal(context, otherRuntime, "other-$chapter", "other-$sentence", target, 0, keys())
        assertEquals(0L, otherJournal.save(null, pending))
        assertTrue(com.reallanguage.app.feature.home.data.AndroidAccountDataCleaner(context, fullSpeakingKeys = keys()).clear(account))
        assertNull(keys().existing(fixedSourceAccountHash(account), 1))
        assertNotNull(keys().existing(fixedSourceAccountHash(other), 1))
        assertEquals(FullSpeakingRecoveryLoad.Loaded(0, pending), otherJournal.load())
        assertFalse(accountLearningAttemptsDirectory(context.filesDir, account).exists())
    }

    @Test fun asrAudioRequestCanBecomeEvaluationWithoutPlaintextAudioFileOrChangedBytes() = isolated {
        val raw = java.util.Base64.getEncoder().encodeToString("NOT_AUDIO_BYTES".toByteArray())
        val original = org.json.JSONObject().put("chapterId", chapter).put("sentenceId", sentence)
            .put("audioBase64", raw).put("audioMimeType", "audio/mp4").put("commandId", "original-command").toString()
        val next = org.json.JSONObject(JsonFullSentenceSpeakingWireCodec().evaluationRequestFromTranscription(original, target, "I felt calm.", true))
        assertEquals(raw, next.getString("audioBase64")); assertEquals(chapter, next.getString("chapterId"))
        assertEquals(sentence, next.getString("sentenceId")); assertEquals("audio/mp4", next.getString("audioMimeType"))
        assertNotEquals("original-command", next.getString("commandId"))
        assertTrue(next.getBoolean("answerAudioPlayed")); assertTrue(next.getBoolean("targetHidden"))
        assertTrue(next.getBoolean("userTranscriptConfirmed")); assertEquals("I felt calm.", next.getString("confirmedTranscript"))
    }

    private fun isolated(block: suspend Fixture.() -> Unit) = runBlocking { withContext(Dispatchers.IO) {
        val fixture = Fixture(); try { fixture.block() } finally { fixture.close() }
    } }

    private class Fixture {
        val runId = UUID.randomUUID().toString()
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val directory = File(base.cacheDir, "full-speaking-journal-$runId").also { check(it.mkdir()) }
        private val files = File(directory, "files").also { check(it.mkdir()) }
        private val cache = File(directory, "cache").also { check(it.mkdir()) }
        private val scoped = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
            override fun getNoBackupFilesDir(): File = files
        }
        private val isolatedDatabase = IsolatedLearningDatabaseContext(scoped, "full-speaking-journal")
        var databaseOpened: () -> Unit = {}
        val context = object : ContextWrapper(isolatedDatabase) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = isolatedDatabase.getDatabasePath(name).also { databaseOpened() }
        }
        val account = "full-journal-$runId"; val chapter = "chapter-$runId"; val sentence = "sentence-$runId"
        val target = "I felt calm."
        val namespace = "reallanguage.full-speaking.payload.test.${runId.replace("-", "") }"
        val runtime = SessionRuntime().apply { update("ISOLATED_TOKEN", account) }
        val pending = FullSpeakingRecoverySnapshot(UUID.randomUUID().toString(), FullSpeakingRecoveryPhase.TrainingPending,
            requestBody = "ISOLATED_NOT_AUDIO")
        init { seed(account, chapter, sentence) }
        fun keys() = AndroidFullSpeakingPayloadKeys(namespace, canUseAccount = { true })
        fun journal(round: Int) = AndroidFullSpeakingRecoveryJournal(context, runtime, chapter, sentence, target, round, keys())
        fun seed(owner: String, chapterId: String, sentenceId: String) {
            val flow = "flow-${UUID.randomUUID()}"
            sql("insert into formation_flows values (?,?,'FIXED','DIARY','隔离恢复测试')", arrayOf(owner, flow))
            sql("insert into chapters values (?,?,?,0,'IN_PROGRESS')", arrayOf(owner, flow, chapterId))
            sql("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?,?,?,0,'我感到平静。',?,'FOCUS')", arrayOf(owner, chapterId, sentenceId, target))
            sql("insert into learning_checkpoints values (?,?,'LR-06',?)", arrayOf(owner, chapterId, sentenceId))
        }
        fun round(round: Int) = sql("insert or replace into learning_module_progress values (?,?,?,'LR-06',?)",
            arrayOf(account, chapter, sentence, org.json.JSONObject().put("version", "1").put("attemptIndex", round.toString()).toString()))
        fun sql(query: String, args: Array<String>) = FormationDatabase(context).use { it.writableDatabase.execSQL(query, args) }
        fun payloads() = files.walkTopDown().filter { it.isFile && it.extension == "payload" }.toList()
        fun close() {
            runtime.clear()
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.aliases().toList().filter { it.startsWith("$namespace.") }.forEach { alias ->
                check(alias.matches(Regex("${Regex.escape(namespace)}\\.[a-f0-9]{64}\\.v1")))
                store.deleteEntry(alias)
            }
            check(directory.name == "full-speaking-journal-$runId" && directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            context.deleteDatabase("real-language-learning.db")
            check(directory.deleteRecursively())
        }
    }
}
