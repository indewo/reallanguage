package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.testing.withExplicitSource
import com.reallanguage.app.testing.explicitSourceFixRepository
import com.reallanguage.app.feature.formation.domain.FixedSourceUnit

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.knowledge.data.CurrentVocabularyScopeSource
import com.reallanguage.app.feature.learning.data.AndroidChapterArchiveRepository
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class EnglishKnowledgePersistenceTest {
    private lateinit var context: Context
    private lateinit var helper: FormationDatabase
    private val runtime = SessionRuntime().apply { update("fixture-token", "owner") }
    private val db get() = helper.writableDatabase
    private var scopeCalls = 0
    private val scope = CurrentVocabularyScopeSource { scopeCalls++; setOf("DAILY_CORE") }
    @Before fun setUp() { context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "known-evidence"); helper = FormationDatabase(context); db }
    @After fun tearDown() { helper.close(); context.deleteDatabase("real-language-learning.db") }
    private fun source(r: SessionRuntime = runtime, s: CurrentVocabularyScopeSource = scope) = AndroidEnglishKnowledgeSnapshotSource(context, r, s)

    @Test fun realJsonPreservesEachCandidateReferenceAndRejectsMalformedNewReferences() {
        val codec = JsonEnglishGenerationWireCodec()
        val read = codec.chapters(payload().toString()).single()
        assertEquals(listOf("PATTERN:p"), read.sentences[0].candidates[0].reusedKnowledgeRefs)
        assertEquals(listOf("VOCABULARY:v"), read.sentences[1].candidates[1].reusedKnowledgeRefs)
        for (invalid in listOf(JSONObject.NULL, "PATTERN:p", JSONArray(listOf(3)), JSONArray(listOf("PATTERN:p", "PATTERN:p")), JSONArray(listOf("OTHER:p")))) {
            val body = payload().apply { getJSONArray("chapters").getJSONObject(0).getJSONArray("sentences").getJSONObject(0).getJSONArray("candidates").getJSONObject(0).put("reusedKnowledgeRefs", invalid) }
            assertTrue(runCatching { codec.chapters(body.toString()) }.isFailure)
        }
        val old = payload().apply { getJSONArray("chapters").getJSONObject(0).getJSONArray("sentences").getJSONObject(0).getJSONArray("candidates").getJSONObject(0).remove("reusedKnowledgeRefs") }
        assertEquals(emptyList<String>(), codec.chapters(old.toString())[0].sentences[0].candidates[0].reusedKnowledgeRefs)
    }

    @Test fun actualScopedSelectionIsFrozenPerAccountCommandAndReadBackWithoutNewScopeRequestOrPlaintextCopy() = runBlocking {
        pattern("p"); vocabulary("v", "DAILY_CORE"); vocabulary("outside", "OTHER"); pattern("other-p", "other")
        val before = snapshot(listOf("pattern_items", "vocabulary_items", "vocabulary_wordlists", "pattern_occurrences", "vocabulary_occurrences"))
        val first = source().freeze("base", "digest") as KnownKnowledgeSnapshotResult.Ready
        assertEquals(setOf("PATTERN:p", "VOCABULARY:v"), first.candidates.map { it.ref }.toSet())
        assertEquals(1, scopeCalls)
        assertEquals(first, source().freeze("base", "digest")); assertEquals(1, scopeCalls)
        assertEquals(before, snapshot(before.keys.toList()))
        val stored = snapshot(listOf("english_generation_knowledge_snapshots")).values.flatten().flatten().filterNotNull().joinToString()
        assertFalse(stored.contains("used to + verb")); assertFalse(stored.contains("过去习惯")); assertFalse(stored.contains("calm"))
        assertEquals(KnownKnowledgeSnapshotResult.Failed, source().freeze("base", "changed-digest"))
        val other = source(SessionRuntime().apply { update("other-token", "other") }).freeze("base", "digest") as KnownKnowledgeSnapshotResult.Ready
        assertEquals(listOf("PATTERN:other-p"), other.candidates.map { it.ref })
    }

    @Test fun removedOrChangedFrozenKnowledgeFailsWithoutNewScopeOrResurrectionButNewOperationCanProceed() = runBlocking {
        pattern("p")
        assertTrue(source().freeze("base", "digest") is KnownKnowledgeSnapshotResult.Ready)
        db.execSQL("update pattern_items set meaning_zh='changed' where account_id='owner'")
        assertEquals(KnownKnowledgeSnapshotResult.Failed, source().freeze("base", "digest")); assertEquals(1, scopeCalls)
        db.delete("pattern_items", "account_id=?", arrayOf("owner"))
        assertEquals(KnownKnowledgeSnapshotResult.Failed, source().freeze("base", "digest")); assertEquals(0, count("pattern_items"))
        assertEquals(KnownKnowledgeSnapshotResult.Ready(emptyList()), source().freeze("new-base", "digest")); assertEquals(2, scopeCalls)
    }

    @Test fun failedScopeCancelledScopeAndLateOwnerNeverPersistAnEmptySuccess() = runBlocking {
        pattern("p")
        assertEquals(KnownKnowledgeSnapshotResult.Failed, source(s = { null }).freeze("failed", "digest"))
        var cancelled = false
        try { source(s = { throw CancellationException() }).freeze("cancelled", "digest") } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(KnownKnowledgeSnapshotResult.Failed, source(s = { runtime.update("other-token", "other"); setOf("DAILY_CORE") }).freeze("late", "digest"))
        assertEquals(0, count("english_generation_knowledge_snapshots"))
    }

    @Test fun trueWireFixAndArchiveCountOnlyChosenReferencesWithoutChangingKnowledgeAbilityOrExamples() = runBlocking {
        pattern("p"); vocabulary("v", "DAILY_CORE")
        val knowledgeBefore = snapshot(listOf("pattern_items", "vocabulary_items", "pattern_examples", "vocabulary_examples"))
        val wire = JsonEnglishGenerationWireCodec().chapters(payload().toString())
        val fixed = explicitSourceFixRepository(context, runtime).fix(request(wire)) as ContentFixResult.Fixed
        assertEquals(listOf("[\"PATTERN:p\"]", "[\"VOCABULARY:v\"]"), rows("select reused_knowledge_refs from fixed_sentences where account_id='owner' order by sentence_order").map { it[0] })
        readyToArchive(fixed.firstChapterId)
        val archive = AndroidChapterArchiveRepository(context, runtime) { "2026-09-09" }
        assertTrue(archive.archive(fixed.firstChapterId, "archive") is ChapterArchiveResult.Archived)
        assertEquals(listOf(listOf("p", "1")), rows("select item_id,is_focus from pattern_occurrences where account_id='owner'"))
        assertEquals(listOf(listOf("v", "0")), rows("select item_id,is_focus from vocabulary_occurrences where account_id='owner'"))
        assertEquals(knowledgeBefore, snapshot(knowledgeBefore.keys.toList()))
        assertTrue(archive.archive(fixed.firstChapterId, "archive-retry") is ChapterArchiveResult.AlreadyArchived)
        assertEquals(1, count("vocabulary_occurrences")); assertEquals(1, count("pattern_occurrences"))
        assertEquals(knowledgeBefore, snapshot(knowledgeBefore.keys.toList()))
    }

    @Test fun crossAccountReferenceRejectsFixBeforeGenerationAndRemovedAfterFixIsNotRecreatedByArchive() = runBlocking {
        pattern("p", "other"); vocabulary("v", "DAILY_CORE")
        var calls = 0
        val repository = explicitSourceFixRepository(context, runtime, knowledgeExamples = { calls++; KnowledgeExampleGenerationResult.Failed })
        assertEquals(ContentFixResult.Failed, repository.fix(request(JsonEnglishGenerationWireCodec().chapters(payload().toString()))))
        assertEquals(0, calls); assertEquals(0, count("chapters"))
        pattern("p")
        val fixed = repository.fix(request(JsonEnglishGenerationWireCodec().chapters(payload().toString()))) as ContentFixResult.Fixed
        db.delete("vocabulary_items", "account_id=? and item_id=?", arrayOf("owner", "v"))
        readyToArchive(fixed.firstChapterId)
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive(fixed.firstChapterId, "removed") is ChapterArchiveResult.Archived)
        assertEquals(0, count("vocabulary_items")); assertEquals(0, count("vocabulary_occurrences"))
    }

    @Test fun explicitOwnerSnapshotCleanupPreservesOtherAccountAndAllLearningData() = runBlocking {
        pattern("p"); pattern("other-p", "other")
        source().freeze("base", "digest")
        source(SessionRuntime().apply { update("other-token", "other") }).freeze("base", "digest")
        val before = snapshot(listOf("pattern_items", "chapters", "fixed_sentences"))
        assertTrue(AndroidEnglishKnowledgeSnapshotCleaner(context).clear("owner"))
        assertEquals(listOf(listOf("other")), rows("select account_id from english_generation_knowledge_snapshots"))
        assertEquals(before, snapshot(before.keys.toList()))
    }
    @Test fun preLogoutSnapshotCannotBecomeActiveAgainForTheSameAccount() = runBlocking {
        pattern("p")
        val oldSnapshot = source()
        runtime.clear(); runtime.update("new-login", "owner")
        assertEquals(KnownKnowledgeSnapshotResult.Failed, oldSnapshot.freeze("old", "digest"))
        assertEquals(0, scopeCalls); assertEquals(0, count("english_generation_knowledge_snapshots"))
        assertTrue(source().freeze("new", "digest") is KnownKnowledgeSnapshotResult.Ready)
    }
    @Test fun preLogoutReferenceReaderCannotBecomeActiveAgainForTheSameAccount() = runBlocking {
        pattern("p")
        val oldReader = AndroidKnownKnowledgeReferenceSource(context, runtime)
        runtime.clear(); runtime.update("new-login", "owner")
        assertNull(oldReader.read(listOf("PATTERN:p")))
        assertEquals(listOf("PATTERN:p"), AndroidKnownKnowledgeReferenceSource(context, runtime).read(listOf("PATTERN:p"))!!.map { it.ref })
    }

    @Test fun versions16Through22UpgradeWithoutInventingReferencesOrDroppingEitherAccountsData() = runBlocking {
        for (version in 16..22) {
            helper.close(); context.deleteDatabase("real-language-learning.db"); helper = FormationDatabase(context); db
            pattern("p"); pattern("other-p", "other")
            val noRefs = JsonEnglishGenerationWireCodec().chapters(payload().toString()).map { ch -> ch.copy(sentences = ch.sentences.map { s -> s.copy(candidates = s.candidates.map { it.copy(reusedKnowledgeRefs = emptyList()) }) }) }
            assertTrue(explicitSourceFixRepository(context, runtime).fix(request(noRefs)) is ContentFixResult.Fixed)
            fun dropColumn(table: String, column: String) { if (rows("pragma table_info($table)").any { it[1] == column }) db.execSQL("alter table $table drop column $column") }
            db.execSQL("drop table if exists english_generation_knowledge_snapshots")
            dropColumn("fixed_sentences", "reused_knowledge_refs")
            dropColumn("target_selections", "known_knowledge_ref")
            if (version < 22) dropColumn("target_selections", "source_evidence")
            if (version < 21) db.execSQL("drop table if exists learning_self_assessments")
            if (version <= 16) dropColumn("chapter_abandon_commands", "chapter_id")
            if (version <= 17) dropColumn("review_chapter_results", "request_digest")
            if (version <= 19) dropColumn("review_queue_items", "occurrence_count")
            val before = snapshot(listOf("pattern_items", "chapters", "fixed_sentences"))
            db.version = version; helper.close(); helper = FormationDatabase(context)
            assertEquals(26, db.version)
            assertEquals(before["pattern_items"], rows("select * from pattern_items order by rowid"))
            assertEquals(before["chapters"], rows("select * from chapters order by rowid"))
            assertEquals(before["fixed_sentences"], rows("select account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role from fixed_sentences order by rowid"))
            assertTrue(rows("select reused_knowledge_refs from fixed_sentences").all { it[0] == null })
            assertEquals(0, count("english_generation_knowledge_snapshots"))
        }
    }

    @Test fun normalTokenRefreshKeepsOwnedFrozenKnowledgeAndReferenceReadersUsable() = runBlocking {
        pattern("p")
        val snapshot = source()
        val reader = AndroidKnownKnowledgeReferenceSource(context, runtime)
        runtime.update("refreshed-token", "owner")
        assertEquals(listOf("PATTERN:p"), reader.read(listOf("PATTERN:p"))?.map { it.ref })
        assertTrue(snapshot.freeze("after-refresh", "same-content") is KnownKnowledgeSnapshotResult.Ready)
    }

    private fun payload() = JSONObject().put("chapters", JSONArray().put(JSONObject().put("chapterId", "c").put("sentences", JSONArray()
        .put(JSONObject().put("sourceUnitId", "u1").put("textZh", "我以前常常步行。").put("isFocus", true).put("candidates", JSONArray().put(candidate("e1", "I used to walk.", "PATTERN:p"))))
        .put(JSONObject().put("sourceUnitId", "u2").put("textZh", "现在我很平静。").put("isFocus", false).put("candidates", JSONArray().put(candidate("unused", "I used to feel calm.", "PATTERN:p")).put(candidate("e2", "I feel calm now.", "VOCABULARY:v")))))))
    private fun candidate(id: String, english: String, ref: String) = JSONObject().put("candidateId", id).put("english", english).put("reusedKnowledgeRefs", JSONArray(listOf(ref)))
    private fun request(chapters: List<EnglishChapterDraft>) = ContentFixRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), chapters, mapOf("u1" to "e1", "u2" to "e2"), emptyList(), emptySet()).withExplicitSource("我以前常常步行。\n现在我很平静。", listOf(FixedSourceUnit("u1", 0, "我以前常常步行。"), FixedSourceUnit("u2", 1, "现在我很平静。")))
    private fun readyToArchive(chapterId: String) {
        val sentence = rows("select unit_id from fixed_sentences where chapter_id='$chapterId' and role='FOCUS'").single()[0]!!
        insert("writing_batch_completions", "account_id" to "owner", "chapter_id" to chapterId, "command_id" to "writing-$chapterId", "qualification" to "SUPPORTED")
        for(table in listOf("full_speaking_result_drafts", "scene_response_result_drafts")) insert(table, "account_id" to "owner", "chapter_id" to chapterId, "sentence_id" to sentence, "passed" to 1)
    }
    private fun pattern(id: String, account: String = "owner") = insert("pattern_items", "account_id" to account, "item_id" to id, "semantic_pattern_key" to "$id|sense", "structure_en" to "used to + verb", "meaning_zh" to "过去习惯", "writing_status" to "VERIFIED", "speaking_status" to "VERIFIED", "writing_streak" to 3, "speaking_streak" to 3, "active_validation" to 0)
    private fun vocabulary(id: String, scope: String) { insert("vocabulary_items", "account_id" to "owner", "item_id" to id, "normalized_form" to id, "semantic_sense_key" to "calm|平静", "display" to "calm", "meaning_zh" to "平静", "status" to "LEARNING", "verified_success_count" to 2); insert("vocabulary_wordlists", "account_id" to "owner", "item_id" to id, "wordlist" to scope) }
    private fun insert(table: String, vararg fields: Pair<String, Any>) { db.insertOrThrow(table, null, ContentValues().apply { fields.forEach { (k,v) -> when(v) { is String -> put(k,v); is Int -> put(k,v) } } }) }
    private fun count(table: String) = rows("select count(*) from $table").single().single()!!.toInt()
    private fun rows(query: String) = db.rawQuery(query, null).use { c -> buildList { while(c.moveToNext()) add(List(c.columnCount) { if(c.isNull(it)) null else c.getString(it) }) } }
    private fun snapshot(tables: List<String>) = tables.associateWith { rows("select * from $it order by rowid") }
}
