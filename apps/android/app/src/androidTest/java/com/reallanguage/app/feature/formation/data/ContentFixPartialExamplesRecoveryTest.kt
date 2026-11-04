package com.reallanguage.app.feature.formation.data

import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.testing.withExplicitSource
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real private SQLite/source encryption, isolated paid-provider responses; no HTTP or AI quality claim. */
class ContentFixPartialExamplesRecoveryTest {
    private lateinit var context: PrivateContext
    private lateinit var runtime: SessionRuntime
    private lateinit var account: String
    private val keys = MemoryKeys()
    private val recoveryKeys = MemoryKeys()
    private val calls = mutableListOf<KnowledgeExampleGenerationRequest>()
    private lateinit var before: String

    @Before fun setUp() {
        val id = UUID.randomUUID().toString()
        account = "partial-examples-$id"
        context = PrivateContext(InstrumentationRegistry.getInstrumentation().targetContext, id)
        runtime = session()
        FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into formation_flows values (?,'foreign-flow','FIXED','DIARY','隔离其他账号')", arrayOf("foreign-$account"))
            helper.writableDatabase.execSQL("insert into chapters values (?,'foreign-flow','foreign-chapter',0,'COMPLETED')", arrayOf("foreign-$account"))
        }
        before = sqlHash()
    }

    @After fun tearDown() { context.closeFixture(); keys.clear(); recoveryKeys.clear() }

    @Test fun partialDeliveredExampleSurvivesNewRepositoryAndOnlyMissingTargetIsRequested() = runBlocking {
        val first = repository { if (it.targetId == "t1") generated(it) else KnowledgeExampleGenerationResult.Failed }
        assertEquals(ContentFixResult.Failed, first.fix(request))
        assertEquals(listOf("t1", "t2"), calls.map { it.targetId })
        assertEquals(before, sqlHash())
        val second = repository(session()) { if (it.targetId == "t2") generated(it) else KnowledgeExampleGenerationResult.Failed }
        assertTrue("Accepted t1 must survive a new owner-bound repository", second.fix(request) is ContentFixResult.Fixed)
        assertEquals(listOf("t1", "t2", "t2"), calls.map { it.targetId })
        assertEquals("cmd:t2", calls.last().commandId)
        assertStoredDetailsAndForeignRows()
        val count = calls.size
        assertTrue(repository(session()) { error("Already fixed must not request") }.fix(request) is ContentFixResult.Fixed)
        assertEquals(count, calls.size)
    }

    @Test fun finalTransactionFailureRetainsAllDeliveredExamplesForLocalOnlyRetry() = runBlocking {
        FormationDatabase(context).use { it.writableDatabase.execSQL(
            "create trigger fixture_partial_fix_failure before insert on fix_commands begin select raise(abort,'ISOLATED_FIX_FAILURE'); end") }
        assertEquals(ContentFixResult.Failed, repository { generated(it) }.fix(request))
        assertEquals(listOf("t1", "t2"), calls.map { it.targetId })
        assertEquals(before, sqlHash())
        FormationDatabase(context).use { it.writableDatabase.execSQL("drop trigger fixture_partial_fix_failure") }
        assertTrue("SQL retry must reuse both delivered details", repository(session()) {
            KnowledgeExampleGenerationResult.Failed
        }.fix(request) is ContentFixResult.Fixed)
        assertEquals(listOf("t1", "t2"), calls.map { it.targetId })
        assertStoredDetailsAndForeignRows()
    }

    @Test fun sameCommandWithChangedMeaningCannotReuseOrResubmitAnotherBody() = runBlocking {
        assertEquals(ContentFixResult.Failed, repository {
            if (it.targetId == "t1") generated(it) else KnowledgeExampleGenerationResult.Failed
        }.fix(request))
        val changed = request.copy(targets = request.targets.map {
            if (it.targetId == "t1") it.copy(explanationZh = "跑步") else it
        })
        val count = calls.size
        assertEquals(ContentFixResult.Failed, repository(session()) { KnowledgeExampleGenerationResult.Failed }.fix(changed))
        assertEquals("An existing command cannot acquire changed generation input", count, calls.size)
        assertEquals(before, sqlHash())
    }

    @Test fun cancellationOnLaterTargetPropagatesAndDoesNotDiscardAcceptedEarlierTarget() = runBlocking {
        try {
            repository { if (it.targetId == "t1") generated(it) else throw CancellationException("ISOLATED_CANCEL") }.fix(request)
            fail("Cancellation must not become successful or empty content")
        } catch (cancelled: CancellationException) { assertEquals("ISOLATED_CANCEL", cancelled.message) }
        assertEquals(before, sqlHash())
        assertTrue(repository(session()) {
            if (it.targetId == "t2") generated(it) else KnowledgeExampleGenerationResult.Failed
        }.fix(request) is ContentFixResult.Fixed)
        assertEquals(listOf("t1", "t2", "t2"), calls.map { it.targetId })
        assertStoredDetailsAndForeignRows()
    }

    @Test fun refreshKeepsOwnerButSameAccountNewSessionRejectsOldInstanceAndNewOneResumes() = runBlocking {
        var failSecond = true
        val old = repository { if (it.targetId == "t2" && failSecond) KnowledgeExampleGenerationResult.Failed else generated(it) }
        assertEquals(ContentFixResult.Failed, old.fix(request))
        val epoch = runtime.accountEpoch
        runtime.update("ISOLATED_REFRESH", account)
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(ContentFixResult.Failed, old.fix(request))
        assertEquals(listOf("t1", "t2", "t2"), calls.map { it.targetId })
        runtime.clear(); runtime.update("ISOLATED_NEW_SESSION", account)
        assertNotEquals(epoch, runtime.accountEpoch)
        val count = calls.size
        assertEquals(ContentFixResult.Failed, old.fix(request)); assertEquals(count, calls.size)
        failSecond = false
        assertTrue(repository { generated(it) }.fix(request) is ContentFixResult.Fixed)
        assertEquals(listOf("t1", "t2", "t2", "t2"), calls.map { it.targetId })
        assertStoredDetailsAndForeignRows()
    }

    @Test fun ownerChangedBeforeGeneratedReturnCannotCacheOrCommitLateDetails() = runBlocking {
        assertEquals(ContentFixResult.Failed, repository {
            runtime.clear(); runtime.update("ISOLATED_OTHER", "foreign-$account")
            generated(it)
        }.fix(request))
        assertEquals(before, sqlHash())
        assertTrue(repository(session()) { generated(it) }.fix(request) is ContentFixResult.Fixed)
        assertEquals("The rejected owner's response must not be a reusable delivery", listOf("t1", "t1", "t2"), calls.map { it.targetId })
        assertStoredDetailsAndForeignRows()
    }

    @Test fun encryptedAcceptedDetailsCannotBeOverwrittenAfterCiphertextDamage() = runBlocking {
        assertEquals(ContentFixResult.Failed, repository {
            if (it.targetId == "t1") generated(it) else KnowledgeExampleGenerationResult.Failed
        }.fix(request))
        val payload = context.payload()
        val original = payload.readBytes()
        assertFalse(String(original, Charsets.UTF_8).contains("隔离完整中文例句"))
        assertFalse(String(original, Charsets.UTF_8).contains("I run a cafe"))
        val damaged = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        payload.writeBytes(damaged)
        val count = calls.size
        assertEquals(ContentFixResult.Failed, repository(session()) { generated(it) }.fix(request))
        assertEquals(count, calls.size); assertArrayEquals(damaged, payload.readBytes()); assertEquals(before, sqlHash())
    }

    @Test fun missingRecoveryKeyIsNotRecreatedAndCannotRequestAgain() = runBlocking {
        assertEquals(ContentFixResult.Failed, repository {
            if (it.targetId == "t1") generated(it) else KnowledgeExampleGenerationResult.Failed
        }.fix(request))
        val payload = context.payload(); val bytes = payload.readBytes()
        val created = recoveryKeys.creations
        recoveryKeys.deleteAccount(fixedSourceAccountHash(account))
        val count = calls.size
        assertEquals(ContentFixResult.Failed, repository(session()) { generated(it) }.fix(request))
        assertEquals(count, calls.size); assertEquals(created, recoveryKeys.creations)
        assertNull(recoveryKeys.existing(fixedSourceAccountHash(account), 1))
        assertArrayEquals(bytes, payload.readBytes()); assertEquals(before, sqlHash())
    }

    @Test fun deliveredWriteFailureRetriesLocalCandidateAndLostAckDoesNotRepeatProvider() = runBlocking {
        var writes = 0
        var reject = true
        val committer = FormationAtomicFileCommitter { target, bytes, allowed ->
            check(allowed() && target.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            writes++
            if (writes == 2 && reject) error("ISOLATED_BEFORE_DELIVERY_WRITE")
            target.writeBytes(bytes)
            if (writes == 3) error("ISOLATED_LOST_WRITE_ACK")
        }
        val repository = repository(committer = committer) { generated(it) }
        assertEquals(ContentFixResult.Failed, repository.fix(request))
        assertEquals(listOf("t1"), calls.map { it.targetId }); assertEquals(before, sqlHash())
        reject = false
        assertTrue(repository.fix(request) is ContentFixResult.Fixed)
        assertEquals(listOf("t1", "t2"), calls.map { it.targetId }); assertStoredDetailsAndForeignRows()
    }

    @Test fun twoRepositoryInstancesShareOneInFlightGenerationAndReadTheSameFixedResult() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val provider: suspend (KnowledgeExampleGenerationRequest) -> KnowledgeExampleGenerationResult = {
            if (it.targetId == "t1") { entered.complete(Unit); release.await() }
            generated(it)
        }
        val first = async(Dispatchers.IO) { repository(provider = provider).fix(request) }
        withTimeout(5_000) { entered.await() }
        val second = async(Dispatchers.IO) { repository(session(), provider = provider).fix(request) }
        release.complete(Unit)
        val firstResult = withTimeout(5_000) { first.await() }
        val secondResult = withTimeout(5_000) { second.await() }
        assertTrue(firstResult is ContentFixResult.Fixed); assertEquals(firstResult, secondResult)
        assertEquals(listOf("t1", "t2"), calls.map { it.targetId }); assertStoredDetailsAndForeignRows()
    }

    @Test fun lateInstancePastPriorCheckReadsConcurrentFixedResultAfterCacheWasConsumed() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val realCipher = AesGcmFixedSourcePayloadCodec(keys)
        val delayedCipher = object : FixedSourcePayloadCodec {
            override fun encrypt(identity: FixedSourcePayloadIdentity, projection: FixedSourceProjection): ByteArray? {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                return realCipher.encrypt(identity, projection)
            }
            override fun decrypt(identity: FixedSourcePayloadIdentity, envelope: ByteArray) = realCipher.decrypt(identity, envelope)
        }
        val late = async(Dispatchers.IO) { repository(session(), cipher = delayedCipher) { generated(it) }.fix(request) }
        try {
            withTimeout(5_000) { entered.await() }
            val fixed = repository { generated(it) }.fix(request)
            assertTrue(fixed is ContentFixResult.Fixed)
            assertEquals(listOf("t1", "t2"), calls.map { it.targetId })
            release.countDown()
            assertEquals("Another successful commit is not an example-generation failure", fixed, withTimeout(5_000) { late.await() })
            assertEquals(listOf("t1", "t2"), calls.map { it.targetId }); assertStoredDetailsAndForeignRows()
        } finally { release.countDown() }
    }

    private fun session() = SessionRuntime().apply { update("ISOLATED_TOKEN", account) }
    private fun repository(owner: SessionRuntime = runtime, committer: FormationAtomicFileCommitter? = null,
        cipher: FixedSourcePayloadCodec = AesGcmFixedSourcePayloadCodec(keys),
        provider: suspend (KnowledgeExampleGenerationRequest) -> KnowledgeExampleGenerationResult) =
        AndroidContentFixRepository(context, owner, knowledgeExamples = { value -> calls += value; provider(value) },
            sourceCipher = cipher, exampleRecoveryKeys = recoveryKeys, exampleRecoveryCommitter = committer)

    private fun generated(value: KnowledgeExampleGenerationRequest) = KnowledgeExampleGenerationResult.Generated(
        KnowledgeTargetDetails(value.targetId, if (value.targetId == "t1") "/rʌn/" else "/ˈkæfeɪ/", value.display,
            "隔离经营咖啡店场景", (1..3).map { index ->
                KnowledgeExampleDraft("I ${if (value.targetId == "t1") "run" else "visit"} a cafe $index.",
                    "隔离完整中文例句第${index}条。", "I ${if (value.targetId == "t1") "run" else "visit"} a cafe $index.")
            }))

    private fun assertStoredDetailsAndForeignRows() = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        assertEquals(before, sqlHash(protectedOnly = true))
        fun count(table: String) = db.rawQuery("select count(*) from $table where account_id=?", arrayOf(account)).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, count("fix_commands")); assertEquals(1, count("chapters"))
        assertEquals(2, count("target_vocabulary_details")); assertEquals(0, count("vocabulary_items")); assertEquals(0, count("pattern_items"))
        db.rawQuery("select pronunciation_text,examples_json from target_vocabulary_details where account_id=? order by pronunciation_text", arrayOf(account)).use { cursor ->
            val found = mutableMapOf<String, org.json.JSONArray>()
            while (cursor.moveToNext()) found[cursor.getString(0)] = org.json.JSONArray(cursor.getString(1))
            assertEquals(setOf("run", "cafe"), found.keys)
            found.values.forEach { examples -> assertEquals(3, examples.length()); assertEquals("隔离完整中文例句第1条。", examples.getJSONObject(0).getString("textZh")) }
        }
    }

    private fun sqlHash(protectedOnly: Boolean = false): String = FormationDatabase(context).use { helper ->
        val db = helper.readableDatabase
        val tables = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' order by name", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        val rows = tables.flatMap { table ->
            check(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            db.rawQuery("select * from \"$table\"", null).use { c -> buildList {
                add("TABLE:$table:${c.columnNames.joinToString(",")}")
                while (c.moveToNext()) {
                    val own = c.getColumnIndex("account_id").takeIf { it >= 0 }?.let(c::getString) == account
                    val allowed = table in setOf("formation_flows", "chapters", "fixed_sentences", "target_selections",
                        "target_vocabulary_details", "fixed_source_payloads", "fix_commands")
                    if (!protectedOnly || !own || !allowed) add(table + (0 until c.columnCount).joinToString("") { column ->
                        val value = when (c.getType(column)) { Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_BLOB -> c.getBlob(column).joinToString("") { "%02x".format(it) }; else -> c.getString(column) }
                        value?.let { "${it.length}:$it" } ?: "-1:"
                    })
                }
            } }
        }.sorted()
        MessageDigest.getInstance("SHA-256").digest(rows.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private class MemoryKeys : FormationPayloadKeys {
        private val values = mutableMapOf<String, SecretKey>()
        var creations = 0; private set
        @Synchronized override fun existing(accountSubjectHash: String, keyVersion: Int) = values[accountSubjectHash]
        @Synchronized override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int) = values.getOrPut(accountSubjectHash) {
            creations++
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        @Synchronized override fun deleteAccount(accountSubjectHash: String) { values.remove(accountSubjectHash) }
        fun clear() = values.clear()
    }

    private class PrivateContext(base: Context, id: String) : ContextWrapper(base) {
        private val root = base.cacheDir.canonicalFile
        private val directory = File(root, "partial-fix-$id").also { check(it.mkdir() && it.canonicalFile == it.absoluteFile) }
        private val db = File(directory, "fixture.db")
        private val files = File(directory, "files").also { check(it.mkdir()) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = files
        override fun getNoBackupFilesDir() = files
        override fun getCacheDir() = directory
        override fun getDatabasePath(name: String): File { check(name == "real-language-learning.db"); return db }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?) = openOrCreateDatabase(name, mode, factory)
        fun payload(): File = files.walkTopDown().filter { it.isFile && it.extension == "payload" }.single().also {
            check(it.canonicalPath.startsWith(files.canonicalPath + File.separator))
        }
        fun closeFixture() {
            check(directory.name.matches(Regex("partial-fix-[a-f0-9-]{36}")) && directory.canonicalFile.parentFile == root)
            SQLiteDatabase.deleteDatabase(db); check(directory.deleteRecursively())
        }
    }

    private companion object {
        val sentence = EnglishSentenceDraft("u1", "我经营一家咖啡店。", true, listOf(EnglishCandidate("e1", "I run a cafe.")))
        val targets = listOf(LearningTarget("t1", "u1", "run", "经营", LearningTargetType.WORD, learningScopes = setOf("CET_4")),
            LearningTarget("t2", "u1", "cafe", "咖啡店", LearningTargetType.WORD, learningScopes = setOf("CET_4")))
        val request = ContentFixRequest("flow", "cmd", listOf(EnglishChapterDraft("c1", listOf(sentence))),
            mapOf("u1" to "e1"), targets, setOf("t1", "t2")).withExplicitSource("我经营一家咖啡店。", listOf(FixedSourceUnit("u1", 0, "我经营一家咖啡店。")))
    }
}
