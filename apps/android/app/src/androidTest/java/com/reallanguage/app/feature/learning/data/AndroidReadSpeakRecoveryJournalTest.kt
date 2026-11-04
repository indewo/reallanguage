package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.fixedSourceAccountHash
import com.reallanguage.app.feature.learning.domain.CheckpointSaveResult
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.feature.learning.domain.ReadSpeakCompletionSignal
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real private SQLite and non-exportable test KeyStore keys; NOT_AUDIO is never sent or played. */
class AndroidReadSpeakRecoveryJournalTest {
    @Test fun accountCleanupRemovesOnlyItsJournalAndPurposeKeyWithoutChangingAnotherAccount() = isolated {
        assertEquals(0L, journal().save(null, pending))
        val otherJournal = journal(session = session(otherAccount), chapterId = otherChapter, sentenceId = otherSentence)
        val otherPending = pending.copy(commandId = UUID.randomUUID().toString())
        assertEquals(0L, otherJournal.save(null, otherPending))
        val otherFile = payloads().single { it.canonicalPath.startsWith(accountLearningAttemptsDirectory(context.filesDir, otherAccount).canonicalPath + File.separator) }
        val otherBytes = otherFile.readBytes().toList()
        val protectedRows = tables().mapValues { (_, rows) -> rows.filterNot { it.contains(account) } }
        assertTrue(com.reallanguage.app.feature.home.data.AndroidAccountDataCleaner(context,
            learningFeedbackKeys = newKeys()).clear(account))
        assertFalse(accountLearningAttemptsDirectory(context.filesDir, account).exists())
        assertNull("Deleted account must not retain its learning-request encryption key",
            newKeys().existing(fixedSourceAccountHash(account), 1))
        assertNotNull(newKeys().existing(fixedSourceAccountHash(otherAccount), 1))
        assertEquals(otherBytes, otherFile.readBytes().toList())
        assertEquals(ReadSpeakRecoveryLoad.Loaded(0L, otherPending), otherJournal.load())
        assertEquals(protectedRows, tables())
    }
    @Test fun pendingRestoresExactRequestWithANewRuntimeAndNonExportableKeystoreKey() = isolated {
        val before = tables()
        val first = journal()
        assertEquals(ReadSpeakRecoveryLoad.Missing, first.load())
        assertTrue(aliases().isEmpty())
        assertEquals(0L, first.save(null, pending))
        val encrypted = payloadBytes()
        assertEquals(1, encrypted.size)
        val ciphertextText = encrypted.values.single().toByteArray().toString(Charsets.UTF_8)
        assertFalse(ciphertextText.contains(pending.commandId))
        assertFalse(ciphertextText.contains(requireNotNull(pending.requestBody)))
        val key = newKeys().existing(fixedSourceAccountHash(account), 1)
        assertNotNull(key)
        assertNull("AndroidKeyStore AES material must not be exportable", key!!.encoded)

        val recreatedRuntime = session(account)
        val restored = journal(session = recreatedRuntime, keys = newKeys())
        assertEquals(ReadSpeakRecoveryLoad.Loaded(0L, pending), restored.load())
        assertEquals("Lost local acknowledgement replays the original revision", 0L, restored.save(null, pending))
        assertEquals(encrypted, payloadBytes())
        assertEquals(before, tables())
    }

    @Test fun refreshKeepsOwnershipButReloginRejectsOldFactoryAndAllowsANewFactory() = isolated {
        val before = tables()
        val old = journal()
        assertEquals(0L, old.save(null, pending))
        val epoch = runtime.accountEpoch
        runtime.update("ISOLATED_REFRESH", account)
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(ReadSpeakRecoveryLoad.Loaded(0L, pending), old.load())
        val blocked = pending.copy(requestBody = null, phase = ReadSpeakRecoveryPhase.Blocked)
        assertEquals(1L, old.save(0L, blocked))
        val encrypted = payloadBytes()
        val keyAliases = aliases()

        runtime.clear()
        runtime.update("ISOLATED_RELOGIN", account)
        assertNotEquals(epoch, runtime.accountEpoch)
        val cleared = blocked.copy(phase = ReadSpeakRecoveryPhase.Cleared)
        assertEquals(ReadSpeakRecoveryLoad.Unavailable, old.load())
        assertNull(old.save(1L, cleared))
        assertEquals(encrypted, payloadBytes())
        assertEquals(keyAliases, aliases())
        val fresh = journal(keys = newKeys())
        assertEquals(ReadSpeakRecoveryLoad.Loaded(1L, blocked), fresh.load())
        assertEquals(2L, fresh.save(1L, cleared))
        assertEquals(ReadSpeakRecoveryLoad.Loaded(2L, cleared), journal(session = session(account)).load())
        assertEquals(before, tables())
    }

    @Test fun changedFixedFactsAndAdvancedCheckpointCannotReadOrOverwriteTheOriginalCiphertext() {
        listOf("different-text", "not-focus", "deleted-chapter", "completed-chapter", "advanced-lr04").forEach { change ->
            isolated {
                val old = journal()
                assertEquals(0L, old.save(null, pending))
                val encrypted = payloadBytes()
                val keyAliases = aliases()
                when (change) {
                    "different-text" -> sql("update fixed_sentences set text_en=? where account_id=? and chapter_id=? and unit_id=?",
                        arrayOf("I read a different sentence.", account, chapter, sentence))
                    "not-focus" -> sql("update fixed_sentences set role='CONTEXT' where account_id=? and chapter_id=? and unit_id=?",
                        arrayOf(account, chapter, sentence))
                    "deleted-chapter" -> sql("delete from chapters where account_id=? and chapter_id=?", arrayOf(account, chapter))
                    "completed-chapter" -> sql("update chapters set status='COMPLETED' where account_id=? and chapter_id=?",
                        arrayOf(account, chapter))
                    "advanced-lr04" -> assertEquals(CheckpointSaveResult.Saved,
                        AndroidLearningCheckpointRepository(context, runtime).save(OrdinaryLearningCheckpoint(chapter, "LR-04", sentence)))
                    else -> error("Unknown isolated mutation")
                }
                val mutatedTables = tables()
                listOf(old, journal(keys = newKeys())).forEach { candidate ->
                    assertEquals(change, ReadSpeakRecoveryLoad.Unavailable, candidate.load())
                    assertNull(change, candidate.save(0L, pending.copy(phase = ReadSpeakRecoveryPhase.Blocked, requestBody = null)))
                }
                assertEquals(change, encrypted, payloadBytes())
                assertEquals(change, keyAliases, aliases())
                assertEquals(change, mutatedTables, tables())
            }
        }
    }

    @Test fun anotherAccountAndAnotherFixedSentenceCannotReadOrOverwriteTheFirstJournal() = isolated {
        val before = tables()
        val original = journal()
        assertEquals(0L, original.save(null, pending))
        val encrypted = payloadBytes()
        val keyAliases = aliases()
        val otherRuntime = session(otherAccount)
        val otherOwner = journal(session = otherRuntime, chapterId = otherChapter, sentenceId = otherSentence)
        assertEquals(ReadSpeakRecoveryLoad.Missing, otherOwner.load())
        val anotherSentence = journal(chapterId = siblingChapter, sentenceId = siblingSentence)
        assertEquals(ReadSpeakRecoveryLoad.Missing, anotherSentence.load())
        val wrongAccountScope = journal(session = otherRuntime)
        assertEquals(ReadSpeakRecoveryLoad.Unavailable, wrongAccountScope.load())
        assertNull(wrongAccountScope.save(null, pending))
        assertEquals(encrypted, payloadBytes())
        assertEquals(keyAliases, aliases())
        assertEquals(ReadSpeakRecoveryLoad.Loaded(0L, pending), original.load())
        assertEquals(before, tables())
    }

    @Test fun missingKeyOrDamagedCiphertextCannotBeOverwrittenOrRecreateAKey() {
        listOf("missing-key", "damaged-ciphertext").forEach { failure ->
            isolated {
                val before = tables()
                assertEquals(0L, journal().save(null, pending))
                if (failure == "missing-key") {
                    newKeys().deleteAccount(fixedSourceAccountHash(account))
                    assertNull(newKeys().existing(fixedSourceAccountHash(account), 1))
                } else {
                    val payload = payloads().single()
                    val bytes = payload.readBytes()
                    assertTrue(bytes.isNotEmpty())
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                    payload.writeBytes(bytes)
                }
                val encrypted = payloadBytes()
                val keyAliases = aliases()
                val recreated = journal(session = session(account), keys = newKeys())
                assertEquals(failure, ReadSpeakRecoveryLoad.Unavailable, recreated.load())
                assertNull(failure, recreated.save(0L, pending.copy(phase = ReadSpeakRecoveryPhase.Blocked, requestBody = null)))
                assertNull(failure, recreated.save(null, pending))
                assertEquals(failure, encrypted, payloadBytes())
                assertEquals(failure, keyAliases, aliases())
                if (failure == "missing-key") assertNull(newKeys().existing(fixedSourceAccountHash(account), 1))
                assertEquals(before, tables())
            }
        }
    }

    @Test fun missingReadCreatesNoKeyAndForeignKeyNamespacesAreRejected() = isolated {
        val before = tables()
        assertEquals(ReadSpeakRecoveryLoad.Missing, journal().load())
        assertNull(newKeys().existing(fixedSourceAccountHash(account), 1))
        assertTrue(aliases().isEmpty())
        assertTrue(payloads().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { AndroidReadSpeakPayloadKeys("reallanguage.session.refresh") }
        assertThrows(IllegalArgumentException::class.java) { AndroidReadSpeakPayloadKeys("reallanguage.fixed-source.payload") }
        assertThrows(IllegalArgumentException::class.java) { newKeys().createIfAbsent("../outside-test-account", 1) }
        assertTrue(aliases().isEmpty())
        assertEquals(before, tables())
    }

    private fun isolated(block: suspend Fixture.() -> Unit) = runBlocking {
        withContext(Dispatchers.IO) {
            val fixture = Fixture()
            try { fixture.block() } finally { fixture.close() }
        }
    }

    private class Fixture {
        private val runId = UUID.randomUUID().toString()
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val directory = File(base.cacheDir, "read-speak-journal-test-$runId").also { check(it.mkdir()) }
        private val files = File(directory, "files").also { check(it.mkdir()) }
        private val cache = File(directory, "cache").also { check(it.mkdir()) }
        private val scoped = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
            override fun getNoBackupFilesDir(): File = files
        }
        val context: Context = IsolatedLearningDatabaseContext(scoped, "read-speak-journal")
        val account = "journal-account-${UUID.randomUUID()}"
        val otherAccount = "journal-other-${UUID.randomUUID()}"
        val chapter = "journal-chapter-${UUID.randomUUID()}"
        val sentence = "journal-sentence-${UUID.randomUUID()}"
        val siblingChapter = "journal-chapter-${UUID.randomUUID()}"
        val siblingSentence = "journal-sentence-${UUID.randomUUID()}"
        val otherChapter = "journal-chapter-${UUID.randomUUID()}"
        val otherSentence = "journal-sentence-${UUID.randomUUID()}"
        private val target = "I read at the café."
        private val namespace = "reallanguage.learning-feedback.payload.test.${runId.replace("-", "")}"
        val runtime = session(account)
        val pending: ReadSpeakRecoverySnapshot

        init {
            seed(account, chapter, sentence)
            seed(account, siblingChapter, siblingSentence)
            seed(otherAccount, otherChapter, otherSentence)
            val command = UUID.randomUUID().toString()
            val audio = File(cache, "isolated-not-audio.m4a").also { it.writeText("NOT_AUDIO_$runId") }
            pending = ReadSpeakRecoverySnapshot(ReadSpeakCompletionSignal.TOUCH_REACHED_END, command,
                JsonReadSpeakWireCodec().request(chapter, sentence, target, ReadSpeakCompletionSignal.TOUCH_REACHED_END,
                    CapturedReadSpeakAudio(audio, "audio/mp4"), command))
        }

        fun session(owner: String) = SessionRuntime().apply { update("ISOLATED_MEMORY_TOKEN", owner) }
        fun newKeys() = AndroidReadSpeakPayloadKeys(namespace, canUseAccount = {
            it == fixedSourceAccountHash(account) || it == fixedSourceAccountHash(otherAccount)
        })
        fun journal(session: SessionRuntime = runtime, chapterId: String = chapter, sentenceId: String = sentence,
                    keys: AndroidReadSpeakPayloadKeys = newKeys()) =
            AndroidReadSpeakRecoveryJournal(context, session, chapterId, sentenceId, target, keys)

        private fun seed(owner: String, chapterId: String, sentenceId: String) {
            val flow = "journal-flow-${UUID.randomUUID()}"
            sql("insert into formation_flows values (?, ?, 'FIXED', 'DIARY', '隔离恢复测试')", arrayOf(owner, flow))
            sql("insert into chapters values (?, ?, ?, 0, 'IN_PROGRESS')", arrayOf(owner, flow, chapterId))
            sql("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
                "values (?, ?, ?, 0, '我在咖啡馆读书。', ?, 'FOCUS')", arrayOf(owner, chapterId, sentenceId, target))
            sql("insert into learning_checkpoints values (?, ?, 'LR-03', ?)", arrayOf(owner, chapterId, sentenceId))
        }

        fun sql(statement: String, bindings: Array<String>) = FormationDatabase(context).use {
            it.writableDatabase.execSQL(statement, bindings)
        }

        fun tables(): Map<String, List<List<String?>>> = FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            val names = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null)
                .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            names.associateWith { table ->
                check(table.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")))
                db.rawQuery("select * from \"$table\"", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                            when (cursor.getType(column)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).joinToString("") { "%02x".format(it) }
                                else -> cursor.getString(column)
                            }
                        })
                    }.sortedBy { row -> row.joinToString("|") { it?.let { value -> "${value.length}:$value" } ?: "NULL" } }
                }
            }
        }

        fun payloads(): List<File> = listOf(account, otherAccount).flatMap { owner ->
            val ownerDirectory = accountLearningAttemptsDirectory(files, owner)
            check(ownerDirectory.canonicalPath.startsWith(files.canonicalPath + File.separator))
            if (!ownerDirectory.exists()) emptyList() else ownerDirectory.walkTopDown().filter { it.isFile && it.extension == "payload" }
                .onEach { check(it.canonicalPath.startsWith(ownerDirectory.canonicalPath + File.separator)) }.toList()
        }
        fun payloadBytes(): Map<String, List<Byte>> = payloads().associate {
            it.relativeTo(files).path to it.readBytes().toList()
        }
        fun aliases(): Set<String> = keyStore().aliases().toList().filter { it.startsWith("$namespace.") }.toSet()
        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun close() {
            runtime.clear()
            check(namespace.matches(Regex("reallanguage\\.learning-feedback\\.payload\\.test\\.[a-f0-9]{32}")))
            val store = keyStore()
            aliases().forEach { alias ->
                check(alias.matches(Regex("${Regex.escape(namespace)}\\.[a-f0-9]{64}\\.v1")))
                store.deleteEntry(alias)
            }
            check(directory.name == "read-speak-journal-test-$runId")
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            check(context.getDatabasePath("real-language-learning.db").canonicalPath.startsWith(directory.canonicalPath + File.separator))
            context.deleteDatabase("real-language-learning.db")
            check(directory.deleteRecursively())
        }
    }
}
