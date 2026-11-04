package com.reallanguage.app.core.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.*
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.home.data.AndroidIncompleteLearningRepository
import com.reallanguage.app.feature.home.domain.*
import com.reallanguage.app.feature.safety.domain.*
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** One UUID database and one UUID payload directory; no application account or original is removed. */
class FixedFormationCleanupPersistenceTest {
    private val id = UUID.randomUUID().toString()
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private lateinit var directory: File
    private val session = SessionRuntime().apply { update("isolated-session", "owner-$id") }
    private val draftKeys = PrivateCompletionKeys()
    private val fixedCodec = AesGcmFixedSourcePayloadCodec(PrivateCompletionKeys())

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "fixed-formation-cleanup")
        database = FormationDatabase(context)
        database.writableDatabase
        directory = File(context.cacheDir, "fixed-formation-cleanup-$id")
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
        check(directory.name == "fixed-formation-cleanup-$id")
        directory.deleteRecursively()
    }

    @Test fun committedSqlOriginalIsReadBackBeforeEncryptedDraftGetsATombstoneAndCannotReappear() = runBlocking {
        val before = snapshot()
        val expected = requireNotNull(before.toFixedSourceProjection("fixed-flow"))
        val runtime = runtime()
        assertEquals(FormationRecoverySave.Saved(0), runtime.repository.save(before, null))
        val fixed = fix(before) as? ContentFixResult.Fixed
        assertNotNull(fixed)
        val sqlBefore = sqlSummary()
        assertEquals(FixedSourceLoad.Loaded(expected), fixedSources().load("fixed-flow"))

        assertEquals(FixedFormationCleanup.Cleaned, runtime.completion.complete(expected))
        assertEquals(FormationRecoveryLoad.Missing, runtime().repository.load(before.sourceEntryId))
        assertEquals(FormationRecoveryList.Listed(emptyList()), runtime().repository.list())
        assertEquals(FormationRecoverySave.Conflict, runtime.repository.save(before, 0))
        assertEquals(FormationRecoverySave.Conflict, runtime().repository.save(before, null))
        assertEquals(FixedFormationCleanup.Cleaned, runtime().completion.complete(expected))
        assertEquals(sqlBefore, sqlSummary())
        assertEquals(1, directory.walkTopDown().count { it.isFile && it.name.endsWith(".discarded") })
        assertEquals(0, directory.walkTopDown().count { it.isFile && it.name.endsWith(".payload") })
    }

    @Test fun cleanupFailureDoesNotDuplicateTheFormalChapterAndRetriesOnlyLocalRemoval() = runBlocking {
        val before = snapshot()
        val expected = requireNotNull(before.toFixedSourceProjection("fixed-flow"))
        val runtime = runtime()
        assertEquals(FormationRecoverySave.Saved(0), runtime.repository.save(before, null))
        val fixed = fix(before) as? ContentFixResult.Fixed
        assertNotNull(fixed)
        val sqlBefore = sqlSummary()
        var removals = 0
        val failing = FixedFormationRecoveryCompletion(runtime.repository, fixedSources(), { _, _ -> removals++; false }, runtime::isCurrent)
        assertEquals(FixedFormationCleanup.FixedCleanupPending, failing.complete(expected))
        assertEquals(1, removals)
        assertTrue(runtime.repository.load(before.sourceEntryId) is FormationRecoveryLoad.Loaded)
        val ordinary = AndroidIncompleteLearningRepository(context, session)
        val formal = ordinary.loadOrdinaryLearning() as? IncompleteLearningLoadResult.Ready
        assertNotNull(formal)
        assertEquals(1, formal!!.entries.size)
        assertEquals(fixed!!.firstChapterId, formal.entries.single().learningId)
        assertEquals(ResumeLearningKind.ORDINARY, formal.entries.single().kind)
        val listing = FormationIncompleteLearningRepository(ordinary, runtime.repository, failing::status)
        assertEquals(formal, listing.loadOrdinaryLearning())
        assertEquals(sqlBefore, sqlSummary())

        assertEquals(FixedFormationCleanup.Cleaned, runtime().completion.complete(expected))
        assertEquals(FormationRecoveryLoad.Missing, runtime().repository.load(before.sourceEntryId))
        assertEquals(sqlBefore, sqlSummary())
    }

    @Test fun unfinishedOrOtherAccountSourcesCannotAuthorizeCleanupOfAnOwnersSavedDraft() = runBlocking {
        val before = snapshot()
        val expected = requireNotNull(before.toFixedSourceProjection("fixed-flow"))
        val originalRuntime = runtime()
        assertEquals(FormationRecoverySave.Saved(0), originalRuntime.repository.save(before, null))
        assertEquals(FixedFormationCleanup.Unconfirmed, originalRuntime.completion.complete(expected))
        assertEquals(FormationRecoveryLoad.Loaded(0, before), originalRuntime.repository.load(before.sourceEntryId))
        assertTrue(fix(before) is ContentFixResult.Fixed)
        session.clear(); session.update("isolated-other", "other-$id")
        assertEquals(FixedFormationCleanup.Unconfirmed, originalRuntime.completion.complete(expected))
        assertEquals(FixedFormationCleanup.Unconfirmed, runtime().completion.complete(expected))
        session.clear(); session.update("isolated-return", "owner-$id")
        assertEquals(FormationRecoveryLoad.Loaded(0, before), runtime().repository.load(before.sourceEntryId))
        assertEquals(FixedFormationCleanup.Unconfirmed, originalRuntime.completion.complete(expected))
        assertEquals(FixedFormationCleanup.Cleaned, runtime().completion.complete(expected))
    }

    private fun runtime() = AndroidFormationRecoveryRuntime(context, session, directory, draftKeys, fixedSources())
    private fun fixedSources() = AndroidFixedSourceRepository(context, session, fixedCodec)
    private suspend fun fix(snapshot: FormationRecoverySnapshot): ContentFixResult = AndroidContentFixRepository(context, session,
        knowledgeExamples = { error("No selected target means no AI example request") }, sourceCipher = fixedCodec).fix(
        ContentFixRequest("fixed-flow", "fixed-command", snapshot.englishChapters, snapshot.selectedCandidateIds,
            emptyList(), emptySet(), sourceType = "DIARY", sourceTitle = snapshot.sourceTitle,
            sourceProjection = snapshot.toFixedSourceProjection("fixed-flow")))

    private fun sqlSummary(): List<String> = listOf("formation_flows", "chapters", "fixed_sentences", "target_selections", "fix_commands", "fixed_source_payloads")
        .flatMap { table -> database.readableDatabase.rawQuery("select * from $table order by rowid", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(table + ":" + (0 until cursor.columnCount).joinToString("|") { column ->
                when (cursor.getType(column)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> "null"
                    android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).joinToString("") { "%02x".format(it) }
                    else -> cursor.getString(column)
                }
            }) }
        } }

    private fun snapshot(): FormationRecoverySnapshot {
        val units = listOf(SemanticUnit("u1", 0, "我喝茶。"), SemanticUnit("u2", 1, "这句完整来源不在章节中。"))
        return FormationRecoverySnapshot("accepted-source", 0, FormationRecoveryPhase.FINAL_FIX,
            ConfirmedSource(units.joinToString("\n") { it.textZh }, SourceType.DIARY, SourceContentShape.ARTICLE),
            "隔离完整来源", FormationSourceOrigin.Diary, units, listOf("u1"), listOf(ChapterDraft("c1", listOf("u1"))),
            listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", units[0].textZh, true,
                listOf(EnglishCandidate("e1", "I drink tea.")))))), mapOf("u1" to "e1"),
            emptyList(), emptyList(), emptySet(), "target-entry", "fixed-flow")
    }
}

private class PrivateCompletionKeys : FormationPayloadKeys {
    private val values = mutableMapOf<String, SecretKey>()
    override fun existing(accountSubjectHash: String, keyVersion: Int) = values[accountSubjectHash]
    override fun createIfAbsent(accountSubjectHash: String, keyVersion: Int) = values.getOrPut(accountSubjectHash) {
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
    override fun deleteAccount(accountSubjectHash: String) { values.remove(accountSubjectHash) }
}
