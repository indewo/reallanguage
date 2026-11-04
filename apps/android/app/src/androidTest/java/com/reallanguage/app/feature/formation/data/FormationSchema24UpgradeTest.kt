package com.reallanguage.app.feature.formation.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.FixedSourceLoad
import com.reallanguage.app.feature.formation.domain.FixedSourceProjection
import com.reallanguage.app.feature.home.data.AndroidIncompleteLearningRepository
import com.reallanguage.app.feature.home.domain.IncompleteLearningLoadResult
import com.reallanguage.app.feature.knowledge.data.AndroidPatternBookRepository
import com.reallanguage.app.feature.knowledge.data.AndroidVocabularyBookRepository
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import com.reallanguage.app.feature.knowledge.domain.VocabularyLoadResult
import com.reallanguage.app.feature.learning.data.AndroidLearningCheckpointRepository
import com.reallanguage.app.feature.learning.data.AndroidLearningProgressStore
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import com.reallanguage.app.testing.removeKnowledgeReuseSchemaForLegacyFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The historical 16–22 migration suite is reused; this only covers the 22→24 release delta. */
class FormationSchema24UpgradeTest {
    private lateinit var context: Context
    private lateinit var helper: FormationDatabase
    private val db get() = helper.writableDatabase

    @Before fun isolatedDatabase() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        context = IsolatedLearningDatabaseContext(app, "schema22-to24")
        assertTrue(context.getDatabasePath(DB_NAME).name.startsWith("schema22-to24-"))
        assertFalse(context.getDatabasePath(DB_NAME).canonicalPath == app.getDatabasePath(DB_NAME).canonicalPath)
        helper = FormationDatabase(context)
    }

    @After fun removeOnlyOwnedFixture() {
        if (::helper.isInitialized) helper.close()
        if (::context.isInitialized) context.deleteDatabase(DB_NAME)
    }

    @Test fun genuineSchema22WithoutSourceTablePreservesBothAccountsAndReopensExistingLearningWithoutBackfill() = runBlocking {
        listOf("migration-owner", "migration-other").forEach(::seedLegacyRecords)
        // Model the actual schema22, not a schema24 database with its version number
        // merely lowered. In particular no fixed original-source table/data existed.
        removeKnowledgeReuseSchemaForLegacyFixture(db)
        db.execSQL("drop table fixed_source_payloads")
        assertFalse("fixed_source_payloads" in tableNames())
        assertFalse("english_generation_knowledge_snapshots" in tableNames())
        assertFalse("reused_knowledge_refs" in columns("fixed_sentences"))
        assertFalse("known_knowledge_ref" in columns("target_selections"))
        assertTrue("source_evidence" in columns("target_selections"))
        val oldColumns = tableNames().associateWith(::columns)
        val before = snapshot(oldColumns)
        db.version = 22
        helper.close()

        helper = FormationDatabase(context)
        assertEquals(26, db.version)
        assertEquals(oldColumns.keys + setOf("fixed_source_payloads", "english_generation_knowledge_snapshots"), tableNames())
        assertEquals("Every old column, value and rowid must survive the real upgrade", before, snapshot(oldColumns))
        assertEquals(0, count("fixed_source_payloads"))
        assertEquals(0, count("english_generation_knowledge_snapshots"))
        assertEquals(0, db.rawQuery("select count(*) from fixed_sentences where reused_knowledge_refs is not null", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.rawQuery("select count(*) from target_selections where known_knowledge_ref is not null", null).use { it.moveToFirst(); it.getInt(0) })

        var codecCalls = 0
        val neverNeededCodec = object : FixedSourcePayloadCodec {
            override fun encrypt(identity: FixedSourcePayloadIdentity, projection: FixedSourceProjection): ByteArray? { codecCalls++; return null }
            override fun decrypt(identity: FixedSourcePayloadIdentity, envelope: ByteArray): FixedSourceProjection? { codecCalls++; return null }
        }
        for (account in listOf("migration-owner", "migration-other")) {
            val runtime = SessionRuntime().apply { update("isolated-token", account) }
            assertEquals("An old title/selected sentence cannot reconstruct the original source", FixedSourceLoad.Missing,
                AndroidFixedSourceRepository(context, runtime, neverNeededCodec).load("old-flow"))

            val fixed = AndroidFixedChapterRepository(context, runtime).load("completed")
            assertTrue("The old completed chapter must still load without a source projection", fixed is FixedChapterLoadResult.Loaded)
            assertEquals("$account 的旧重点句。", (fixed as FixedChapterLoadResult.Loaded).chapter.sentences.first().textZh)
            assertEquals(2, fixed.chapter.sentences.size)
            assertEquals(OrdinaryLearningCheckpoint("active", "LR-05", "active-sentence"),
                AndroidLearningCheckpointRepository(context, runtime).read("active"))
            assertEquals(mapOf("version" to "1", "input" to "$account 的未提交输入", "hintExpanded" to "true"),
                AndroidLearningProgressStore(context, runtime, "active", "active-sentence", "LR-05").read())
            val incomplete = AndroidIncompleteLearningRepository(context, runtime).loadOrdinaryLearning()
            assertTrue(incomplete is IncompleteLearningLoadResult.Ready)
            val entry = (incomplete as IncompleteLearningLoadResult.Ready).entries.single()
            assertEquals("active", entry.learningId)
            assertEquals("$account 的旧来源标题", entry.sourceLabelZh)
            assertEquals("LR-05", entry.savedStepId)
            assertEquals("active-sentence", entry.checkpointStepId)

            val vocabulary = AndroidVocabularyBookRepository(context, runtime) { setOf("CET_4") }.loadAllRecords()
            assertTrue(vocabulary is VocabularyLoadResult.Ready)
            val word = (vocabulary as VocabularyLoadResult.Ready).records.single()
            assertEquals("$account 的旧词义", word.meaningZh)
            assertEquals(KnowledgeStatus.TENTATIVE, word.status)
            assertEquals(2, word.effectiveLearnedCount)
            assertEquals((0..2).map { "$account 词汇例句 $it" }, word.examples.map { it.textZh })
            assertEquals(1, word.focusOccurrenceCount)
            assertEquals(1, word.contextOccurrenceCount)

            val patterns = AndroidPatternBookRepository(context, runtime).loadAllPatterns()
            assertTrue(patterns is PatternLoadResult.Ready)
            val pattern = (patterns as PatternLoadResult.Ready).patterns.single()
            assertEquals("$account 的旧句式说明", pattern.functionZh)
            assertEquals(KnowledgeStatus.MASTERED, pattern.writingStatus)
            assertEquals(KnowledgeStatus.LEARNING, pattern.speakingStatus)
            assertEquals(3, pattern.writingConsecutiveSuccessCount)
            assertEquals(1, pattern.speakingConsecutiveSuccessCount)
            assertEquals(listOf("drink", "名词"), pattern.structureParts)
            assertEquals((0..2).map { "$account 句式例句 $it" }, pattern.examples.map { it.textZh })
        }
        assertEquals("Missing legacy evidence must not cause key access or attempted backfill", 0, codecCalls)
        assertEquals(0, count("fixed_source_payloads"))
        assertEquals("Reading after migration must remain read-only", before, snapshot(oldColumns))
        helper.close()
        helper = FormationDatabase(context)
        assertEquals(26, db.version)
        assertEquals("A second open must not re-run migration or alter progress", before, snapshot(oldColumns))
        assertEquals(0, count("fixed_source_payloads"))
        assertEquals(listOf(listOf("text:ok")), rows("pragma integrity_check"))
    }

    private fun seedLegacyRecords(account: String) {
        insert("formation_flows", account, "flow_id" to "old-flow", "status" to "FIXED", "source_type" to "DIARY", "source_title" to "$account 的旧来源标题")
        listOf("completed", "active").forEachIndexed { order, chapter ->
            insert("chapters", account, "flow_id" to "old-flow", "chapter_id" to chapter, "chapter_order" to order,
                "status" to if (chapter == "completed") "COMPLETED" else "IN_PROGRESS")
            insert("fixed_sentences", account, "chapter_id" to chapter, "unit_id" to "$chapter-sentence", "sentence_order" to 0,
                "text_zh" to "$account 的旧重点句。", "text_en" to "I drink tea.", "role" to "FOCUS")
        }
        insert("fixed_sentences", account, "chapter_id" to "completed", "unit_id" to "context-sentence", "sentence_order" to 1,
            "text_zh" to "$account 的旧非重点句。", "text_en" to "It is warm.", "role" to "CONTEXT")
        insert("target_selections", account, "chapter_id" to "completed", "target_id" to "word", "unit_id" to "completed-sentence",
            "type" to "WORD", "display" to "tea", "explanation_zh" to "$account 的旧词义", "structure_parts" to "", "related_vocabulary" to "drink", "learning_scopes" to "CET_4",
            "source_evidence" to "{\"version\":1,\"startUtf16\":8,\"endUtf16\":11,\"surfaceForm\":\"tea\",\"normalizedForm\":\"tea\",\"allowedForms\":[\"tea\"]}")
        insert("target_selections", account, "chapter_id" to "completed", "target_id" to "pattern-target", "unit_id" to "completed-sentence",
            "type" to "PATTERN", "display" to "drink + noun", "explanation_zh" to "$account 的旧句式说明", "structure_parts" to "drink\u001f名词", "related_vocabulary" to "tea", "learning_scopes" to "CET_4")
        insert("fix_commands", account, "command_id" to "old-command", "first_chapter_id" to "completed")
        insert("chapter_archives", account, "chapter_id" to "completed", "command_id" to "old-archive", "summary" to "$account 已保存总结", "completed_local_date" to "2026-09-06")
        insert("sentence_capabilities", account, "sentence_id" to "completed-sentence", "capability_type" to "FULL_WRITING", "status" to "VERIFIED")
        insert("learning_steps", account, "chapter_id" to "active", "sentence_id" to "active-sentence", "step" to "LR-04", "status" to "COMPLETED", "command_id" to "old-step")
        insert("learning_checkpoints", account, "chapter_id" to "active", "module_id" to "LR-05", "step_id" to "active-sentence")
        insert("learning_module_progress", account, "chapter_id" to "active", "sentence_id" to "active-sentence", "module_id" to "LR-05",
            "payload" to "{\"version\":\"1\",\"input\":\"$account 的未提交输入\",\"hintExpanded\":\"true\"}")
        insert("learning_explanation_cache", account, "chapter_id" to "active", "sentence_id" to "active-sentence", "cache_key" to "old-cache", "response_body" to "{\"explanationZh\":\"$account 已保存讲解\"}")
        insert("writing_result_drafts", account, "chapter_id" to "active", "sentence_id" to "active-sentence", "ability_type" to "WHOLE_WRITING", "target_id" to "", "passed" to 0)
        insert("learning_self_assessments", account, "chapter_id" to "active", "sentence_id" to "active-sentence", "target_id" to "old-target", "capability_type" to "MEANING_WRITING")
        insert("vocabulary_items", account, "item_id" to "v", "normalized_form" to "tea", "semantic_sense_key" to "tea|$account 的旧词义", "display" to "tea",
            "meaning_zh" to "$account 的旧词义", "status" to "SELF_ASSESSED", "verified_success_count" to 2, "phonetic" to "/tiː/", "pronunciation_audio_id" to "tea", "representative_scene" to "$account 旧词汇场景")
        insert("vocabulary_wordlists", account, "item_id" to "v", "wordlist" to "CET_4")
        insert("pattern_items", account, "item_id" to "p", "semantic_pattern_key" to "drink + noun|$account 的旧句式说明", "structure_en" to "drink + noun",
            "meaning_zh" to "$account 的旧句式说明", "writing_status" to "VERIFIED", "speaking_status" to "LEARNING", "writing_streak" to 3, "speaking_streak" to 1,
            "active_validation" to 1, "representative_scene" to "$account 旧句式场景", "natural_revalidation" to 1)
        (0..2).forEach { order ->
            insert("vocabulary_examples", account, "item_id" to "v", "example_order" to order, "text_en" to "Vocabulary example $order.", "text_zh" to "$account 词汇例句 $order", "audio_id" to "Vocabulary example $order.")
            insert("pattern_examples", account, "item_id" to "p", "example_order" to order, "text_en" to "Pattern example $order.", "text_zh" to "$account 句式例句 $order", "pronunciation_text" to "Pattern example $order.")
        }
        listOf("completed-sentence", "context-sentence").forEachIndexed { index, sentence ->
            insert("vocabulary_occurrences", account, "item_id" to "v", "chapter_id" to "completed", "sentence_id" to sentence, "is_focus" to if (index == 0) 1 else 0)
        }
        insert("pattern_occurrences", account, "item_id" to "p", "chapter_id" to "completed", "sentence_id" to "completed-sentence", "is_focus" to 1)
        insert("learning_obligations", account, "obligation_id" to "old-obligation", "chapter_id" to "completed", "sentence_id" to "completed-sentence", "capability_type" to "MEANING_WRITING", "target_id" to "word")
        insert("correction_flags", account, "obligation_id" to "old-obligation")
        insert("review_queue_items", account, "review_cycle_id" to "old-cycle", "queue_item_id" to "old-queue", "obligation_id" to "old-obligation", "original_chapter_id" to "completed",
            "sentence_id" to "completed-sentence", "priority_rank" to 1, "entered_at" to 1_700_000_000L, "occurrence_count" to 2)
    }

    private fun insert(table: String, account: String, vararg fields: Pair<String, Any>) {
        db.insertOrThrow(table, null, ContentValues().apply {
            put("account_id", account)
            fields.forEach { (key, value) -> when (value) {
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                else -> error("Unsupported isolated fixture value")
            } }
        })
    }

    private fun tableNames(): Set<String> = db.rawQuery("select name from sqlite_master where type='table' and name not like 'sqlite_%' and name!='android_metadata' order by name", null)
        .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    private fun columns(table: String): List<String> = db.rawQuery("pragma table_info($table)", null)
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(1)) } }
    private fun snapshot(columns: Map<String, List<String>>) = columns.mapValues { (table, names) ->
        rows("select rowid,${names.joinToString(",")} from $table order by rowid")
    }
    private fun rows(sql: String): List<List<String>> = db.rawQuery(sql, null).use { cursor -> buildList {
        while (cursor.moveToNext()) add(List(cursor.columnCount) { index -> when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> "null"
            Cursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(index)}"
            Cursor.FIELD_TYPE_FLOAT -> "float:${cursor.getDouble(index)}"
            Cursor.FIELD_TYPE_BLOB -> "blob:${cursor.getBlob(index).joinToString(",")}"
            else -> "text:${cursor.getString(index)}"
        } })
    } }
    private fun count(table: String) = db.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }

    private companion object { const val DB_NAME = "real-language-learning.db" }
}
