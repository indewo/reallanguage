package com.reallanguage.app.feature.review.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.review.domain.ReviewResultRepository
import com.reallanguage.app.feature.review.domain.ReviewResultSummary
import com.reallanguage.app.feature.review.domain.ReviewSettlementResult
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidReviewResultRepository(
    context: Context,
    private val runtime: SessionRuntime,
) : ReviewResultRepository {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId && runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    override suspend fun settleSession(reviewSessionId: String, commandId: String): ReviewSettlementResult = withContext(Dispatchers.IO) {
        val accountId = ownerAccountId ?: return@withContext ReviewSettlementResult.Failed
        if (!ownerIsActive() || reviewSessionId.isBlank() || commandId.isBlank()) return@withContext ReviewSettlementResult.Failed
        try { FormationDatabase(applicationContext).use { helper ->
            val result = helper.writableDatabase.settle(accountId, reviewSessionId, commandId)
            if (ownerIsActive()) result else ReviewSettlementResult.Failed
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ReviewSettlementResult.Failed }
    }

    private fun SQLiteDatabase.settle(
        accountId: String,
        reviewSessionId: String,
        commandId: String,
    ): ReviewSettlementResult {
        beginTransaction()
        return try {
            check(ownerIsActive())
            rawQuery(
                "select review_session_id,summary from review_session_settlements where account_id=? and command_id=?",
                arrayOf(accountId, commandId),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    if (cursor.getString(0) != reviewSessionId) return ReviewSettlementResult.Failed
                    val summary = decode(cursor.getString(1))
                    check(ownerIsActive())
                    setTransactionSuccessful()
                    return ReviewSettlementResult.AlreadySettled(summary)
                }
            }
            val session = rawQuery(
                "select review_cycle_id,local_date,completed_focus_count from review_sessions where account_id=? and review_session_id=?",
                arrayOf(accountId, reviewSessionId),
            ).use { if (it.moveToFirst()) Session(it.getString(0), it.getString(1), it.getInt(2)) else null }
                ?: return ReviewSettlementResult.Failed
            check(session.completedFocusCount in 1..3)
            val cycleStatus = rawQuery("select status from review_cycles where account_id=? and review_cycle_id=?",
                arrayOf(accountId, session.cycleId)).use { check(it.moveToFirst()); it.getString(0) }
            check(cycleStatus in setOf("ACTIVE", "COMPLETED"))
            val savedFocusCount = rawQuery(
                "select coalesce(sum(r.completed_focus_count),0) from review_chapter_results r " +
                    "join review_chapters c on c.account_id=r.account_id and c.review_chapter_id=r.review_chapter_id " +
                    "where r.account_id=? and c.review_cycle_id=? and c.status='COMPLETED' and r.local_date=?",
                arrayOf(accountId, session.cycleId, session.localDate),
            ).use { it.moveToFirst(); it.getInt(0) }
            check(savedFocusCount == session.completedFocusCount)
            val remaining = rawQuery(
                "select rci.sentence_id,o.sentence_id,o.capability_type,o.target_id from review_chapters rc " +
                    "join review_chapter_items rci on rci.account_id=rc.account_id and rci.review_chapter_id=rc.review_chapter_id " +
                    "join review_queue_items qi on qi.account_id=rci.account_id and qi.queue_item_id=rci.queue_item_id " +
                    "left join learning_obligations o on o.account_id=qi.account_id and o.obligation_id=qi.obligation_id " +
                    "where rc.account_id=? and rc.review_cycle_id=? and rc.status='READY'",
                arrayOf(accountId, session.cycleId),
            ).use { cursor ->
                val pendingSentences = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    check(!cursor.isNull(1) && !cursor.isNull(2) && !cursor.isNull(3))
                    val sentenceId = cursor.getString(0)
                    check(sentenceId == cursor.getString(1))
                    if (reviewCapabilityState(accountId, sentenceId, cursor.getString(2), cursor.getString(3)).eligible) {
                        pendingSentences += sentenceId
                    }
                }
                pendingSentences.size
            }
            val cycleCompleted = remaining == 0
            check(cycleStatus != "COMPLETED" || cycleCompleted)
            if (cycleCompleted) {
                completeReviewCycle(accountId, session.cycleId, session.localDate)
            }
            val summary = ReviewResultSummary(
                completedFocusSentenceCount = session.completedFocusCount,
                remainingFocusSentenceCount = remaining,
                cycleCompleted = cycleCompleted,
                normalLearningDaysReset = cycleCompleted,
            )
            insertOrThrow("review_session_settlements", null, ContentValues().apply {
                put("account_id", accountId); put("review_session_id", reviewSessionId)
                put("command_id", commandId); put("summary", encode(summary))
            })
            check(ownerIsActive())
            setTransactionSuccessful()
            ReviewSettlementResult.Settled(summary)
        } finally {
            endTransaction()
        }
    }

    private fun encode(summary: ReviewResultSummary) = JSONObject()
        .put("completed", summary.completedFocusSentenceCount)
        .put("remaining", summary.remainingFocusSentenceCount)
        .put("cycleCompleted", summary.cycleCompleted)
        .put("daysReset", summary.normalLearningDaysReset)
        .toString()

    private fun decode(value: String) = JSONObject(value).let {
        ReviewResultSummary(
            it.getInt("completed"),
            it.getInt("remaining"),
            it.getBoolean("cycleCompleted"),
            it.getBoolean("daysReset"),
        )
    }

    private data class Session(val cycleId: String, val localDate: String, val completedFocusCount: Int)
}
