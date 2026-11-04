package com.reallanguage.app.core.app

import android.content.ContentValues
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.toJson
import com.reallanguage.app.feature.formation.domain.SourceEvidence
import com.reallanguage.app.feature.learning.data.AndroidLockedWritingTargetReader
import com.reallanguage.app.feature.learning.domain.LockedWritingTarget
import com.reallanguage.app.feature.learning.domain.WritingTargetSpan
import com.reallanguage.app.feature.learning.domain.WritingTargetType
import com.reallanguage.app.feature.review.data.AndroidReviewChapterRepository
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbilityStatus
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real queue, native JSON and SQLite; no user database, provider calls or generated results. */
class ReviewWritingTargetEntryTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("isolated-token", "review-owner") }
    private val ability = ReviewAbility("q-tea", ReviewAbilityModule.WORD_MEANING, ReviewAbilityStatus.LEARNING, "s1", "t-tea")
    private val source = SourceEvidence(1, 16, 19, "tea", "tea", listOf("tea"))
    private val expected = LockedWritingTarget("t-tea", "s1", WritingTargetType.MEANING, listOf("tea"), WritingTargetSpan(16, 19, "tea"))

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "review-writing-target")
        database = FormationDatabase(context)
        insert("formation_flows", "account_id" to "review-owner", "flow_id" to "flow", "status" to "FIXED", "source_type" to "DIARY", "source_title" to "日记")
        insert("chapters", "account_id" to "review-owner", "flow_id" to "flow", "chapter_id" to "original", "chapter_order" to 0, "status" to "COMPLETED")
        insert("fixed_sentences", "account_id" to "review-owner", "chapter_id" to "original", "unit_id" to "context", "sentence_order" to 0,
            "text_zh" to "上下文", "text_en" to "This is the original scene.", "role" to "CONTEXT")
        insert("fixed_sentences", "account_id" to "review-owner", "chapter_id" to "original", "unit_id" to "s1", "sentence_order" to 1,
            "text_zh" to "我以前喝茶。", "text_en" to "I used to drink tea.", "role" to "FOCUS")
        insert("target_selections", "account_id" to "review-owner", "chapter_id" to "original", "target_id" to "t-old", "unit_id" to "s1", "type" to "PATTERN",
            "display" to "used to + verb", "explanation_zh" to "过去的习惯", "structure_parts" to "", "related_vocabulary" to "")
        insert("target_selections", "account_id" to "review-owner", "chapter_id" to "original", "target_id" to "t-tea", "unit_id" to "s1", "type" to "WORD",
            "display" to "tea", "explanation_zh" to "茶", "structure_parts" to "", "related_vocabulary" to "", "source_evidence" to source.toJson().toString())
        insert("vocabulary_items", "account_id" to "review-owner", "item_id" to "v-tea", "normalized_form" to "tea", "semantic_sense_key" to "茶", "display" to "tea",
            "meaning_zh" to "茶", "status" to "LEARNING", "verified_success_count" to 0)
        insert("vocabulary_occurrences", "account_id" to "review-owner", "item_id" to "v-tea", "chapter_id" to "original", "sentence_id" to "s1", "is_focus" to 1)
        insert("learning_obligations", "account_id" to "review-owner", "obligation_id" to "o-tea", "chapter_id" to "original", "sentence_id" to "s1",
            "capability_type" to "MEANING_WRITING", "target_id" to "t-tea")
        insert("review_cycles", "account_id" to "review-owner", "review_cycle_id" to "cycle", "status" to "ACTIVE", "started_local_date" to "2026-09-08")
        insert("review_queue_items", "account_id" to "review-owner", "review_cycle_id" to "cycle", "queue_item_id" to "q-tea", "obligation_id" to "o-tea",
            "original_chapter_id" to "original", "sentence_id" to "s1", "priority_rank" to 1, "entered_at" to 1, "occurrence_count" to 1)
        insert("review_chapters", "account_id" to "review-owner", "review_cycle_id" to "cycle", "review_chapter_id" to "review", "original_chapter_id" to "original",
            "source_type" to "DIARY", "source_title" to "日记", "status" to "READY")
        insert("review_chapter_items", "account_id" to "review-owner", "review_chapter_id" to "review", "queue_item_id" to "q-tea", "sentence_id" to "s1", "ability_type" to "MEANING_WRITING")
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
    }

    @Test fun exactReviewTargetIgnoresUnrelatedUnrecoverableLegacyButWholeChapterRemainsStrict() {
        val reader = AndroidLockedWritingTargetReader(context, runtime)
        assertEquals(listOf(expected), reader.readTarget("original", "s1", "t-tea"))
        assertNull(reader.read("original"))
        assertNull(reader.readTarget("original", "s1", "t-old"))
        assertHistoryUntouched()
    }

    @Test fun realQueueEntryCanReenterTheValidTargetWithoutRewritingOldEvidence() = runBlocking {
        assertEquals(listOf(expected), entry())
        assertEquals(listOf(expected), entry())
        assertHistoryUntouched()
    }

    @Test fun requestedTargetMustExistInTheExactAccountOriginalChapterAndFocusSentence() {
        // Remove only this test fixture's unrelated legacy row to expose scope validation directly.
        database.writableDatabase.delete("target_selections", "target_id=?", arrayOf("t-old"))
        val reader = AndroidLockedWritingTargetReader(context, runtime)
        assertNull(reader.readTarget("other", "s1", "t-tea"))
        assertNull(reader.readTarget("original", "context", "t-tea"))
        assertNull(reader.readTarget("original", "s1", "missing"))
        assertNull(reader.readTarget("original", "", "t-tea"))
        assertNull(AndroidLockedWritingTargetReader(context, SessionRuntime().apply { update("isolated", "other-owner") }).readTarget("original", "s1", "t-tea"))
        runtime.update("isolated", "other-owner")
        assertNull(reader.readTarget("original", "s1", "t-tea"))
        assertEquals(0, count("writing_result_drafts"))
    }

    @Test fun badCurrentTargetEvidenceFailsInsteadOfSkippingItOrProducingFormalResults() = runBlocking {
        val corrupt = source.copy(startUtf16 = 15).toJson().toString()
        database.writableDatabase.execSQL("update target_selections set source_evidence=? where target_id='t-tea'", arrayOf(corrupt))
        assertNull(entry())
        assertEquals(corrupt, value("select source_evidence from target_selections where target_id='t-tea'"))
        assertEquals(0, count("review_chapter_results"))
        assertEquals(0, count("writing_result_drafts"))
        assertEquals(1, count("learning_obligations"))
    }

    @Test fun entryCannotUseAnotherQueueAbilityOrSubstituteAnotherModuleSentenceOrChapter() = runBlocking {
        database.writableDatabase.delete("target_selections", "target_id=?", arrayOf("t-old"))
        assertNull(entry(requested = ability.copy(abilityId = "not-in-queue")))
        assertNull(entry(requested = ability.copy(module = ReviewAbilityModule.PATTERN_WRITING)))
        assertNull(entry(requested = ability.copy(sentenceId = "context")))
        assertNull(entry(original = "other-original"))
        assertNull(entry(review = "other-review"))
        assertNull(entry(requested = ability.copy(requiresReview = false)))
        assertEquals(0, count("review_chapter_results"))
    }

    @Test fun removedMasteredOrCompletedQueueCannotReopenAStaleTarget() = runBlocking {
        database.writableDatabase.delete("target_selections", "target_id=?", arrayOf("t-old"))
        database.writableDatabase.execSQL("update vocabulary_items set status='VERIFIED'")
        assertNull(entry())
        database.writableDatabase.execSQL("update vocabulary_items set status='LEARNING'")
        database.writableDatabase.execSQL("update review_chapters set status='COMPLETED'")
        assertNull(entry())
        database.writableDatabase.execSQL("update review_chapters set status='READY'")
        database.writableDatabase.delete("review_queue_items", "queue_item_id=?", arrayOf("q-tea"))
        assertNull(entry())
        assertEquals(0, count("review_chapter_results"))
        assertEquals(1, count("learning_obligations"))
    }

    @Test fun queueLoadAndTargetReadRemainBoundToTheAccountThatCreatedTheEntry() = runBlocking {
        val repository = AndroidReviewChapterRepository(context, runtime)
        val reader = AndroidLockedWritingTargetReader(context, runtime)
        val loaded = loadReviewWritingTargets("review", "original", ability,
            loadChapter = { id -> repository.loadChapter(id).also { runtime.update("isolated", "other-owner") } },
            readTarget = reader::readTarget,
        )
        assertNull(loaded)
        assertHistoryUntouched()
    }

    private suspend fun entry(review: String = "review", original: String = "original", requested: ReviewAbility = ability): List<LockedWritingTarget>? {
        val repository = AndroidReviewChapterRepository(context, runtime)
        val reader = AndroidLockedWritingTargetReader(context, runtime)
        return loadReviewWritingTargets(review, original, requested, repository::loadChapter, reader::readTarget)
    }

    private fun assertHistoryUntouched() {
        assertEquals(2, count("target_selections"))
        assertNull(value("select source_evidence from target_selections where target_id='t-old'"))
        assertEquals(source.toJson().toString(), value("select source_evidence from target_selections where target_id='t-tea'"))
        listOf("writing_result_drafts", "writing_batch_completions", "review_chapter_results").forEach { assertEquals(0, count(it)) }
    }

    private fun insert(table: String, vararg pairs: Pair<String, Any>) {
        database.writableDatabase.insertOrThrow(table, null, ContentValues().apply {
            pairs.forEach { (key, value) -> if (value is Int) put(key, value) else put(key, value.toString()) }
        })
    }
    private fun count(table: String) = database.readableDatabase.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }
    private fun value(sql: String): String? = database.readableDatabase.rawQuery(sql, null).use { if (!it.moveToFirst() || it.isNull(0)) null else it.getString(0) }
}
