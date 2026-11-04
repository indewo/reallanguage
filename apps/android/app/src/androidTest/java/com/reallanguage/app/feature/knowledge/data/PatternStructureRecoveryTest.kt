package com.reallanguage.app.feature.knowledge.data

import android.content.ContentValues
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PatternStructureRecoveryTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("fixture-token", "owner") }

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "pattern-structure")
        database = FormationDatabase(context)
        database.writableDatabase
    }
    @After fun tearDown() { database.close(); context.deleteDatabase("real-language-learning.db") }

    @Test fun recoversOnlyStoredPartsFromTheExactOwnedArchivedOccurrenceWithoutChangingData() = runBlocking {
        pattern("p", "owner")
        source("owner", "c1", "s1", "t1", listOf("used to", "+ verb"))
        occurrence("owner", "p", "c1", "s1")
        source("owner", "c1", "s1", "other-target", listOf("wrong target"), display = "want to")
        source("other", "c1", "s1", "t1", listOf("other account"))
        occurrence("other", "p", "c1", "s1")
        source("owner", "active", "s2", "t2", listOf("not archived"), completed = false)
        occurrence("owner", "p", "active", "s2")
        val before = snapshot()
        assertEquals(listOf("used to", "+ verb"), read().single().structureParts)
        assertEquals(before, snapshot())
        assertEquals(listOf("used to", "+ verb"), read().single().structureParts)
    }

    @Test fun identicalConfirmedPartsAcrossOccurrencesAreReusableButConflictingPartsAreNotGuessed() = runBlocking {
        pattern("p", "owner")
        source("owner", "c1", "s1", "t1", listOf("used to", "+ verb"))
        source("owner", "c2", "s2", "t2", listOf("used to", "+ verb"))
        occurrence("owner", "p", "c1", "s1")
        occurrence("owner", "p", "c2", "s2")
        assertEquals(listOf("used to", "+ verb"), read().single().structureParts)
        database.writableDatabase.execSQL("update target_selections set structure_parts=? where account_id='owner' and target_id='t2'", arrayOf("used\u001fto\u001fverb"))
        assertEquals(emptyList<String>(), read().single().structureParts)
    }

    @Test fun legacyMissingPartsAndRemovedOldOccurrencesNeverBecomeAnInventedBreakdown() = runBlocking {
        pattern("new-p", "owner")
        source("owner", "old-chapter", "old-sentence", "old-target", listOf("old structure"))
        occurrence("owner", "removed-p", "old-chapter", "old-sentence")
        source("owner", "new-chapter", "new-sentence", "new-target", emptyList())
        occurrence("owner", "new-p", "new-chapter", "new-sentence")
        assertEquals(emptyList<String>(), read().single().structureParts)
    }

    private suspend fun read() = (AndroidPatternBookRepository(context, runtime).loadAllPatterns() as PatternLoadResult.Ready).patterns

    private fun pattern(id: String, account: String) = insert("pattern_items", "account_id" to account, "item_id" to id,
        "semantic_pattern_key" to "used to + verb|表示过去习惯", "structure_en" to "used to + verb", "meaning_zh" to "表示过去习惯",
        "writing_status" to "LEARNING", "speaking_status" to "PENDING", "writing_streak" to 0, "speaking_streak" to 0, "active_validation" to 1)

    private fun source(account: String, chapter: String, sentence: String, target: String, parts: List<String>, display: String = "used to + verb", completed: Boolean = true) {
        database.writableDatabase.insertWithOnConflict("chapters", null, values("account_id" to account, "flow_id" to "flow", "chapter_id" to chapter,
            "chapter_order" to 0, "status" to if (completed) "COMPLETED" else "NOT_STARTED"), android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        insert("target_selections", "account_id" to account, "chapter_id" to chapter, "target_id" to target, "unit_id" to sentence,
            "type" to "PATTERN", "display" to display, "explanation_zh" to "表示过去习惯", "structure_parts" to parts.joinToString("\u001f"), "related_vocabulary" to "")
    }
    private fun occurrence(account: String, item: String, chapter: String, sentence: String) = insert("pattern_occurrences", "account_id" to account,
        "item_id" to item, "chapter_id" to chapter, "sentence_id" to sentence, "is_focus" to 1)
    private fun insert(table: String, vararg fields: Pair<String, Any>) { database.writableDatabase.insertOrThrow(table, null, values(*fields)) }
    private fun values(vararg fields: Pair<String, Any>) = ContentValues().apply {
        fields.forEach { (key, value) -> when(value) { is String -> put(key, value); is Int -> put(key, value) } }
    }
    private fun snapshot() = listOf("pattern_items", "pattern_occurrences", "target_selections", "chapters").associateWith { table ->
        database.readableDatabase.rawQuery("select * from $table order by rowid", null).use { cursor -> buildList {
            while (cursor.moveToNext()) add(List(cursor.columnCount) { if(cursor.isNull(it)) null else cursor.getString(it) })
        } }
    }
}
