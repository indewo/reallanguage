package com.reallanguage.app.feature.formation.data

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.knowledge.data.AndroidPatternBookRepository
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RelatedVocabularySchema25UpgradeTest {
    @Test fun genuineVersion24UpgradePreservesBothAccountsAndNeverBackfillsMissingContextualMeanings() = runBlocking {
        val context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "related-upgrade")
        var helper = FormationDatabase(context)
        val owner = "owner-${UUID.randomUUID()}"
        val other = "other-${UUID.randomUUID()}"
        val tables = listOf("chapters", "fixed_sentences", "target_selections", "learning_checkpoints",
            "pattern_items", "pattern_examples", "pattern_occurrences", "vocabulary_items")
        try {
            var db = helper.writableDatabase
            // Remove the additive fields, not just the version header: this is an actual v24 schema.
            for (table in listOf("target_selections", "pattern_items")) {
                if (columns(helper, table).contains("related_vocabulary_snapshot"))
                    db.execSQL("alter table $table drop column related_vocabulary_snapshot")
            }
            for (account in listOf(owner, other)) {
                db.execSQL("insert into chapters values(?,'old-flow','old-chapter',0,'COMPLETED')", arrayOf(account))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values(?,'old-chapter','old-sentence',0,'我以前喝茶。','I used to drink tea.','FOCUS')", arrayOf(account))
                db.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary) values(?,'old-chapter','old-target','old-sentence','PATTERN','used to + verb','过去习惯',?,'tea')", arrayOf(account, "used to\u001fverb"))
                db.execSQL("insert into learning_checkpoints values(?,'old-chapter','EXPLANATION','old-sentence')", arrayOf(account))
                db.insertOrThrow("pattern_items", null, ContentValues().apply {
                    put("account_id", account); put("item_id", "old-pattern"); put("semantic_pattern_key", "used to + verb|过去习惯")
                    put("structure_en", "used to + verb"); put("meaning_zh", "过去习惯")
                    put("writing_status", "VERIFIED"); put("speaking_status", "PENDING")
                    put("writing_streak", 2); put("speaking_streak", 0); put("active_validation", 1)
                    put("representative_scene", "曾经的生活习惯"); put("natural_revalidation", 1)
                })
                db.execSQL("insert into pattern_occurrences values(?,'old-pattern','old-chapter','old-sentence',1)", arrayOf(account))
                repeat(3) { order -> db.execSQL("insert into pattern_examples values(?,'old-pattern',?,?,?,?)",
                    arrayOf<Any>(account, order, "I used to walk $order miles.", "我以前走${order}英里。", "I used to walk $order miles.")) }
                db.insertOrThrow("vocabulary_items", null, ContentValues().apply {
                    put("account_id", account); put("item_id", "old-word"); put("normalized_form", "tea")
                    put("semantic_sense_key", "茶树"); put("display", "tea"); put("meaning_zh", "茶树")
                    put("status", "LEARNING"); put("verified_success_count", 2)
                })
            }
            val originalColumns = tables.associateWith { columns(helper, it) }
            val before = tables.associateWith { rows(helper, "select rowid,${originalColumns.getValue(it).joinToString()} from $it order by rowid") }
            db.version = 24
            helper.close(); helper = FormationDatabase(context); db = helper.writableDatabase
            assertEquals(26, db.version)
            for (table in tables) assertEquals("Upgrade changed existing $table rows", before.getValue(table),
                rows(helper, "select rowid,${originalColumns.getValue(table).joinToString()} from $table order by rowid"))
            for (table in listOf("target_selections", "pattern_items")) {
                assertTrue(columns(helper, table).contains("related_vocabulary_snapshot"))
                assertEquals(listOf(listOf(null), listOf(null)), rows(helper, "select related_vocabulary_snapshot from $table order by rowid"))
            }
            for (account in listOf(owner, other)) {
                val runtime = SessionRuntime().apply { update("isolated", account) }
                val pattern = (AndroidPatternBookRepository(context, runtime).loadAllPatterns() as PatternLoadResult.Ready).patterns.single()
                assertNull("An unrelated existing tea sense must not fill the old pattern snapshot", pattern.relatedVocabulary)
                assertEquals(listOf("used to", "verb"), pattern.structureParts)
                assertEquals(3, pattern.examples.size); assertEquals(2, pattern.writingConsecutiveSuccessCount)
                assertTrue(AndroidFixedChapterRepository(context, runtime).load("old-chapter") is FixedChapterLoadResult.Loaded)
            }
            assertEquals(listOf(listOf("ok")), rows(helper, "pragma integrity_check"))
        } finally { helper.close(); context.deleteDatabase("real-language-learning.db") }
    }

    private fun columns(helper: FormationDatabase, table: String): List<String> =
        rows(helper, "pragma table_info($table)").map { it[1]!! }
    private fun rows(helper: FormationDatabase, sql: String): List<List<String?>> =
        helper.readableDatabase.rawQuery(sql, null).use { c -> buildList {
            while (c.moveToNext()) add(List(c.columnCount) { if (c.isNull(it)) null else c.getString(it) })
        } }
}
