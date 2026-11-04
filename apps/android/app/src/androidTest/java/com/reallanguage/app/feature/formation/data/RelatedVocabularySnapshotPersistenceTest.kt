package com.reallanguage.app.feature.formation.data

import android.content.ContentValues
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.knowledge.data.AndroidPatternBookRepository
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import com.reallanguage.app.feature.knowledge.domain.PatternRelatedVocabulary
import com.reallanguage.app.feature.learning.data.AndroidChapterArchiveRepository
import com.reallanguage.app.feature.learning.domain.ChapterArchiveResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import com.reallanguage.app.testing.explicitSourceFixRepository
import com.reallanguage.app.testing.withExplicitSource
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Real SQLite and fixed-source encryption, always in a disposable UUID database; zero AI. */
class RelatedVocabularySnapshotPersistenceTest {
    @Test fun cancelledWordExplanationIsFixedWithPatternAndReopenedWithoutCreatingAnotherTarget() = fixture {
        val request = request()
        val fixed = fix(request)
        assertEquals(listOf("pattern"), generatedTargets)
        assertEquals(listOf(listOf("PATTERN", "tea")), rows("select type,related_vocabulary from target_selections"))
        assertEquals(0, count("vocabulary_items")); assertEquals(0, count("pattern_items"))
        val stored = targetSnapshot(fixed.firstChapterId)
        assertNotNull("The selected pattern must own the cancelled word's contextual explanation", stored)
        assertEnvelope(JSONObject(stored!!), request, persistentId(request, "sentence", "u"))
        helper.close(); helper = FormationDatabase(context)
        assertEquals(stored, targetSnapshot(fixed.firstChapterId))
        assertEquals(fixed, repository().fix(request))
        assertEquals(1, generatedTargets.size)
        assertEquals(1, count("target_selections")); assertEquals(0, count("target_vocabulary_details"))
    }

    @Test fun sameCommandCannotAcceptAChangedExplanationOrLocalReferenceAsAnExactReplay() = fixture {
        val original = request()
        fix(original)
        val changedSense = original.copy(targets = original.targets.map { target ->
            if (target.type != LearningTargetType.PATTERN) target else target.copy(
                relatedVocabularyItems = target.relatedVocabularyItems!!.map { it.copy(senseZh = "茶树") })
        })
        assertEquals(ContentFixResult.Failed, repository().fix(changedSense))
        val renamedRef = original.copy(targets = original.targets.map { target ->
            if (target.type != LearningTargetType.PATTERN) target else target.copy(
                relatedVocabularyItems = target.relatedVocabularyItems!!.map { it.copy(itemRef = "another-local-ref") },
                relatedVocabularyItemRefs = listOf("another-local-ref"))
        })
        assertEquals(ContentFixResult.Failed, repository().fix(renamedRef))
        assertEquals(1, generatedTargets.size)
        assertEquals(1, count("chapters")); assertEquals(1, count("fix_commands"))
    }

    @Test fun wrongSentenceExplanationFailsBeforeExamplesOrFixedWrites() = fixture {
        val original = request()
        val invalid = original.copy(targets = original.targets.map { target ->
            if (target.type != LearningTargetType.PATTERN) target else target.copy(
                relatedVocabularyItems = target.relatedVocabularyItems!!.map { it.copy(unitId = "unrelated-unit") })
        })
        assertEquals(ContentFixResult.Failed, repository().fix(invalid))
        assertTrue(generatedTargets.isEmpty())
        assertEquals(0, count("chapters")); assertEquals(0, count("target_selections"))
    }

    @Test fun archiveCopiesFirstRepresentativeExplanationButNeverRestoresCancelledWordAsKnowledge() = fixture {
        val original = request()
        val first = fix(original)
        // Seed the real new-column value independently, so this archive test is not blocked by fix RED.
        val expected = envelope(original).toString()
        setTargetSnapshot(first.firstChapterId, expected)
        readyToArchive(first.firstChapterId)
        assertTrue(archive(first.firstChapterId) is ChapterArchiveResult.Archived)
        assertEquals(expected, rows("select related_vocabulary_snapshot from pattern_items").single().single())
        assertEquals(0, count("vocabulary_items")); assertEquals(0, count("vocabulary_occurrences"))
        assertEquals(listOf(PatternRelatedVocabulary("tea", "茶（饮品）")), readPattern().relatedVocabulary)
        val representative = rows("select representative_scene,related_vocabulary_snapshot from pattern_items")
        val examples = rows("select text_en,text_zh from pattern_examples order by example_order")

        val secondRequest = request(flow = "second", word = "coffee", sense = "咖啡（饮品）")
        val second = fix(secondRequest)
        setTargetSnapshot(second.firstChapterId, envelope(secondRequest).toString())
        readyToArchive(second.firstChapterId)
        assertTrue(archive(second.firstChapterId) is ChapterArchiveResult.Archived)
        assertEquals(representative, rows("select representative_scene,related_vocabulary_snapshot from pattern_items"))
        assertEquals(examples, rows("select text_en,text_zh from pattern_examples order by example_order"))
        assertEquals(1, count("pattern_items")); assertEquals(2, count("pattern_occurrences"))
        assertEquals(listOf(PatternRelatedVocabulary("tea", "茶（饮品）")), readPattern().relatedVocabulary)
        assertEquals(0, count("vocabulary_items")); assertEquals(0, count("vocabulary_occurrences"))
    }

    @Test fun corruptFixedSnapshotCannotPartiallyArchiveOrBorrowAWordFromAnotherSentence() {
        for (corruption in listOf("json", "unit", "source", "ref", "sense")) fixture {
            val request = request()
            val fixed = fix(request)
            val value = envelope(request).apply {
                when (corruption) {
                    "unit" -> put("unitId", "another-sentence")
                    "source" -> put("sourceTextEn", "I used to drink coffee.")
                    "ref" -> put("refs", JSONArray(listOf("missing")))
                    "sense" -> getJSONArray("items").getJSONObject(0).put("senseZh", " ")
                }
            }
            val raw = if (corruption == "json") "{broken" else value.toString()
            setTargetSnapshot(fixed.firstChapterId, raw)
            readyToArchive(fixed.firstChapterId)
            val before = rows("select * from target_selections")
            assertEquals("Corrupt $corruption evidence must not become a successful formal result", ChapterArchiveResult.Failed, archive(fixed.firstChapterId))
            assertEquals(0, count("chapter_archives")); assertEquals(0, count("pattern_items"))
            assertEquals(0, count("sentence_capabilities")); assertEquals(0, count("learning_obligations"))
            assertEquals(before, rows("select * from target_selections"))
            assertEquals("NOT_STARTED", rows("select status from chapters").single().single())
        }
    }

    @Test fun patternBookReadsItsOwnedRepresentativeSnapshotAndRejectsDamagedNewDataWithoutMutation() = fixture {
        val request = request()
        val fixed = fix(request)
        readyToArchive(fixed.firstChapterId)
        assertTrue(archive(fixed.firstChapterId) is ChapterArchiveResult.Archived)
        val raw = envelope(request).toString()
        db.execSQL("update pattern_items set related_vocabulary_snapshot=?", arrayOf(raw))
        assertEquals(listOf(PatternRelatedVocabulary("tea", "茶（饮品）")), readPattern().relatedVocabulary)
        for (invalid in listOf("{broken", envelope(request).put("refs", JSONArray()).toString(),
            envelope(request).apply { getJSONArray("items").getJSONObject(0).put("senseZh", "") }.toString())) {
            db.execSQL("update pattern_items set related_vocabulary_snapshot=?", arrayOf(invalid))
            val before = rows("select * from pattern_items")
            assertEquals(PatternLoadResult.Failed, AndroidPatternBookRepository(context, runtime).loadAllPatterns())
            assertEquals(before, rows("select * from pattern_items"))
        }
    }

    @Test fun legacyNullStaysUnknownAndLaterOccurrencesDoNotInventItsRepresentativeGlossary() = fixture {
        val legacy = request().copy(targets = request().targets.map { it.copy(relatedVocabularyItems = null, relatedVocabularyItemRefs = null) })
        val first = fix(legacy)
        readyToArchive(first.firstChapterId)
        assertTrue(archive(first.firstChapterId) is ChapterArchiveResult.Archived)
        assertNull(readPattern().relatedVocabulary)
        val legacyState = rows("select representative_scene,related_vocabulary_snapshot from pattern_items")
        val fresh = request(flow = "later", word = "coffee", sense = "咖啡（饮品）")
        val next = fix(fresh)
        setTargetSnapshot(next.firstChapterId, envelope(fresh).toString())
        readyToArchive(next.firstChapterId)
        assertTrue(archive(next.firstChapterId) is ChapterArchiveResult.Archived)
        assertNull(readPattern().relatedVocabulary)
        assertEquals(legacyState, rows("select representative_scene,related_vocabulary_snapshot from pattern_items"))
    }

    @Test fun internallyValidRepresentativeSnapshotCannotReadAnotherAccountsOrMissingFixedSource() {
        for (unit in listOf("foreign-sentence", "missing-sentence")) fixture {
            val request = request()
            val fixed = fix(request)
            readyToArchive(fixed.firstChapterId)
            assertTrue(archive(fixed.firstChapterId) is ChapterArchiveResult.Archived)
            db.execSQL("insert into chapters values(?,'foreign-flow','foreign-chapter',0,'COMPLETED')", arrayOf("other-$account"))
            db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,'foreign-chapter','foreign-sentence',0,'我以前喝茶。','I used to drink tea.','FOCUS')", arrayOf("other-$account"))
            val raw = envelope(request).apply {
                put("unitId", unit)
                getJSONArray("items").getJSONObject(0).put("unitId", unit)
            }.toString()
            db.execSQL("update pattern_items set related_vocabulary_snapshot=?", arrayOf(raw))
            val before = rows("select * from pattern_items")
            assertEquals("Internally valid evidence still needs this account's exact fixed source", PatternLoadResult.Failed,
                AndroidPatternBookRepository(context, runtime).loadAllPatterns())
            assertEquals(before, rows("select * from pattern_items"))
        }
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        Fixture().use { fixture -> fixture.block() }
    }

    private class Fixture : AutoCloseable {
        val context: Context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "related-vocabulary")
        val account = "related-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-token", account) }
        var helper = FormationDatabase(context)
        val db get() = helper.writableDatabase
        val generatedTargets = mutableListOf<String>()

        init {
            // Independent behavior RED: missing persistence, not merely a missing schema column.
            for (table in listOf("target_selections", "pattern_items")) {
                if (rows("pragma table_info($table)").none { it[1] == "related_vocabulary_snapshot" })
                    db.execSQL("alter table $table add column related_vocabulary_snapshot text")
            }
        }

        fun repository() = explicitSourceFixRepository(context, runtime, knowledgeExamples = { request ->
            generatedTargets.add(request.targetId)
            KnowledgeExampleGenerationResult.Generated(KnowledgeTargetDetails(request.targetId, null,
                request.display, request.sourceScene.single().textEn,
                listOf(KnowledgeExampleDraft("I used to walk.", "我以前常步行。", "I used to walk."),
                    KnowledgeExampleDraft("She used to swim.", "她以前常游泳。", "She used to swim."),
                    KnowledgeExampleDraft("We used to read.", "我们以前常阅读。", "We used to read.")), true))
        })
        suspend fun fix(request: ContentFixRequest): ContentFixResult.Fixed {
            val result = repository().fix(request)
            assertTrue("Isolated fixed fixture must succeed before the assertion: $result", result is ContentFixResult.Fixed)
            return result as ContentFixResult.Fixed
        }
        suspend fun archive(chapter: String) = AndroidChapterArchiveRepository(context, runtime).archive(chapter, "archive-$chapter")
        suspend fun readPattern() = (AndroidPatternBookRepository(context, runtime).loadAllPatterns() as PatternLoadResult.Ready).patterns.single()
        fun readyToArchive(chapter: String) {
            val sentence = rows("select unit_id from fixed_sentences where chapter_id='$chapter'").single().single()!!
            db.execSQL("insert into writing_batch_completions values(?,?,?,'SUPPORTED')", arrayOf(account, chapter, "write-$chapter"))
            for (table in listOf("full_speaking_result_drafts", "scene_response_result_drafts"))
                db.execSQL("insert into $table values(?,?,?,1)", arrayOf(account, chapter, sentence))
        }
        fun setTargetSnapshot(chapter: String, value: String) = db.execSQL(
            "update target_selections set related_vocabulary_snapshot=? where account_id=? and chapter_id=?", arrayOf(value, account, chapter))
        fun targetSnapshot(chapter: String): String? = db.rawQuery(
            "select related_vocabulary_snapshot from target_selections where account_id=? and chapter_id=?", arrayOf(account, chapter)
        ).use { assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0) }
        fun rows(sql: String) = db.rawQuery(sql, null).use { c -> buildList {
            while (c.moveToNext()) add(List(c.columnCount) { if (c.isNull(it)) null else c.getString(it) })
        } }
        fun count(table: String) = rows("select count(*) from $table").single().single()!!.toInt()
        override fun close() { helper.close(); context.deleteDatabase("real-language-learning.db") }
    }

    private companion object {
        fun persistentId(request: ContentFixRequest, kind: String, id: String) = UUID.nameUUIDFromBytes("${request.formationFlowId}:$kind:$id".toByteArray()).toString()
        fun request(flow: String = "first", word: String = "tea", sense: String = "茶（饮品）"): ContentFixRequest {
            val english = "I used to drink $word."
            val wordEvidence = SourceEvidence(1, 16, 16 + word.length, word, word, listOf(word))
            val pattern = LearningTarget("pattern", "u", "used to + verb", "过去习惯", LearningTargetType.PATTERN,
                structureParts = listOf("used to", "verb"), relatedVocabulary = listOf(word),
                sourceEvidence = SourceEvidence(1, 2, 15, "used to drink", "used to drink", listOf("used to drink")),
                relatedVocabularyItems = listOf(RelatedVocabularyItem("local-word", "u", word, sense, LearningTargetType.WORD, wordEvidence)),
                relatedVocabularyItemRefs = listOf("local-word"))
            val cancelled = LearningTarget("word", "u", word, sense, LearningTargetType.WORD, sourceEvidence = wordEvidence)
            return ContentFixRequest(flow, "command-$flow", listOf(EnglishChapterDraft("chapter", listOf(
                EnglishSentenceDraft("u", "我以前常喝这种饮品。", true, listOf(EnglishCandidate("english", english)))))),
                mapOf("u" to "english"), listOf(pattern, cancelled), setOf("pattern"))
                .withExplicitSource("我以前常喝这种饮品。", listOf(FixedSourceUnit("u", 0, "我以前常喝这种饮品。")))
        }
        fun envelope(request: ContentFixRequest): JSONObject {
            val target = request.targets.first()
            val unit = persistentId(request, "sentence", "u")
            return JSONObject().put("version", 1).put("sourceTextEn", request.chapters.single().sentences.single().candidates.single().textEn)
                .put("unitId", unit).put("items", JSONArray(target.relatedVocabularyItems!!.map { item ->
                    JSONObject().put("itemRef", item.itemRef).put("unitId", unit).put("display", item.display)
                        .put("senseZh", item.senseZh).put("unitType", item.unitType.name).put("sourceEvidence", item.sourceEvidence.toJson())
                })).put("refs", JSONArray(target.relatedVocabularyItemRefs))
        }
        fun assertEnvelope(actual: JSONObject, request: ContentFixRequest, unit: String) {
            assertEquals(1, actual.getInt("version")); assertEquals(unit, actual.getString("unitId"))
            assertEquals("I used to drink tea.", actual.getString("sourceTextEn"))
            val item = actual.getJSONArray("items").getJSONObject(0)
            assertEquals(1, actual.getJSONArray("items").length()); assertEquals(unit, item.getString("unitId"))
            assertEquals("local-word", item.getString("itemRef")); assertEquals("local-word", actual.getJSONArray("refs").getString(0))
            assertNotEquals(persistentId(request, "target", "word"), item.getString("itemRef"))
            assertEquals("tea", item.getString("display")); assertEquals("茶（饮品）", item.getString("senseZh"))
            assertEquals("WORD", item.getString("unitType")); assertEquals(16, item.getJSONObject("sourceEvidence").getInt("startUtf16"))
            assertEquals("tea", item.getJSONObject("sourceEvidence").getString("surfaceForm"))
        }
    }
}
