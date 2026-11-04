package com.reallanguage.app.feature.formation.data

import com.reallanguage.app.testing.withExplicitSource
import com.reallanguage.app.testing.explicitSourceFixRepository
import com.reallanguage.app.feature.formation.domain.FixedSourceUnit

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.learning.data.AndroidLockedWritingTargetReader
import com.reallanguage.app.feature.learning.domain.WritingTargetSpan
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Native JSON and schema migration on a private UUID database, with no provider calls. */
class TargetSourceEvidencePersistenceTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("isolated-token", "evidence-owner") }
    private val english = "I used to walk home."
    private val evidence = SourceEvidence(1, 2, 14, "used to walk", "used to + verb", listOf("used to walk"))
    private val target = LearningTarget("t1", "s1", "used to + verb", "表示过去的习惯", LearningTargetType.PATTERN, sourceEvidence = evidence)

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val isolatedFile = File(base.cacheDir, "target-evidence-${UUID.randomUUID()}.db")
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = isolatedFile
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(isolatedFile.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(isolatedFile.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
            override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(isolatedFile)
        }
        database = FormationDatabase(context)
        database.writableDatabase
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
    }

    @Test fun realJsonHttpFixAndRecreatedReaderKeepTheExactSourceSpanAndAllowedForm() = runBlocking {
        var calls = 0
        val request = request()
        val http = HttpLearningTargetRepository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                calls++
                assertEquals(english, JSONObject(request.body).getJSONArray("chapters").getJSONObject(0).getJSONArray("sentences").getJSONObject(0).getString("textEn"))
                return HttpResponse(200, payload(evidenceJson()).toString())
            }
        }, runtime, request.chapters, request.selectedCandidateIds, "extraction-command")
        val extracted = http.extract() as LearningTargetExtractionResult.Extracted
        assertEquals(evidence, extracted.targets.single().sourceEvidence)
        assertEquals(1, calls)
        val fixed = explicitSourceFixRepository(context, runtime).fix(request.copy(targets = extracted.targets)) as ContentFixResult.Fixed
        val restored = SessionRuntime().apply { update("new-isolated-token", requireNotNull(runtime.accountId)) }
        val locked = requireNotNull(AndroidLockedWritingTargetReader(context, restored).read(fixed.firstChapterId)).single()
        assertEquals(listOf(evidence.surfaceForm), locked.expectedForms)
        assertEquals(WritingTargetSpan(2, 14, evidence.surfaceForm), locked.sourceSpan)
        assertEquals(evidenceJson().toString(), value("select source_evidence from target_selections"))
        assertEquals(1, calls)
        assertEquals(ContentFixResult.Fixed(fixed.firstChapterId), explicitSourceFixRepository(context, restored).fix(request.copy(targets = extracted.targets)))
        assertEquals(1, count("target_selections"))
        assertNull(AndroidLockedWritingTargetReader(context, SessionRuntime().apply { update("other-token", "other-owner") }).read(fixed.firstChapterId))
    }

    @Test fun malformedNewEvidenceIsRejectedByTheRealJsonCodecRatherThanCoercedOrDropped() {
        val invalid = buildList<Any> {
            for (key in evidenceJson().keys().asSequence().toList()) add(evidenceJson().apply { remove(key) })
            listOf("version", "startUtf16", "endUtf16").forEach { key ->
                listOf<Any>("1", 1.5, true, JSONObject.NULL).forEach { add(evidenceJson().put(key, it)) }
            }
            add(evidenceJson().put("allowedForms", JSONArray(listOf("used to walk", "used to run"))))
            add(evidenceJson().put("allowedForms", JSONArray(listOf(7))))
            add(evidenceJson().put("normalizedForm", " "))
            add(evidenceJson().put("surfaceForm", true))
            add("not-an-object")
        }
        invalid.forEach { candidate -> assertTrue("Malformed evidence must not be accepted", runCatching {
            JsonLearningTargetWireCodec().payload(payload(candidate).toString())
        }.isFailure) }
    }

    @Test fun missingOrNullEvidenceRemainsLegacyAndIsNotInventedByTheCodec() {
        for (body in listOf(payload(JSONObject.NULL), payload(JSONObject.NULL).apply { getJSONArray("targets").getJSONObject(0).remove("sourceEvidence") })) {
            assertNull(JsonLearningTargetWireCodec().payload(body.toString()).targets.single().sourceEvidence)
        }
    }

    @Test fun invalidExplicitEvidenceRejectsFixBeforeAnyGenerationOrPartialPersistence() = runBlocking {
        var calls = 0
        val repository = explicitSourceFixRepository(context, runtime, knowledgeExamples = {
            calls++
            KnowledgeExampleGenerationResult.Failed
        })
        val invalid = target.copy(sourceEvidence = evidence.copy(startUtf16 = 3))
        assertEquals(ContentFixResult.Failed, repository.fix(request(target = invalid)))
        assertEquals(0, calls)
        assertEquals(0, count("chapters"))
        assertEquals(0, count("target_selections"))
        assertEquals(0, count("fix_commands"))
    }

    @Test fun automaticWordHttpResponseCannotUseAnotherRealWordAsItsEvidence() = runBlocking {
        val text = "I drink tea and coffee."
        val source = SourceEvidence(1, 16, 22, "coffee", "tea", listOf("coffee"))
        val request = request(text)
        val body = payload(source.toJson()).apply {
            getJSONArray("targets").getJSONObject(0).put("type", "WORD").put("display", "tea")
        }
        var calls = 0
        val http = HttpLearningTargetRepository(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                calls++
                return HttpResponse(200, body.toString())
            }
        }, runtime, request.chapters, request.selectedCandidateIds, "misbound-word")
        assertEquals(LearningTargetExtractionResult.Failed, http.extract())
        assertEquals(1, calls)
        assertEquals(0, count("target_selections"))
    }

    @Test fun misboundWordFixIsRejectedBeforeAnyGenerationOrDatabaseWrite() = runBlocking {
        var calls = 0
        val repository = explicitSourceFixRepository(context, runtime, knowledgeExamples = {
            calls++
            KnowledgeExampleGenerationResult.Failed
        })
        val wrong = LearningTarget("t1", "s1", "tea", "茶", LearningTargetType.WORD,
            sourceEvidence = SourceEvidence(1, 16, 22, "coffee", "tea", listOf("coffee")))
        assertEquals(ContentFixResult.Failed, repository.fix(request("I drink tea and coffee.", wrong)))
        assertEquals(0, calls)
        assertEquals(0, count("chapters"))
        assertEquals(0, count("target_selections"))
        assertEquals(0, count("fix_commands"))
    }

    @Test fun damagedStoredWordBindingCannotScoreAnotherWordOrRewriteHistory() = runBlocking {
        val text = "I drink tea and coffee."
        val tea = LearningTarget("t1", "s1", "tea", "茶", LearningTargetType.WORD,
            sourceEvidence = SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea")))
        val fixed = explicitSourceFixRepository(context, runtime).fix(request(text, tea)) as ContentFixResult.Fixed
        val corrupted = SourceEvidence(1, 16, 22, "coffee", "tea", listOf("coffee")).toJson().toString()
        database.writableDatabase.execSQL("update target_selections set source_evidence=?", arrayOf(corrupted))
        assertNull(AndroidLockedWritingTargetReader(context, runtime).read(fixed.firstChapterId))
        assertEquals(corrupted, value("select source_evidence from target_selections"))
        assertEquals(0, count("writing_result_drafts"))
        assertEquals(0, count("writing_batch_completions"))
    }

    @Test fun inflectedWordPreservesNormalizedMetadataWithoutBroadeningAllowedForms() = runBlocking {
        val source = SourceEvidence(1, 4, 10, "worked", "work", listOf("worked"))
        val word = LearningTarget("t1", "s1", " WORKED ", "工作", LearningTargetType.WORD, sourceEvidence = source)
        val fixed = explicitSourceFixRepository(context, runtime).fix(request("She worked today.", word)) as ContentFixResult.Fixed
        val locked = requireNotNull(AndroidLockedWritingTargetReader(context, runtime).read(fixed.firstChapterId)).single()
        assertEquals(listOf("worked"), locked.expectedForms)
        assertEquals(WritingTargetSpan(4, 10, "worked"), locked.sourceSpan)
        assertEquals(source.toJson().toString(), value("select source_evidence from target_selections"))
        assertEquals(" WORKED ", value("select display from target_selections"))
    }

    @Test fun oldUniqueLiteralTargetGetsRuntimeEvidenceWithoutChangingHistoricalStorage() = runBlocking {
        val old = LearningTarget("t1", "s1", "CALM", "平静", LearningTargetType.WORD)
        val fixed = explicitSourceFixRepository(context, runtime).fix(request("I felt calm.", old)) as ContentFixResult.Fixed
        val locked = requireNotNull(AndroidLockedWritingTargetReader(context, runtime).read(fixed.firstChapterId)).single()
        assertEquals(listOf("calm"), locked.expectedForms)
        assertEquals(WritingTargetSpan(7, 11, "calm"), locked.sourceSpan)
        assertNull(value("select source_evidence from target_selections"))
    }

    @Test fun legacyLiteralWordMetadataIsNotReclassifiedByNewExplicitEvidenceRules() = runBlocking {
        val old = LearningTarget("t1", "s1", "take care", "照料", LearningTargetType.WORD)
        val fixed = explicitSourceFixRepository(context, runtime).fix(request("I take care of plants.", old)) as ContentFixResult.Fixed
        val locked = requireNotNull(AndroidLockedWritingTargetReader(context, runtime).read(fixed.firstChapterId)).single()
        assertEquals(listOf("take care"), locked.expectedForms)
        assertNull(value("select source_evidence from target_selections"))
        assertEquals("WORD", value("select type from target_selections"))
    }

    @Test fun ambiguousOrNonliteralOldTargetsFailLoadingWithoutWritingFormalFailure() = runBlocking {
        for ((text, display) in listOf(english to "used to + verb", "I worked." to "work", "calm calm" to "calm")) {
            val old = target.copy(display = display, sourceEvidence = null)
            val fixed = explicitSourceFixRepository(context, runtime).fix(request(text, old)) as ContentFixResult.Fixed
            assertNull(AndroidLockedWritingTargetReader(context, runtime).read(fixed.firstChapterId))
        }
        assertEquals(0, count("writing_result_drafts"))
        assertEquals(0, count("writing_batch_completions"))
        assertEquals(0, count("sentence_capabilities"))
    }

    @Test fun version21UpgradePreservesBothAccountsAndOnlyAddsNullableEvidence() = runBlocking {
        val old = LearningTarget("t1", "s1", "calm", "平静", LearningTargetType.WORD)
        val fixed = explicitSourceFixRepository(context, runtime).fix(request("I felt calm.", old)) as ContentFixResult.Fixed
        val other = SessionRuntime().apply { update("other-token", "other-owner") }
        assertTrue(explicitSourceFixRepository(context, other).fix(request("I felt calm.", old)) is ContentFixResult.Fixed)
        val db = database.writableDatabase
        // Remove every post-v21 addition instead of only changing the version header.
        // The former fixture accidentally retained the v25 snapshot in its SELECT *.
        db.execSQL("alter table target_selections drop column source_evidence")
        db.execSQL("alter table target_selections drop column related_vocabulary_snapshot")
        db.execSQL("alter table pattern_items drop column related_vocabulary_snapshot")
        com.reallanguage.app.testing.removeKnowledgeReuseSchemaForLegacyFixture(db)
        db.execSQL("drop table fixed_source_payloads")
        val oldTargetColumns = listOf("account_id", "chapter_id", "target_id", "unit_id", "type", "display",
            "explanation_zh", "structure_parts", "related_vocabulary", "learning_scopes")
        assertEquals(oldTargetColumns, snapshot("pragma table_info(target_selections)").map { requireNotNull(it[1]) })
        for ((index, account) in listOf(requireNotNull(runtime.accountId), requireNotNull(other.accountId)).withIndex()) {
            db.insertOrThrow("pattern_items", null, ContentValues().apply {
                put("account_id", account); put("item_id", "old-pattern-$index")
                put("semantic_pattern_key", "used to + verb|过去习惯")
                put("structure_en", "used to + verb"); put("meaning_zh", "过去习惯")
                put("writing_status", "LEARNING"); put("speaking_status", "PENDING")
                put("writing_streak", index); put("speaking_streak", 0); put("active_validation", 1)
                put("representative_scene", "旧账号$index 的原代表场景"); put("natural_revalidation", 1)
            })
        }
        val tables = listOf("target_selections", "chapters", "fixed_sentences", "pattern_items",
            "formation_flows", "fix_commands", "target_vocabulary_details")
        val oldColumns = tables.associateWith { table -> snapshot("pragma table_info($table)").map { requireNotNull(it[1]) } }
        val before = tables.associateWith { table ->
            snapshot("select rowid,${oldColumns.getValue(table).joinToString()} from $table order by rowid")
        }
        db.version = 21
        database.close()
        database = FormationDatabase(context)
        assertEquals(26, database.readableDatabase.version)
        for (table in tables) assertEquals("Both accounts' old $table values and rowids must survive",
            before.getValue(table), snapshot("select rowid,${oldColumns.getValue(table).joinToString()} from $table order by rowid"))
        assertEquals(listOf(listOf(null, null, null), listOf(null, null, null)),
            snapshot("select source_evidence,related_vocabulary_snapshot,known_knowledge_ref from target_selections order by account_id"))
        assertEquals(listOf(listOf(null), listOf(null)), snapshot("select related_vocabulary_snapshot from pattern_items order by account_id"))
        assertEquals(0, count("fixed_source_payloads")) // An absent old source is not guessed or reconstructed.
        assertEquals(listOf("calm"), requireNotNull(AndroidLockedWritingTargetReader(context, runtime).read(fixed.firstChapterId)).single().expectedForms)
        assertEquals(listOf(listOf("ok")), snapshot("pragma integrity_check"))
    }

    @Test fun damagedStoredEvidenceIsNotReplacedByLegacyInference() = runBlocking {
        val fixed = explicitSourceFixRepository(context, runtime).fix(request()) as ContentFixResult.Fixed
        database.writableDatabase.execSQL("update target_selections set source_evidence=?", arrayOf(evidenceJson().put("endUtf16", 200).toString()))
        assertNull(AndroidLockedWritingTargetReader(context, runtime).read(fixed.firstChapterId))
        assertEquals(0, count("writing_result_drafts"))
    }

    private fun request(text: String = english, target: LearningTarget = this.target) = ContentFixRequest(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("s1", "我以前步行回家。", true, listOf(EnglishCandidate("e1", text)))))),
        mapOf("s1" to "e1"), listOf(target), setOf("t1"),
    ).withExplicitSource("我以前步行回家。", listOf(FixedSourceUnit("s1", 0, "我以前步行回家。")))
    private fun evidenceJson() = JSONObject().put("version", 1).put("startUtf16", 2).put("endUtf16", 14)
        .put("surfaceForm", "used to walk").put("normalizedForm", "used to + verb").put("allowedForms", JSONArray(listOf("used to walk")))
    private fun payload(source: Any) = JSONObject().put("learningScopes", JSONArray()).put("queryableExpressions", JSONArray())
        .put("targets", JSONArray().put(JSONObject().put("targetId", "t1").put("unitId", "s1").put("display", target.display)
            .put("explanationZh", target.explanationZh).put("type", "PATTERN").put("structureParts", JSONArray(listOf("used to", "verb")))
            .put("relatedVocabulary", JSONArray()).put("sourceEvidence", source)))
    private fun count(table: String) = database.readableDatabase.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }
    private fun value(query: String): String? = database.readableDatabase.rawQuery(query, null).use { it.moveToFirst(); if (it.isNull(0)) null else it.getString(0) }
    private fun snapshot(query: String) = database.readableDatabase.rawQuery(query, null).use { c ->
        buildList { while (c.moveToNext()) add(List(c.columnCount) { if (c.isNull(it)) null else c.getString(it) }) }
    }
}
