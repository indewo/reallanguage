package com.reallanguage.app.feature.formation.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import com.reallanguage.app.testing.withExplicitSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FixedSourceProjectionPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("isolated-token", "owner") }
    private val keys = FixedSourceTestKeys()
    private val codec get() = AesGcmFixedSourcePayloadCodec(keys)
    private val db get() = database.writableDatabase
    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "fixed-source-proof")
        database = FormationDatabase(context); db
    }
    @After fun tearDown() { database.close(); context.deleteDatabase("real-language-learning.db") }

    @Test fun newFixWithoutOriginalSourceFailsBeforeAnyAiOrChapterWrite() = runBlocking {
        var calls = 0
        val repository = AndroidContentFixRepository(context, runtime, knowledgeExamples = { calls++; KnowledgeExampleGenerationResult.Failed }, sourceCipher = codec)
        assertEquals(ContentFixResult.Failed, repository.fix(request().copy(sourceProjection = null)))
        assertEquals(0, calls)
        assertEquals(0, count("chapters")); assertEquals(0, count("fix_commands"))
    }

    @Test fun encryptionFailureCannotSpendOnExamplesOrSavePartialFormalContent() = runBlocking {
        var calls = 0
        var encryptions = 0
        val failed = object : FixedSourcePayloadCodec {
            override fun encrypt(identity: FixedSourcePayloadIdentity, projection: FixedSourceProjection): ByteArray? { encryptions++; return null }
            override fun decrypt(identity: FixedSourcePayloadIdentity, envelope: ByteArray): FixedSourceProjection? = null
        }
        val repository = AndroidContentFixRepository(context, runtime, knowledgeExamples = { calls++; KnowledgeExampleGenerationResult.Failed }, sourceCipher = failed)
        assertEquals(ContentFixResult.Failed, repository.fix(request()))
        assertEquals(1, encryptions); assertEquals(0, calls); assertEquals(0, count("chapters"))
    }

    @Test fun fixedSourceAndChaptersCommitTogetherAndExactRetryReusesPersistedCiphertext() = runBlocking {
        var encryptions = 0
        val real = codec
        val counted = object : FixedSourcePayloadCodec by real {
            override fun encrypt(identity: FixedSourcePayloadIdentity, projection: FixedSourceProjection): ByteArray? { encryptions++; return real.encrypt(identity, projection) }
        }
        val request = request().copy(targets = emptyList(), selectedTargetIds = emptySet()).let {
            it.copy(sourceProjection = it.sourceProjection!!.copy(selectedTargetIds = emptySet()))
        }
        val repository = AndroidContentFixRepository(context, runtime, sourceCipher = counted)
        val result = repository.fix(request)
        assertTrue(result is ContentFixResult.Fixed)
        assertEquals(FixedSourceLoad.Loaded(request.sourceProjection!!), AndroidFixedSourceRepository(context, runtime, real).load("flow"))
        assertTrue(hasSourceTable())
        val before = envelope()
        assertFalse(String(before, Charsets.UTF_8).contains("这句完整原文没有进入章节"))
        assertFalse(String(before, Charsets.UTF_8).contains("UNSELECTED ENGLISH"))
        assertEquals(1, count("fixed_source_payloads")); assertEquals(1, count("chapters"))
        assertEquals(result, repository.fix(request)); assertEquals(1, encryptions)
        assertArrayEquals(before, envelope())
    }

    @Test fun lastSqlWriteFailureRollsBackOriginalSourceAndAllChapterRows() = runBlocking {
        assertTrue(hasSourceTable())
        db.execSQL("create trigger isolated_reject_final_fix before insert on fix_commands begin select raise(ABORT,'isolated final write failure'); end")
        val result = AndroidContentFixRepository(context, runtime, sourceCipher = codec).fix(request())
        assertEquals(ContentFixResult.Failed, result)
        listOf("fixed_source_payloads", "formation_flows", "chapters", "fixed_sentences", "target_selections", "fix_commands").forEach { assertEquals(0, count(it)) }
    }

    @Test fun ownerChangeDuringEncryptionCannotPersistOrReadAnotherAccountsSource() = runBlocking {
        val real = codec
        var calls = 0
        val switching = object : FixedSourcePayloadCodec by real {
            override fun encrypt(identity: FixedSourcePayloadIdentity, projection: FixedSourceProjection): ByteArray? {
                calls++; val bytes = real.encrypt(identity, projection); runtime.update("other-token", "other"); return bytes
            }
        }
        assertEquals(ContentFixResult.Failed, AndroidContentFixRepository(context, runtime, sourceCipher = switching).fix(request()))
        assertEquals(1, calls); assertEquals(0, count("chapters"))
    }

    @Test fun schema23UpgradePreservesOldRowsButNeverBackfillsMissingOriginalText() = runBlocking {
        db.execSQL("insert into formation_flows values('owner','legacy','FIXED','DIARY','真实旧来源标题')")
        db.execSQL("drop table if exists fixed_source_payloads")
        db.version = 23
        database.close()
        database = FormationDatabase(context)
        assertEquals(26, db.version); assertTrue(hasSourceTable())
        assertEquals(0, count("fixed_source_payloads")); assertEquals(1, count("formation_flows"))
        assertEquals(FixedSourceLoad.Missing, AndroidFixedSourceRepository(context, runtime, codec).load("legacy"))
        db.rawQuery("select source_title from formation_flows where flow_id='legacy'", null).use { assertTrue(it.moveToFirst()); assertEquals("真实旧来源标题", it.getString(0)) }
    }

    @Test fun accountBoundReaderCannotReturnOldAccountProjectionAfterSwitch() = runBlocking {
        assertTrue(AndroidContentFixRepository(context, runtime, sourceCipher = codec).fix(request()) is ContentFixResult.Fixed)
        val old = AndroidFixedSourceRepository(context, runtime, codec)
        assertTrue(old.load("flow") is FixedSourceLoad.Loaded)
        runtime.update("other-token", "other")
        assertEquals(FixedSourceLoad.Unavailable, old.load("flow"))
        assertEquals(FixedSourceLoad.Missing, AndroidFixedSourceRepository(context, runtime, codec).load("flow"))
    }

    @Test fun sameCommandAndCandidateIdCannotClaimSuccessForDifferentConfirmedEnglish() = runBlocking {
        var calls = 0
        val repository = AndroidContentFixRepository(context, runtime, sourceCipher = codec)
        val first = request()
        assertTrue(repository.fix(first) is ContentFixResult.Fixed)
        val changed = first.copy(chapters = first.chapters.map { chapter -> chapter.copy(sentences = chapter.sentences.map { sentence ->
            sentence.copy(candidates = sentence.candidates.map { candidate ->
                if (candidate.candidateId == "e1") candidate.copy(textEn = "I drink coffee.") else candidate
            })
        }) })
        val replay = AndroidContentFixRepository(context, runtime,
            knowledgeExamples = { calls++; KnowledgeExampleGenerationResult.Failed }, sourceCipher = codec)
        assertEquals(ContentFixResult.Failed, replay.fix(changed))
        assertEquals(0, calls)
        db.rawQuery("select text_en from fixed_sentences", null).use { assertTrue(it.moveToFirst()); assertEquals("I drink tea.", it.getString(0)) }
    }

    @Test fun differentCommandCannotSpendAgainOnAnAlreadyFixedFlow() = runBlocking {
        val first = request()
        assertTrue(AndroidContentFixRepository(context, runtime, sourceCipher = codec).fix(first) is ContentFixResult.Fixed)
        var calls = 0
        val retry = AndroidContentFixRepository(context, runtime,
            knowledgeExamples = { calls++; KnowledgeExampleGenerationResult.Failed }, sourceCipher = codec)
        assertEquals(ContentFixResult.Failed, retry.fix(first.copy(commandId = "different-command")))
        assertEquals(0, calls)
        assertEquals(1, count("chapters")); assertEquals(1, count("fixed_source_payloads")); assertEquals(1, count("fix_commands"))
    }

    private fun request(): ContentFixRequest = ContentFixRequest("flow", "command",
        listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "喝茶。", true,
            listOf(EnglishCandidate("e1", "I drink tea."), EnglishCandidate("e2", "UNSELECTED ENGLISH")))))),
        mapOf("u1" to "e1"), listOf(LearningTarget("t1", "u1", "tea", "茶", LearningTargetType.WORD)), setOf("t1"))
        .withExplicitSource("喝茶。\n这句完整原文没有进入章节。", listOf(FixedSourceUnit("u1", 0, "喝茶。"), FixedSourceUnit("u2", 1, "这句完整原文没有进入章节。")))
    private fun count(table: String) = db.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }
    private fun hasSourceTable() = db.rawQuery("select count(*) from sqlite_master where type='table' and name='fixed_source_payloads'", null).use { it.moveToFirst(); it.getInt(0) == 1 }
    private fun envelope() = db.rawQuery("select envelope from fixed_source_payloads where account_id='owner' and flow_id='flow'", null).use { check(it.moveToFirst()); it.getBlob(0) }
}

internal class FixedSourceTestKeys : FormationPayloadKeys {
    private val keys = mutableMapOf<String, javax.crypto.SecretKey>()
    override fun existing(accountSubjectHash: String, keyVersion: Int) = keys[accountSubjectHash]
    override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int) = keys.getOrPut(accountSubjectHash) {
        javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
    override fun deleteAccount(accountSubjectHash: String) { keys.remove(accountSubjectHash) }
}
