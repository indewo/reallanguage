package com.reallanguage.app.feature.knowledge.data

import android.content.ContentValues
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import com.reallanguage.app.feature.knowledge.domain.PatternRemovalResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AndroidPatternBookRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("token", "account") }

    @Before fun setUp() {
        context = com.reallanguage.app.testing.IsolatedLearningDatabaseContext(
            InstrumentationRegistry.getInstrumentation().targetContext, "pattern-book")
        database = FormationDatabase(context)
        database.writableDatabase
    }
    @After fun tearDown() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
    }

    @Test fun loadsEveryAccountPatternWithoutVocabularyScopeAndKeepsIndependentCapabilities() = runBlocking {
        seedPattern("p1", "account", "used to", "过去习惯", "VERIFIED", "LEARNING", 3, 1, false)
        seedPattern("p2", "account", "for the first time", "第一次", "LEARNING", "VERIFIED", 0, 2, true)
        seedPattern("other", "another", "other", "其他", "VERIFIED", "VERIFIED", 3, 3, true)
        repeat(3) { index -> insert("pattern_examples", "account_id" to "account", "item_id" to "p1", "example_order" to index, "text_en" to "Example $index", "text_zh" to "例句 $index", "pronunciation_text" to "Example $index") }
        insert("pattern_occurrences", "account_id" to "account", "item_id" to "p1", "chapter_id" to "c1", "sentence_id" to "s1", "is_focus" to 1)

        val records = (AndroidPatternBookRepository(context, runtime).loadAllPatterns() as PatternLoadResult.Ready).patterns

        assertEquals(listOf("p1", "p2"), records.map { it.patternId })
        assertEquals(KnowledgeStatus.MASTERED, records.first().writingStatus)
        assertEquals(KnowledgeStatus.LEARNING, records.first().speakingStatus)
        assertEquals(3, records.first().writingConsecutiveSuccessCount)
        assertEquals(1, records.first().speakingConsecutiveSuccessCount)
        assertEquals(false, records.first().naturalRevalidation)
        assertEquals(3, records.first().examples.size)
        assertEquals(1, records.first().occurrenceCount)
    }

    @Test fun pendingAndSelfAssessedAreLoadedPerAbilityWithoutInventingSuccess() = runBlocking {
        seedPattern("pending", "account", "used to", "过去习惯", "PENDING", "SELF_ASSESSED", 0, 0, true)
        val item = (AndroidPatternBookRepository(context, runtime).loadAllPatterns() as PatternLoadResult.Ready).patterns.single()
        assertEquals(KnowledgeStatus.NOT_STARTED, item.writingStatus)
        assertEquals(KnowledgeStatus.TENTATIVE, item.speakingStatus)
        assertEquals(0, item.consecutiveSuccessCount)
    }

    @Test fun removalDeletesOnlyPatternKnowledgeAndFutureQueueReferences() = runBlocking {
        seedPattern("p1", "account", "used to", "过去习惯", "VERIFIED", "LEARNING", 3, 1, true)
        insert("formation_flows", "account_id" to "account", "flow_id" to "f1", "status" to "FIXED", "source_type" to "DIARY", "source_title" to "日记")
        insert("chapters", "account_id" to "account", "flow_id" to "f1", "chapter_id" to "c1", "chapter_order" to 0, "status" to "COMPLETED")
        insert("target_selections", "account_id" to "account", "chapter_id" to "c1", "target_id" to "t1", "unit_id" to "s1", "type" to "PATTERN", "display" to "used to", "explanation_zh" to "过去习惯", "structure_parts" to "usedto", "related_vocabulary" to "")
        insert("pattern_examples", "account_id" to "account", "item_id" to "p1", "example_order" to 0, "text_en" to "I used to run.", "text_zh" to "我过去常跑步。", "pronunciation_text" to "I used to run.")
        insert("pattern_occurrences", "account_id" to "account", "item_id" to "p1", "chapter_id" to "c1", "sentence_id" to "s1", "is_focus" to 1)
        insert("learning_obligations", "account_id" to "account", "obligation_id" to "o1", "chapter_id" to "c1", "sentence_id" to "s1", "capability_type" to "PATTERN_SPEAKING", "target_id" to "t1")
        insert("correction_flags", "account_id" to "account", "obligation_id" to "o1")
        insert("review_queue_items", "account_id" to "account", "review_cycle_id" to "cycle", "queue_item_id" to "q1", "obligation_id" to "o1", "original_chapter_id" to "c1", "sentence_id" to "s1", "priority_rank" to 1, "entered_at" to 1)
        insert("review_chapter_items", "account_id" to "account", "review_chapter_id" to "rc1", "queue_item_id" to "q1", "sentence_id" to "s1", "ability_type" to "PATTERN_SPEAKING")

        val repository = AndroidPatternRemovalRepository(context, runtime)
        assertEquals(PatternRemovalResult.Removed, repository.removeFromPatternBook("p1", "cmd"))
        assertEquals(0, count("pattern_items"))
        assertEquals(0, count("pattern_examples"))
        assertEquals(0, count("pattern_occurrences"))
        assertEquals(0, count("learning_obligations"))
        assertEquals(0, count("review_queue_items"))
        assertEquals(0, count("review_chapter_items"))
        assertEquals(1, count("target_selections"))
        assertEquals(1, count("chapters"))
        assertEquals(PatternRemovalResult.AlreadyRemoved, repository.removeFromPatternBook("p1", "cmd"))
    }

    @Test fun removalIsAccountIsolated() = runBlocking {
        seedPattern("p1", "account", "used to", "过去习惯", "LEARNING", "LEARNING", 0, 0, true)
        seedPattern("p1", "another", "used to", "过去习惯", "VERIFIED", "VERIFIED", 3, 3, true)

        assertEquals(PatternRemovalResult.Removed, AndroidPatternRemovalRepository(context, runtime).removeFromPatternBook("p1", "cmd"))
        assertEquals(1, count("pattern_items"))
    }

    @Test fun oldReaderCannotBorrowChangedAccountIdentity() = runBlocking {
        seedPattern("p1", "account", "used to", "过去习惯", "LEARNING", "LEARNING", 0, 0, true)
        val old = SessionRuntime().apply { update("old-token", "old-account") }
        val repository = AndroidPatternBookRepository(context, old)
        old.update("new-token", "account")
        assertEquals(PatternLoadResult.Failed, repository.loadAllPatterns())
    }

    @Test fun oldRemovalCannotDeleteChangedAccountsKnowledge() = runBlocking {
        seedPattern("p1", "account", "used to", "过去习惯", "LEARNING", "LEARNING", 0, 0, true)
        val old = SessionRuntime().apply { update("old-token", "old-account") }
        val repository = AndroidPatternRemovalRepository(context, old)
        old.update("new-token", "account")
        assertEquals(PatternRemovalResult.Failed, repository.removeFromPatternBook("p1", "wrong-owner"))
        assertEquals(1, count("pattern_items"))
        assertEquals(0, count("pattern_removal_commands"))
    }

    private fun seedPattern(id: String, account: String, structure: String, meaning: String, writing: String, speaking: String, writingStreak: Int, speakingStreak: Int, natural: Boolean) {
        insert("pattern_items", "account_id" to account, "item_id" to id, "semantic_pattern_key" to "${structure.lowercase()}|${meaning.lowercase()}", "structure_en" to structure, "meaning_zh" to meaning, "writing_status" to writing, "speaking_status" to speaking, "writing_streak" to writingStreak, "speaking_streak" to speakingStreak, "active_validation" to 1, "representative_scene" to "代表场景", "natural_revalidation" to if (natural) 1 else 0)
    }
    private fun insert(table: String, vararg fields: Pair<String, Any>) {
        database.writableDatabase.insertOrThrow(table, null, ContentValues().apply {
            fields.forEach { (name, value) -> when (value) { is String -> put(name, value); is Int -> put(name, value) } }
        })
    }
    private fun count(table: String): Int = database.readableDatabase.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }
}
