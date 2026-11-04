package com.reallanguage.app.feature.review.data

import android.content.ContentValues
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.review.domain.ReviewQueueResult
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidTodayReviewRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("token", "account") }

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "today-review")
        database = FormationDatabase(context)
        database.writableDatabase
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
    }

    @Test fun queueRequiresThreeActualDaysAndKeepsDeterministicSourceChapters() = runBlocking {
        seedChapter("c1", "f1", "DIARY", "昨晚的日记", "I spoke up.")
        val repository = AndroidTodayReviewRepository(context, runtime) { "2026-09-02" }
        assertTrue((repository.loadTodayQueue(3) as ReviewQueueResult.Ready).chapters.isEmpty())

        listOf("2026-08-29", "2026-08-30", "2026-09-01").forEach(::seedDay)
        val first = (repository.loadTodayQueue(3) as ReviewQueueResult.Ready).chapters
        val replay = (repository.loadTodayQueue(3) as ReviewQueueResult.Ready).chapters

        assertEquals(first, replay)
        assertEquals(1, first.size)
        assertEquals(ReviewSourceType.DIARY, first.single().sourceType)
        assertEquals("昨晚的日记", first.single().sourceTitle)
        assertEquals("I spoke up.", first.single().focusSentence)
    }

    @Test fun dailyLimitNeverReturnsMoreThanThreeDistinctFocusSentences() = runBlocking {
        listOf("2026-08-29", "2026-08-30", "2026-09-01").forEach(::seedDay)
        seedChapter("c1", "f1", "DIARY", "日记", "One.")
        seedChapter("c2", "f2", "QUICK_RECORDING", "通勤录音", "Two.")
        seedChapter("c3", "f3", "AI_SCENE", "会议场景", "Three.")
        seedChapter("c4", "f4", "DIARY", "周末日记", "Four.")

        val chapters = (AndroidTodayReviewRepository(context, runtime) { "2026-09-02" }
            .loadTodayQueue(3) as ReviewQueueResult.Ready).chapters

        assertEquals(3, chapters.sumOf { it.focusSentenceCount })
        assertEquals(listOf("c1", "c2", "c3"), chapters.map { it.originalChapterId })
        assertEquals(
            listOf(ReviewSourceType.DIARY, ReviewSourceType.QUICK_RECORDING, ReviewSourceType.AI_SCENE),
            chapters.map { it.sourceType },
        )
    }

    @Test fun oldAccountRepositoryCannotReadOrMaterializeNewAccountQueue() = runBlocking {
        val oldRuntime = SessionRuntime().apply { update("old-token", "old-account") }
        val repository = AndroidTodayReviewRepository(context, oldRuntime) { "2026-09-07" }
        seedChapter("c1", "f1", "DIARY", "日记", "One.")
        listOf("2026-09-01", "2026-09-02", "2026-09-03").forEach(::seedDay)
        oldRuntime.update("new-token", "account")
        assertEquals(false, repository.hasDueReview())
        assertEquals(ReviewQueueResult.Failed, repository.loadTodayQueue(3))
        database.readableDatabase.rawQuery("select count(*) from review_cycles", null).use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun expiredSessionDoesNotReadOrCreateReviewCycle() = runBlocking {
        val repository = AndroidTodayReviewRepository(context, runtime) { "2026-09-07" }
        seedChapter("c1", "f1", "DIARY", "日记", "One.")
        listOf("2026-09-01", "2026-09-02", "2026-09-03").forEach(::seedDay)
        runtime.clear()
        assertEquals(false, repository.hasDueReview())
        assertEquals(ReviewQueueResult.Failed, repository.loadTodayQueue(3))
    }

    @Test fun version19QueueUpgradePreservesReferencesAndSnapshotsOnlyOnce() = runBlocking {
        listOf("2026-09-01", "2026-09-02", "2026-09-03").forEach(::seedDay)
        seedVocabularyChapter("word", "LEARNING", 1)
        val repository = AndroidTodayReviewRepository(context, runtime)
        val before = repository.loadTodayQueue(3)
        val db = database.writableDatabase
        db.execSQL("alter table review_queue_items rename to queue_test_legacy")
        db.execSQL("create table review_queue_items(account_id text not null, review_cycle_id text not null, queue_item_id text not null, obligation_id text not null, original_chapter_id text not null, sentence_id text not null, priority_rank integer not null, entered_at integer not null, primary key(account_id,queue_item_id), unique(account_id,review_cycle_id,obligation_id))")
        db.execSQL("insert into review_queue_items select account_id,review_cycle_id,queue_item_id,obligation_id,original_chapter_id,sentence_id,priority_rank,entered_at from queue_test_legacy")
        db.execSQL("drop table queue_test_legacy")
        db.execSQL("create index review_queue_order on review_queue_items(account_id,review_cycle_id,priority_rank,entered_at)")
        db.execSQL("alter table target_selections drop column source_evidence")
        com.reallanguage.app.testing.removeKnowledgeReuseSchemaForLegacyFixture(db)
        db.version = 19
        database.close()
        database = FormationDatabase(context)
        assertEquals(26, database.readableDatabase.version)
        database.readableDatabase.rawQuery("select occurrence_count from review_queue_items", null).use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
        }
        assertEquals(before, repository.loadTodayQueue(3))
        database.writableDatabase.execSQL("insert into vocabulary_occurrences values ('account','word','word','later',0)")
        assertEquals(before, repository.loadTodayQueue(3))
        database.readableDatabase.rawQuery("select occurrence_count from review_queue_items", null).use {
            it.moveToFirst(); assertEquals(1, it.getInt(0))
        }
    }

    @Test fun pendingAbilityPrecedesAnOlderLearningAbility() = runBlocking {
        listOf("2026-09-01", "2026-09-02", "2026-09-03").forEach(::seedDay)
        seedChapter("older", "f-old", "DIARY", "旧来源", "Older.")
        seedChapter("pending", "f-new", "AI_SCENE", "待学习来源", "Pending.")
        database.writableDatabase.execSQL("update sentence_capabilities set status='PENDING' where sentence_id='s-pending'")
        val queue = AndroidTodayReviewRepository(context, runtime).loadTodayQueue(3) as ReviewQueueResult.Ready
        assertEquals(listOf("pending", "older"), queue.chapters.map { it.originalChapterId })
    }

    @Test fun completingFirstChapterOpensTheNextChapterInTheSameDisplayedOrder() = runBlocking {
        listOf("2026-09-01", "2026-09-02", "2026-09-03").forEach(::seedDay)
        seedChapter("start", "first", "DIARY", "第一章", "Start.")
        database.writableDatabase.execSQL("update sentence_capabilities set status='PENDING'")
        seedVocabularyChapter("many", "LEARNING", 3)
        seedVocabularyChapter("rare", "LEARNING", 1)
        val queue = AndroidTodayReviewRepository(context, runtime).loadTodayQueue(3) as ReviewQueueResult.Ready
        assertEquals(listOf("start", "rare", "many"), queue.chapters.map { it.originalChapterId })
        val repository = AndroidReviewChapterRepository(context, runtime)
        val first = queue.chapters.first().reviewChapterId
        val loaded = repository.loadChapter(first) as com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult.Ready
        val committed = repository.commitChapter(first, "next-sorted", loaded.chapter.abilities.map {
            com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft(it.abilityId, "e-${it.abilityId}")
        }) as com.reallanguage.app.feature.review.domain.ReviewChapterCommitResult.Committed
        assertEquals(queue.chapters[1].reviewChapterId, committed.nextReviewChapterId)
    }

    @Test fun sameStatusUsesOccurrenceCountThenStableEntryOrder() = runBlocking {
        listOf("2026-09-01", "2026-09-02", "2026-09-03").forEach(::seedDay)
        seedVocabularyChapter("many", "LEARNING", 3)
        seedVocabularyChapter("rare-old", "LEARNING", 1)
        seedVocabularyChapter("rare-new", "LEARNING", 1)
        val queue = AndroidTodayReviewRepository(context, runtime).loadTodayQueue(3) as ReviewQueueResult.Ready
        assertEquals(listOf("rare-old", "rare-new", "many"), queue.chapters.map { it.originalChapterId })
    }

    @Test fun verifiedVocabularyIsRemovedFromAnAlreadyMaterializedQueue() = runBlocking {
        listOf("2026-09-01", "2026-09-02", "2026-09-03").forEach(::seedDay)
        seedVocabularyChapter("word", "LEARNING", 1)
        val repository = AndroidTodayReviewRepository(context, runtime)
        assertEquals(1, (repository.loadTodayQueue(3) as ReviewQueueResult.Ready).chapters.size)
        database.writableDatabase.execSQL("update vocabulary_items set status='VERIFIED' where item_id='word'")
        assertTrue((repository.loadTodayQueue(3) as ReviewQueueResult.Ready).chapters.isEmpty())
        database.readableDatabase.rawQuery("select count(*) from review_queue_items", null).use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun absentKnowledgeIsAnExplicitFailureNotAnInventedLearningItem() = runBlocking {
        listOf("2026-09-01", "2026-09-02", "2026-09-03").forEach(::seedDay)
        seedVocabularyChapter("missing", "LEARNING", 1)
        database.writableDatabase.delete("vocabulary_items", "item_id='missing'", null)
        assertEquals(ReviewQueueResult.Failed, AndroidTodayReviewRepository(context, runtime).loadTodayQueue(3))
        assertEquals(3, rowCount("normal_learning_days"))
    }

    @Test fun noEligibleAbilitiesEndsEmptyCycleWithoutInventingLearningResults() = runBlocking {
        listOf("2026-09-01", "2026-09-02", "2026-09-03").forEach(::seedDay)
        seedVocabularyChapter("word", "VERIFIED", 1)
        val repository = AndroidTodayReviewRepository(context, runtime) { "2026-09-07" }
        assertEquals(false, repository.hasDueReview())
        assertTrue((repository.loadTodayQueue(3) as ReviewQueueResult.Ready).chapters.isEmpty())
        assertEquals(0, rowCount("normal_learning_days"))
        assertEquals(0, rowCount("review_chapter_results"))
        assertEquals(0, rowCount("review_sessions"))
        assertEquals(1, rowCount("vocabulary_items"))
        assertEquals(1, rowCount("learning_obligations"))
    }

    @Test fun anActiveQueueThatBecomesEmptyNoLongerAdvertisesDueWork() = runBlocking {
        listOf("2026-09-01", "2026-09-02", "2026-09-03").forEach(::seedDay)
        seedVocabularyChapter("word", "LEARNING", 1)
        val repository = AndroidTodayReviewRepository(context, runtime) { "2026-09-07" }
        assertTrue(repository.hasDueReview())
        database.writableDatabase.execSQL("update vocabulary_items set status='VERIFIED'")
        assertEquals(false, repository.hasDueReview())
        assertEquals(false, repository.hasDueReview())
        assertEquals(0, rowCount("normal_learning_days"))
        assertEquals(0, rowCount("review_queue_items"))
    }

    @Test fun dailyLimitDefersRemainingWorkWithoutClaimingEmptyAndNextDayRestoresIt() = runBlocking {
        listOf("2026-09-01", "2026-09-02", "2026-09-03").forEach(::seedDay)
        repeat(4) { seedChapter("c$it", "f$it", "DIARY", "日记", "Sentence $it.") }
        var date = "2026-09-07"
        val repository = AndroidTodayReviewRepository(context, runtime) { date }
        val initial = repository.loadTodayQueue(3) as ReviewQueueResult.Ready
        val chapters = AndroidReviewChapterRepository(context, runtime) { date }
        initial.chapters.forEach { summary ->
            val loaded = chapters.loadChapter(summary.reviewChapterId) as com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult.Ready
            assertTrue(chapters.commitChapter(summary.reviewChapterId, "commit-${summary.reviewChapterId}", loaded.chapter.abilities.map {
                com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft(it.abilityId, "isolated-evidence", true)
            }) is com.reallanguage.app.feature.review.domain.ReviewChapterCommitResult.Committed)
        }
        val limited = repository.loadTodayQueue(3) as ReviewQueueResult.Ready
        assertTrue(limited.chapters.isEmpty())
        assertTrue(limited.deferredByDailyLimit)
        assertTrue(repository.hasDueReview())
        assertEquals(3, rowCount("normal_learning_days"))
        date = "2026-09-08"
        val nextDay = repository.loadTodayQueue(3) as ReviewQueueResult.Ready
        assertEquals(1, nextDay.chapters.size)
        assertEquals(false, nextDay.deferredByDailyLimit)
    }

    private fun rowCount(table: String) = database.readableDatabase.rawQuery("select count(*) from $table", null).use {
        it.moveToFirst(); it.getInt(0)
    }

    private fun seedVocabularyChapter(id: String, status: String, occurrences: Int) {
        seedChapter(id, "flow-$id", "DIARY", "来源 $id", "I use $id.")
        database.writableDatabase.apply {
            execSQL("update learning_obligations set capability_type='MEANING_WRITING',target_id=? where chapter_id=?", arrayOf("target-$id", id))
            insertOrThrow("target_selections", null, ContentValues().apply {
                put("account_id", "account"); put("chapter_id", id); put("target_id", "target-$id"); put("unit_id", "s-$id")
                put("type", "WORD"); put("display", id); put("explanation_zh", "意思 $id"); put("structure_parts", ""); put("related_vocabulary", "")
            })
            insertOrThrow("vocabulary_items", null, ContentValues().apply {
                put("account_id", "account"); put("item_id", id); put("normalized_form", id); put("semantic_sense_key", "意思 $id")
                put("display", id); put("meaning_zh", "意思 $id"); put("status", status); put("verified_success_count", 0)
            })
            repeat(occurrences) { index ->
                insertOrThrow("vocabulary_occurrences", null, ContentValues().apply {
                    put("account_id", "account"); put("item_id", id); put("chapter_id", id); put("sentence_id", "s-$id-$index"); put("is_focus", 1)
                })
            }
        }
    }

    private fun seedDay(date: String) {
        database.writableDatabase.insertOrThrow("normal_learning_days", null, ContentValues().apply {
            put("account_id", "account"); put("local_date", date)
        })
    }

    private fun seedChapter(
        chapterId: String,
        flowId: String,
        sourceType: String,
        sourceTitle: String,
        english: String,
    ) {
        val db = database.writableDatabase
        db.insertOrThrow("formation_flows", null, ContentValues().apply {
            put("account_id", "account"); put("flow_id", flowId); put("status", "FIXED")
            put("source_type", sourceType); put("source_title", sourceTitle)
        })
        db.insertOrThrow("chapters", null, ContentValues().apply {
            put("account_id", "account"); put("flow_id", flowId); put("chapter_id", chapterId)
            put("chapter_order", 0); put("status", "COMPLETED")
        })
        db.insertOrThrow("fixed_sentences", null, ContentValues().apply {
            put("account_id", "account"); put("chapter_id", chapterId); put("unit_id", "s-$chapterId")
            put("sentence_order", 0); put("text_zh", "原句 $chapterId"); put("text_en", english); put("role", "FOCUS")
        })
        db.insertOrThrow("sentence_capabilities", null, ContentValues().apply {
            put("account_id", "account"); put("sentence_id", "s-$chapterId")
            put("capability_type", "FULL_WRITING"); put("status", "LEARNING")
        })
        db.insertOrThrow("learning_obligations", null, ContentValues().apply {
            put("account_id", "account"); put("obligation_id", "o-$chapterId")
            put("chapter_id", chapterId); put("sentence_id", "s-$chapterId")
            put("capability_type", "FULL_WRITING"); put("target_id", "")
        })
    }
}
