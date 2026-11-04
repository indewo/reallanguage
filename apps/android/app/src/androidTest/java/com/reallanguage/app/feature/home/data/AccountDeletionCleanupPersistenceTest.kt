package com.reallanguage.app.feature.home.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import com.reallanguage.app.testing.withExplicitSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class AccountDeletionCleanupPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private lateinit var journalDirectory: File
    private val owned = "deletion-owned-${UUID.randomUUID()}"
    private val other = "deletion-other-${UUID.randomUUID()}"
    private val keys = FixedSourceTestKeys()
    private val db get() = database.writableDatabase
    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "account-cleanup")
        database = FormationDatabase(context)
        journalDirectory = Files.createTempDirectory(context.cacheDir.toPath(), "account-deletion-journal-").toFile()
    }
    @After fun tearDown() { database.close(); context.deleteDatabase("real-language-learning.db"); journalDirectory.deleteRecursively() }

    @Test fun confirmedReceiptSurvivesRecreationAndCompletionRemovesOnlyTheExactAccountAndCommand() = runBlocking {
        val first = ConfirmedAccountDeletion(owned, "command-1")
        val second = ConfirmedAccountDeletion(other, "command-2")
        val journal = AndroidAccountDeletionCleanupJournal(context, journalDirectory)
        assertTrue(journal.record(first)); assertTrue(journal.record(second))
        val restored = AndroidAccountDeletionCleanupJournal(context, journalDirectory)
        assertEquals(setOf(first, second), (restored.read() as AccountDeletionCleanupLoad.Ready).entries.toSet())
        assertFalse(restored.complete(first.copy(commandId = "wrong-command")))
        assertTrue(restored.complete(first))
        assertEquals(listOf(second), (AndroidAccountDeletionCleanupJournal(context, journalDirectory).read() as AccountDeletionCleanupLoad.Ready).entries)
        assertTrue(restored.complete(first))
    }

    @Test fun anotherCommandCannotOverwriteAnOutstandingConfirmedDeletion() = runBlocking {
        val journal = AndroidAccountDeletionCleanupJournal(context, journalDirectory)
        val deletion = ConfirmedAccountDeletion(owned, "stable-command")
        assertTrue(journal.record(deletion)); assertTrue(journal.record(deletion))
        assertFalse(journal.record(deletion.copy(commandId = "other-command")))
        assertEquals(listOf(deletion), (journal.read() as AccountDeletionCleanupLoad.Ready).entries)
    }

    @Test fun malformedReceiptIsNotTreatedAsAnEmptySuccessfulJournal() = runBlocking {
        journalDirectory.resolve("${fixedSourceAccountHash(owned)}.pending").writeText("{broken")
        assertEquals(AccountDeletionCleanupLoad.Unavailable, AndroidAccountDeletionCleanupJournal(context, journalDirectory).read())
    }

    @Test fun accountCleanupDeletesItsCiphertextAndPurposeKeyButKeepsAnotherAccountsSource() = runBlocking {
        seed(owned); seed(other)
        val ownedHash = fixedSourceAccountHash(owned); val otherHash = fixedSourceAccountHash(other)
        assertNotNull(keys.existing(ownedHash, 1)); assertNotNull(keys.existing(otherHash, 1))
        assertTrue(AndroidAccountDataCleaner(context, keys).clear(owned))
        assertEquals(0, count("fixed_source_payloads", owned)); assertEquals(1, count("fixed_source_payloads", other))
        assertNull(keys.existing(ownedHash, 1)); assertNotNull(keys.existing(otherHash, 1))
        assertTrue(AndroidFixedSourceRepository(context, session(other), AesGcmFixedSourcePayloadCodec(keys)).load("flow") is FixedSourceLoad.Loaded)
    }

    @Test fun ignoredSqlDeletionFailsAtomicallyAndCannotEraseTheKeyForRetainedCiphertext() = runBlocking {
        seed(owned)
        db.execSQL("create trigger isolated_ignore_source_cleanup before delete on fixed_source_payloads begin select raise(IGNORE); end")
        assertFalse(AndroidAccountDataCleaner(context, keys).clear(owned))
        assertEquals(1, count("fixed_source_payloads", owned)); assertEquals(1, count("chapters", owned))
        assertNotNull(keys.existing(fixedSourceAccountHash(owned), 1))
    }

    @Test fun aKeyDeletionThatDidNotActuallyDeleteCannotReportCompleteAndCanBeRetried() = runBlocking {
        seed(owned)
        val failedKeys = object : FormationPayloadKeys by keys { override fun deleteAccount(accountSubjectHash: String) { } }
        assertFalse(AndroidAccountDataCleaner(context, failedKeys).clear(owned))
        assertNotNull(keys.existing(fixedSourceAccountHash(owned), 1))
        assertTrue(AndroidAccountDataCleaner(context, keys).clear(owned))
        assertNull(keys.existing(fixedSourceAccountHash(owned), 1))
    }

    @Test fun productionWireRequiresTheExplicitDeletedStatusAndRejectsMalformedOrDifferentResults() {
        val wire = JsonAccountDeletionWireCodec()
        assertTrue(wire.confirmsDeletion("{\"status\":\"DELETED\"}"))
        listOf("", "{}", "{\"status\":\"PENDING\"}", "{\"status\":true}", "{\"status\":\"DELETED\"} trailing")
            .forEach { assertFalse(wire.confirmsDeletion(it)) }
    }

    @Test fun aRevokedPurposeKeyWriterCannotRecreateItsKeyAfterCleanup() = runBlocking {
        withContext(Dispatchers.IO) {
            var allowed = true
            val namespace = "reallanguage.fixed-source.payload.test.${UUID.randomUUID().toString().replace("-", "")}"
            val hash = fixedSourceAccountHash(owned)
            val guarded = AndroidFixedSourcePayloadKeys(namespace, canUseAccount = { it == hash && allowed })
            val cleanup = AndroidFixedSourcePayloadKeys(namespace)
            try {
                guarded.createIfAbsent(hash, 1)
                allowed = false
                cleanup.deleteAccount(hash)
                assertNull(cleanup.existing(hash, 1))
                assertTrue(runCatching { guarded.createIfAbsent(hash, 1) }.isFailure)
                assertNull(cleanup.existing(hash, 1))
            } finally { cleanup.deleteAccount(hash) }
        }
    }

    @Test fun ordinaryLogoutAndLoginPreserveTheFormalSourceAndItsKey() = runBlocking {
        seed(owned)
        val runtime = session(owned)
        val reader = AndroidFixedSourceRepository(context, runtime, AesGcmFixedSourcePayloadCodec(keys))
        val before = reader.load("flow")
        assertTrue(before is FixedSourceLoad.Loaded)
        runtime.clear()
        assertEquals(FixedSourceLoad.Unavailable, reader.load("flow"))
        runtime.update("new-token", owned)
        assertEquals(before, AndroidFixedSourceRepository(context, runtime, AesGcmFixedSourcePayloadCodec(keys)).load("flow"))
        assertNotNull(keys.existing(fixedSourceAccountHash(owned), 1))
    }

    private suspend fun seed(account: String) {
        val request = ContentFixRequest("flow", "fix", listOf(EnglishChapterDraft("c", listOf(EnglishSentenceDraft("u", "原始来源。", true,
            listOf(EnglishCandidate("e", "Original source.")))))), mapOf("u" to "e"), emptyList(), emptySet())
            .withExplicitSource("原始来源。", listOf(FixedSourceUnit("u", 0, "原始来源。")))
        assertTrue(AndroidContentFixRepository(context, session(account), sourceCipher = AesGcmFixedSourcePayloadCodec(keys)).fix(request) is ContentFixResult.Fixed)
    }
    private fun session(account: String) = SessionRuntime().apply { update("isolated-token", account) }
    private fun count(table: String, account: String) = db.rawQuery("select count(*) from $table where account_id=?", arrayOf(account)).use { it.moveToFirst(); it.getInt(0) }
}
