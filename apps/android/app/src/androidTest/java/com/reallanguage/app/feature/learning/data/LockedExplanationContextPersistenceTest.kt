package com.reallanguage.app.feature.learning.data

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.toJson
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** SQLite-only tests: no main database, UI substitution, HTTP, AI, or generated knowledge state. */
class LockedExplanationContextPersistenceTest {
    @Test fun readsSelectedPatternAndItsCancelledWordExplanationWithExactOriginalEvidenceAndZeroWrites() = fixture {
        target("pattern", "PATTERN", "used to + verb", "过去习惯", evidence(2, "used to drink"), glossary("tea", "茶（饮品）", 16))
        target("drink", "WORD", "drink", "喝", evidence(10, "drink"))
        val before = savedRows()
        val result = requireNotNull(reader().read("chapter", "focus"))
        assertEquals("chapter", result.chapterId); assertEquals("focus", result.focusSentenceId)
        assertEquals(english, result.textEn); assertEquals(setOf("pattern", "drink"), result.targets.map { it.targetId }.toSet())
        val pattern = result.targets.single { it.targetId == "pattern" }
        assertEquals(listOf("used to", "verb"), pattern.structureParts)
        val related = requireNotNull(pattern.relatedVocabulary).single()
        assertEquals("tea", related.display); assertEquals("茶（饮品）", related.senseZh)
        assertEquals(LearningTargetType.WORD, related.unitType)
        assertEquals(evidence(16, "tea"), related.sourceEvidence)
        assertTrue(result.targets.none { it.type == LearningTargetType.WORD && it.display == "tea" })
        assertEquals(before, savedRows())
        assertEquals(0, count("vocabulary_items")); assertEquals(0, count("learning_self_assessments"))
        helper.close(); helper = FormationDatabase(context)
        assertEquals(result, reader().read("chapter", "focus"))
    }

    @Test fun genuineLegacyMissingEvidenceAndGlossaryRemainUnknownWithoutGuessingOrRewriting() = fixture {
        target("legacy", "PATTERN", "used to + verb", "过去习惯", null)
        db.execSQL("update target_selections set structure_parts='',related_vocabulary='tea' where target_id='legacy'")
        val before = savedRows()
        val result = requireNotNull(reader().read("chapter", "focus")).targets.single()
        assertNull(result.sourceEvidence); assertNull(result.relatedVocabulary)
        assertEquals(emptyList<String>(), result.structureParts)
        assertEquals(before, savedRows())
    }

    @Test fun exactEmptySelectionIsNotConfusedWithMissingChapterOrNonFocusSentence() = fixture {
        val reader = reader()
        val context = requireNotNull(reader.read("chapter", "focus"))
        assertTrue(context.targets.isEmpty()); assertEquals(english, context.textEn)
        assertNull(reader.read("missing", "focus")); assertNull(reader.read("chapter", "context"))
        assertNull(reader.read("chapter", "other-focus"))
        assertNull(reader.read("other-chapter", "focus"))
    }

    @Test fun corruptSnapshotOrCrossSentenceBindingNeverDegradesIntoEmptyValidExplanation() {
        for (kind in listOf("json", "root-unit", "source", "missing-ref", "word-surface", "blank-meaning")) fixture {
            val value = glossary("tea", "茶（饮品）", 16).apply {
                when (kind) {
                    "root-unit" -> put("unitId", "other-focus")
                    "source" -> put("sourceTextEn", "I used to drink coffee.")
                    "missing-ref" -> put("refs", JSONArray(listOf("absent")))
                    "word-surface" -> getJSONArray("items").getJSONObject(0).put("display", "coffee")
                    "blank-meaning" -> getJSONArray("items").getJSONObject(0).put("senseZh", " ")
                }
            }
            target("pattern", "PATTERN", "used to + verb", "过去习惯", evidence(2, "used to drink"), value)
            if (kind == "json") db.execSQL("update target_selections set related_vocabulary_snapshot='{broken'")
            val before = savedRows()
            assertNull("Invalid $kind must fail closed, not lose its glossary", reader().read("chapter", "focus"))
            assertEquals(before, savedRows())
        }
    }

    @Test fun selectedWordAndRelatedExplanationCannotDisagreeAboutTheSameOccurrence() = fixture {
        target("pattern", "PATTERN", "used to + verb", "过去习惯", evidence(2, "used to drink"), glossary("tea", "茶（饮品）", 16))
        target("tea", "WORD", "tea", "茶树", evidence(16, "tea"))
        val before = savedRows()
        assertNull(reader().read("chapter", "focus"))
        assertEquals(before, savedRows())
    }

    @Test fun sameSurfaceAtDifferentPositionsRetainsBothMeaningsAndDoesNotMergeThem() = fixture {
        english = "The bank is near the river bank."
        db.execSQL("update fixed_sentences set text_en=? where account_id=? and unit_id='focus'", arrayOf(english, account))
        target("financial-bank", "WORD", "bank", "银行", evidence(english.indexOf("bank"), "bank"))
        target("river-bank", "WORD", "bank", "河岸", evidence(english.lastIndexOf("bank"), "bank"))
        val targets = requireNotNull(reader().read("chapter", "focus")).targets
        assertEquals(2, targets.size)
        assertEquals(setOf("银行", "河岸"), targets.map { it.explanationZh }.toSet())
        assertEquals(setOf(english.indexOf("bank"), english.lastIndexOf("bank")), targets.map { it.sourceEvidence!!.startUtf16 }.toSet())
    }

    @Test fun readerIsBoundToLoginEpochButOrdinaryTokenRefreshAndCompletedReadOnlyReviewRemainAvailable() = fixture {
        target("pattern", "PATTERN", "used to + verb", "过去习惯", evidence(2, "used to drink"), glossary("tea", "茶（饮品）", 16))
        val reader = reader()
        val original = requireNotNull(reader.read("chapter", "focus"))
        runtime.update("refreshed", account)
        assertEquals(original, reader.read("chapter", "focus"))
        db.execSQL("update chapters set status='COMPLETED' where account_id=?", arrayOf(account))
        assertEquals(original, reader.read("chapter", "focus"))
        runtime.clear(); runtime.update("new-login", account)
        assertNull(reader.read("chapter", "focus"))
        assertEquals(original, reader().read("chapter", "focus"))
        runtime.update("other-token", "other-$account")
        assertNull(reader.read("chapter", "focus"))
        assertEquals(emptyList<Any>(), requireNotNull(reader().read("chapter", "focus")).targets)
        assertEquals("Other account private source.", requireNotNull(reader().read("chapter", "focus")).textEn)
    }

    private fun fixture(block: Fixture.() -> Unit) { Fixture().use { it.block() } }
    private class Fixture : AutoCloseable {
        val context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "locked-explanation")
        val account = "explanation-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated", account) }
        var helper = FormationDatabase(context)
        val db get() = helper.writableDatabase
        var english = "I used to drink tea."
        init {
            for (owner in listOf(account, "other-$account")) {
                db.execSQL("insert into chapters values(?,'flow','chapter',0,'NOT_STARTED')", arrayOf(owner))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,'chapter','focus',0,'原句',?,'FOCUS')",
                    arrayOf(owner, if (owner == account) english else "Other account private source."))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,'chapter','context',1,'只读背景','Background only.','CONTEXT')", arrayOf(owner))
            }
        }
        fun reader() = AndroidLockedExplanationContextReader(context, runtime)
        fun target(id: String, type: String, display: String, meaning: String, source: SourceEvidence?, glossary: JSONObject? = null) {
            db.insertOrThrow("target_selections", null, ContentValues().apply {
                put("account_id", account); put("chapter_id", "chapter"); put("unit_id", "focus"); put("target_id", id)
                put("type", type); put("display", display); put("explanation_zh", meaning)
                put("structure_parts", if (type == "PATTERN") "used to\u001fverb" else "")
                put("source_evidence", source?.toJson()?.toString())
                put("related_vocabulary", if (glossary == null) "" else glossary.getJSONArray("items").getJSONObject(0).getString("display"))
                put("related_vocabulary_snapshot", glossary?.toString())
            })
        }
        fun evidence(start: Int, surface: String) = SourceEvidence(1, start, start + surface.length, surface, surface, listOf(surface))
        fun glossary(display: String, meaning: String, start: Int) = JSONObject().put("version", 1)
            .put("sourceTextEn", english).put("unitId", "focus")
            .put("items", JSONArray().put(JSONObject().put("itemRef", "local").put("unitId", "focus")
                .put("display", display).put("senseZh", meaning).put("unitType", "WORD").put("sourceEvidence", evidence(start, display).toJson())))
            .put("refs", JSONArray(listOf("local")))
        fun savedRows() = listOf("chapters", "fixed_sentences", "target_selections").associateWith { table ->
            db.rawQuery("select * from $table order by rowid", null).use { c -> buildList {
                while (c.moveToNext()) add(List(c.columnCount) { if (c.isNull(it)) null else c.getString(it) })
            } }
        }
        fun count(table: String) = db.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }
        override fun close() { helper.close(); context.deleteDatabase("real-language-learning.db") }
    }
}
