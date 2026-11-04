package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.testing.withExplicitSource
import com.reallanguage.app.testing.explicitSourceFixRepository
import com.reallanguage.app.feature.formation.domain.FixedSourceUnit

import android.content.ContentValues
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.learning.data.AndroidChapterArchiveRepository
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class KnownLearningTargetPersistenceTest {
    private lateinit var context: Context
    private lateinit var helper: FormationDatabase
    private val db get() = helper.writableDatabase
    private val runtime = SessionRuntime().apply { update("fixture", "owner") }
    private val item = KnownKnowledgeCandidate("VOCABULARY:v", KnownKnowledgeType.VOCABULARY, "work", "工作")
    private val target = LearningTarget("t", "s", "worked", "工作", LearningTargetType.WORD, sourceEvidence = SourceEvidence(1, 4, 10, "worked", "work", listOf("worked")), knownKnowledgeRef = item.ref)
    private val chapter = EnglishChapterDraft("c", listOf(EnglishSentenceDraft("s", "她工作了。", true, listOf(EnglishCandidate("e", "She worked.", listOf(item.ref))))))
    @Before fun setUp() { context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "known-target"); helper = FormationDatabase(context)
        insert("vocabulary_items", "account_id" to "owner", "item_id" to "v", "normalized_form" to "work", "semantic_sense_key" to "work|工作", "display" to "work", "meaning_zh" to "工作", "status" to "LEARNING", "verified_success_count" to 1)
    }
    @After fun tearDown() { helper.close(); context.deleteDatabase("real-language-learning.db") }
    @Test fun referenceReaderOnlyReturnsTheExactCurrentAccountRowsInRequestedOrder() = runBlocking {
        val before = rows("select * from vocabulary_items")
        assertEquals(listOf(item), AndroidKnownKnowledgeReferenceSource(context, runtime).read(listOf(item.ref)))
        assertEquals(before, rows("select * from vocabulary_items"))
        assertNull(AndroidKnownKnowledgeReferenceSource(context, runtime).read(listOf("VOCABULARY:missing")))
        assertNull(AndroidKnownKnowledgeReferenceSource(context, SessionRuntime().apply { update("other", "other") }).read(listOf(item.ref)))
    }
    @Test fun nativeJsonCarriesKnownInputsAndNullableTargetIdentityWithoutCoercion() {
        val codec = JsonLearningTargetWireCodec()
        val input = JSONObject(codec.requestWithKnowledge(listOf(chapter), mapOf("s" to "e"), "command", null, null, mapOf("s" to listOf(item))))
        assertEquals(item.ref, input.getJSONArray("chapters").getJSONObject(0).getJSONArray("sentences").getJSONObject(0).getJSONArray("reusedKnowledge").getJSONObject(0).getString("ref"))
        assertEquals(item.ref, codec.payload(payload(item.ref).toString()).targets.single().knownKnowledgeRef)
        assertNull(codec.payload(payload(JSONObject.NULL).toString()).targets.single().knownKnowledgeRef)
        for (invalid in listOf(3, true, "OTHER:v", "")) assertTrue(runCatching { codec.payload(payload(invalid).toString()) }.isFailure)
    }
    @Test fun inflectedKnownWordFixAndArchiveUpdateTheOriginalSenseOnceInsteadOfCreatingAnInflectedDuplicate() = runBlocking {
        val fixed = explicitSourceFixRepository(context, runtime).fix(request(target)) as ContentFixResult.Fixed
        assertEquals(listOf(listOf(item.ref)), rows("select known_knowledge_ref from target_selections"))
        val sentence = rows("select unit_id from fixed_sentences").single()[0]!!
        val targetId = rows("select target_id from target_selections").single()[0]!!
        insert("writing_batch_completions", "account_id" to "owner", "chapter_id" to fixed.firstChapterId, "command_id" to "writing", "qualification" to "INDEPENDENT")
        for ((type,id) in listOf("WHOLE_WRITING" to "", "MEANING_WRITING" to targetId)) insert("writing_result_drafts", "account_id" to "owner", "chapter_id" to fixed.firstChapterId, "sentence_id" to sentence, "ability_type" to type, "target_id" to id, "passed" to 1)
        for(table in listOf("full_speaking_result_drafts", "scene_response_result_drafts")) insert(table, "account_id" to "owner", "chapter_id" to fixed.firstChapterId, "sentence_id" to sentence, "passed" to 1)
        assertTrue(AndroidChapterArchiveRepository(context, runtime).archive(fixed.firstChapterId, "archive") is ChapterArchiveResult.Archived)
        assertEquals(listOf(listOf("v", "work", "VERIFIED", "2")), rows("select item_id,display,status,verified_success_count from vocabulary_items"))
        assertEquals(listOf(listOf("v", "1")), rows("select item_id,is_focus from vocabulary_occurrences"))
    }
    @Test fun mismatchedKnownMeaningAndUnreportedOrRemovedIdentityRejectFixBeforeAnyPaidGeneration() = runBlocking {
        var calls = 0
        val repo = explicitSourceFixRepository(context, runtime, knowledgeExamples = { calls++; KnowledgeExampleGenerationResult.Failed })
        for (bad in listOf(target.copy(explanationZh = "另一意思"), target.copy(knownKnowledgeRef = "PATTERN:v"), target.copy(knownKnowledgeRef = "VOCABULARY:other"))) {
            assertEquals(ContentFixResult.Failed, repo.fix(request(bad)))
        }
        assertEquals(0, calls); assertEquals(listOf(listOf("0")), rows("select count(*) from chapters"))
    }
    @Test fun selectingAnExistingKnownTargetDoesNotPayAgainForItsRepresentativeSceneAndExamples() = runBlocking {
        var calls = 0
        val repository = explicitSourceFixRepository(context, runtime, knowledgeExamples = { calls++; KnowledgeExampleGenerationResult.Failed })
        assertTrue(repository.fix(request(target)) is ContentFixResult.Fixed)
        assertEquals(0, calls)
        assertEquals(listOf(listOf("1")), rows("select count(*) from vocabulary_items"))
    }
    private fun payload(ref: Any) = JSONObject().put("learningScopes", JSONArray()).put("queryableExpressions", JSONArray()).put("targets", JSONArray().put(JSONObject()
        .put("targetId", "t").put("unitId", "s").put("display", "worked").put("explanationZh", "工作").put("type", "WORD").put("structureParts", JSONArray()).put("relatedVocabulary", JSONArray()).put("sourceEvidence", target.sourceEvidence!!.toJson()).put("reusedKnowledgeRef", ref)))
    private fun request(t: LearningTarget) = ContentFixRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), listOf(chapter), mapOf("s" to "e"), listOf(t), setOf(t.targetId)).withExplicitSource("她工作了。", listOf(FixedSourceUnit("s", 0, "她工作了。")))
    private fun insert(table: String, vararg fields: Pair<String, Any>) { db.insertOrThrow(table, null, ContentValues().apply { fields.forEach { (k,v) -> when(v) { is String -> put(k,v); is Int -> put(k,v) } } }) }
    private fun rows(query: String) = db.rawQuery(query, null).use { c -> buildList { while(c.moveToNext()) add(List(c.columnCount) { if(c.isNull(it)) null else c.getString(it) }) } }
}
