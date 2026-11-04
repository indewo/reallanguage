package com.reallanguage.app.feature.review.data

import android.content.ContentValues
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.review.domain.ReviewExitResult
import com.reallanguage.app.feature.review.domain.ReviewQueueResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AndroidReviewExitRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("token", "account") }

    @Before fun setUp() {
        context = com.reallanguage.app.testing.IsolatedLearningDatabaseContext(
            InstrumentationRegistry.getInstrumentation().targetContext, "review-exit-regression")
        database = FormationDatabase(context)
        database.writableDatabase
        seed()
    }
    @After fun tearDown() { database.close(); context.deleteDatabase("real-language-learning.db") }

    @Test fun discardsOnlyCurrentReadyChapterWorkingStateAndIsIdempotent() = runBlocking {
        val reviewId = currentReviewId()
        insertWorking(reviewId, "a-current")
        insertWorking("other-review", "a-other")
        val repository = AndroidReviewExitRepository(context, runtime)

        assertEquals(ReviewExitResult.Discarded, repository.discardWorkingState(reviewId))
        assertEquals(0, count(reviewId))
        assertEquals(1, count("other-review"))
        assertEquals(ReviewExitResult.AlreadyDiscarded, repository.discardWorkingState(reviewId))
    }

    @Test fun unknownOrCompletedChapterCannotClearWorkingState() = runBlocking {
        val repository = AndroidReviewExitRepository(context, runtime)
        assertEquals(ReviewExitResult.Failed, repository.discardWorkingState("unknown"))
        assertEquals(-1, repository.completedChapterCount("unknown"))
    }

    @Test fun completedChapterCountDoesNotMultiplyRowsOrCountAnotherCycle() = runBlocking {
        val reviewId = currentReviewId()
        val db = database.writableDatabase
        val cycle = db.rawQuery("select review_cycle_id from review_chapters where review_chapter_id=?", arrayOf(reviewId)).use { it.moveToFirst(); it.getString(0) }
        db.execSQL("insert into review_chapters values ('account',?,'completed','other-original','DIARY','日记','COMPLETED')", arrayOf(cycle))
        db.execSQL("insert into review_chapters values ('account','other-cycle','other-cycle-chapter','other-original','DIARY','日记','COMPLETED')")
        db.execSQL("insert into review_chapter_results(account_id,review_chapter_id,command_id,local_date,completed_focus_count) values ('account','completed','done','2026-09-02',1)")
        db.execSQL("insert into review_chapter_results(account_id,review_chapter_id,command_id,local_date,completed_focus_count) values ('account','other-cycle-chapter','other-done','2026-09-01',1)")
        assertEquals(1, AndroidReviewExitRepository(context, runtime).completedChapterCount(reviewId))
    }

    @Test fun oldAccountCannotClearTheNewAccountsWorkingChapter() = runBlocking {
        val reviewId = currentReviewId()
        insertWorking(reviewId, "a-current")
        val previous = SessionRuntime().apply { update("isolated", "old-account") }
        val repository = AndroidReviewExitRepository(context, previous)
        previous.update("isolated", "account")
        assertEquals(ReviewExitResult.Failed, repository.discardWorkingState(reviewId))
        assertEquals(1, count(reviewId))
    }

    private suspend fun currentReviewId() =
        (AndroidTodayReviewRepository(context, runtime) { "2026-09-02" }.loadTodayQueue(3) as ReviewQueueResult.Ready)
            .chapters.single().reviewChapterId

    private fun seed() {
        val db = database.writableDatabase
        listOf("2026-08-29", "2026-08-30", "2026-09-01").forEach { date ->
            db.insertOrThrow("normal_learning_days", null, values("account_id" to "account", "local_date" to date))
        }
        db.insertOrThrow("formation_flows", null, values("account_id" to "account", "flow_id" to "flow", "status" to "FIXED", "source_type" to "DIARY", "source_title" to "日记"))
        db.insertOrThrow("chapters", null, values("account_id" to "account", "flow_id" to "flow", "chapter_id" to "chapter", "chapter_order" to 0, "status" to "COMPLETED"))
        db.insertOrThrow("fixed_sentences", null, values("account_id" to "account", "chapter_id" to "chapter", "unit_id" to "focus", "sentence_order" to 0, "text_zh" to "句子", "text_en" to "Sentence.", "role" to "FOCUS"))
        db.insertOrThrow("sentence_capabilities", null, values("account_id" to "account", "sentence_id" to "focus", "capability_type" to "FULL_WRITING", "status" to "LEARNING"))
        db.insertOrThrow("learning_obligations", null, values("account_id" to "account", "obligation_id" to "o1", "chapter_id" to "chapter", "sentence_id" to "focus", "capability_type" to "FULL_WRITING", "target_id" to ""))
    }

    private fun insertWorking(reviewId: String, abilityId: String) = database.writableDatabase.insertOrThrow(
        "review_working_state", null, values("account_id" to "account", "review_chapter_id" to reviewId, "ability_id" to abilityId, "evidence_id" to "evidence"),
    )
    private fun count(reviewId: String) = database.readableDatabase.rawQuery(
        "select count(*) from review_working_state where account_id='account' and review_chapter_id=?", arrayOf(reviewId),
    ).use { it.moveToFirst(); it.getInt(0) }
    private fun values(vararg pairs: Pair<String, Any>) = ContentValues().apply { pairs.forEach { (key, value) -> if (value is Int) put(key, value) else put(key, value.toString()) } }
}
