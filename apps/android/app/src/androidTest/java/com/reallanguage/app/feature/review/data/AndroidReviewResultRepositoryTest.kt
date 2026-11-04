package com.reallanguage.app.feature.review.data

import android.content.ContentValues
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft
import com.reallanguage.app.feature.review.domain.ReviewChapterCommitResult
import com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult
import com.reallanguage.app.feature.review.domain.ReviewQueueResult
import com.reallanguage.app.feature.review.domain.ReviewSettlementResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidReviewResultRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = SessionRuntime().apply { update("token", "account") }

    @Before fun setUp() {
        context = com.reallanguage.app.testing.IsolatedLearningDatabaseContext(
            InstrumentationRegistry.getInstrumentation().targetContext, "review-result")
        database = FormationDatabase(context)
        database.writableDatabase
        listOf("2026-08-29", "2026-08-30", "2026-09-01").forEach(::seedDay)
    }
    @After fun tearDown() { database.close(); context.deleteDatabase("real-language-learning.db") }

    @Test fun completedCycleResetsLearningDaysAndSettlementReplaysExactly() = runBlocking {
        seedChapter("c1", "f1")
        val sessionId = completeFirstChapter()
        val repository = AndroidReviewResultRepository(context, runtime)

        val settled = repository.settleSession(sessionId, "settle-1") as ReviewSettlementResult.Settled
        assertTrue(settled.summary.cycleCompleted)
        assertTrue(settled.summary.normalLearningDaysReset)
        assertEquals(0, count("normal_learning_days"))
        assertEquals(1, settled.summary.completedFocusSentenceCount)

        val replay = repository.settleSession(sessionId, "settle-1") as ReviewSettlementResult.AlreadySettled
        assertEquals(settled.summary, replay.summary)
        assertEquals(1, count("review_session_settlements"))
    }

    @Test fun remainingReviewChapterKeepsCycleAndLearningDayBoundary() = runBlocking {
        seedChapter("c1", "f1")
        seedChapter("c2", "f2")
        val sessionId = completeFirstChapter()
        val settled = AndroidReviewResultRepository(context, runtime)
            .settleSession(sessionId, "settle-2") as ReviewSettlementResult.Settled

        assertFalse(settled.summary.cycleCompleted)
        assertEquals(1, settled.summary.remainingFocusSentenceCount)
        assertEquals(3, count("normal_learning_days"))
    }

    @Test fun resultUsesCurrentEligibilityInsteadOfCountingStaleQueueEntries() = runBlocking {
        seedChapter("c1", "f1")
        seedChapter("c2", "f2")
        val sessionId = completeFirstChapter()
        // A shared capability may be verified by another completed chapter before
        // settlement. Its old queue row is not evidence of remaining work.
        database.writableDatabase.execSQL("update sentence_capabilities set status='VERIFIED' where account_id='account' and sentence_id='s-c2'")
        val settled = AndroidReviewResultRepository(context, runtime)
            .settleSession(sessionId, "settle-stale") as ReviewSettlementResult.Settled
        assertEquals(0, settled.summary.remainingFocusSentenceCount)
        assertTrue(settled.summary.cycleCompleted)
        assertEquals(1, settled.summary.completedFocusSentenceCount)
        assertEquals(1, count("review_chapter_results"))
        assertEquals(0, count("normal_learning_days"))
    }

    @Test fun missingFormalStateCannotBeTreatedAsNoRemainingWork() = runBlocking {
        seedChapter("c1", "f1")
        seedChapter("c2", "f2")
        val sessionId = completeFirstChapter()
        database.writableDatabase.delete("sentence_capabilities", "account_id=? and sentence_id=?", arrayOf("account", "s-c2"))
        assertEquals(ReviewSettlementResult.Failed,
            AndroidReviewResultRepository(context, runtime).settleSession(sessionId, "settle-missing-state"))
        assertEquals(3, count("normal_learning_days"))
        assertEquals(0, count("review_session_settlements"))
    }

    @Test fun oldAccountCannotSettleNewAccountsSessionOrResetTheirDays() = runBlocking {
        seedChapter("c1", "f1")
        val sessionId = completeFirstChapter()
        val oldRuntime = SessionRuntime().apply { update("old-token", "old-account") }
        val repository = AndroidReviewResultRepository(context, oldRuntime)
        oldRuntime.update("new-token", "account")
        assertEquals(ReviewSettlementResult.Failed, repository.settleSession(sessionId, "wrong-owner"))
        assertEquals(3, count("normal_learning_days"))
        assertEquals(0, count("review_session_settlements"))
    }

    @Test fun missingCompletedChapterEvidenceCannotResetLearningDays() = runBlocking {
        seedChapter("c1", "f1")
        val sessionId = completeFirstChapter()
        database.writableDatabase.delete("review_chapter_results", "account_id=?", arrayOf("account"))
        assertEquals(ReviewSettlementResult.Failed,
            AndroidReviewResultRepository(context, runtime).settleSession(sessionId, "missing-evidence"))
        assertEquals(3, count("normal_learning_days"))
        assertEquals(0, count("review_session_settlements"))
    }

    @Test fun missingCycleCannotClaimCycleReset() = runBlocking {
        seedChapter("c1", "f1")
        val sessionId = completeFirstChapter()
        database.writableDatabase.delete("review_cycles", "account_id=?", arrayOf("account"))
        assertEquals(ReviewSettlementResult.Failed,
            AndroidReviewResultRepository(context, runtime).settleSession(sessionId, "missing-cycle"))
        assertEquals(3, count("normal_learning_days"))
    }

    @Test fun savedChapterCanSettleAfterHomeReconcilesTheEmptyCycle() = runBlocking {
        seedChapter("c1", "f1")
        val sessionId = completeFirstChapter()
        assertFalse(AndroidTodayReviewRepository(context, runtime) { "2026-09-02" }.hasDueReview())
        val settled = AndroidReviewResultRepository(context, runtime)
            .settleSession(sessionId, "after-home") as ReviewSettlementResult.Settled
        assertEquals(1, settled.summary.completedFocusSentenceCount)
        assertTrue(settled.summary.cycleCompleted)
        assertEquals(1, count("review_chapter_results"))
    }

    private suspend fun completeFirstChapter(): String {
        val queue = AndroidTodayReviewRepository(context, runtime) { "2026-09-02" }.loadTodayQueue(3) as ReviewQueueResult.Ready
        val reviewId = queue.chapters.first().reviewChapterId
        val repository = AndroidReviewChapterRepository(context, runtime) { "2026-09-02" }
        val chapter = (repository.loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter
        val result = repository.commitChapter(
            reviewId,
            "commit-$reviewId",
            chapter.abilities.map { ReviewAbilityResultDraft(it.abilityId, "evidence", true) },
        ) as ReviewChapterCommitResult.Committed
        return result.reviewSessionId
    }

    private fun seedChapter(chapterId: String, flowId: String) {
        val db = database.writableDatabase
        db.insertOrThrow("formation_flows", null, values("account_id" to "account", "flow_id" to flowId, "status" to "FIXED", "source_type" to "DIARY", "source_title" to "日记"))
        db.insertOrThrow("chapters", null, values("account_id" to "account", "flow_id" to flowId, "chapter_id" to chapterId, "chapter_order" to 0, "status" to "COMPLETED"))
        db.insertOrThrow("fixed_sentences", null, values("account_id" to "account", "chapter_id" to chapterId, "unit_id" to "s-$chapterId", "sentence_order" to 0, "text_zh" to "句子", "text_en" to "Sentence $chapterId.", "role" to "FOCUS"))
        db.insertOrThrow("sentence_capabilities", null, values("account_id" to "account", "sentence_id" to "s-$chapterId", "capability_type" to "FULL_WRITING", "status" to "LEARNING"))
        db.insertOrThrow("learning_obligations", null, values("account_id" to "account", "obligation_id" to "o-$chapterId", "chapter_id" to chapterId, "sentence_id" to "s-$chapterId", "capability_type" to "FULL_WRITING", "target_id" to ""))
    }
    private fun seedDay(date: String) = database.writableDatabase.insertOrThrow("normal_learning_days", null, values("account_id" to "account", "local_date" to date))
    private fun count(table: String) = database.readableDatabase.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }
    private fun values(vararg pairs: Pair<String, Any>) = ContentValues().apply { pairs.forEach { (key, value) -> if (value is Int) put(key, value) else put(key, value.toString()) } }
}
