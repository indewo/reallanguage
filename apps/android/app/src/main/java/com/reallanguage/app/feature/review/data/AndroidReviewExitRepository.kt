package com.reallanguage.app.feature.review.data

import android.content.Context
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.review.domain.ReviewExitRepository
import com.reallanguage.app.feature.review.domain.ReviewExitResult
import com.reallanguage.app.feature.review.domain.ReviewPatternEntryLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

class AndroidReviewExitRepository(
    context: Context,
    private val runtime: SessionRuntime,
    private val patternEntryLease: ReviewPatternEntryLease? = null,
) : ReviewExitRepository {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId && runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    override suspend fun completedChapterCount(reviewChapterId: String): Int = withContext(Dispatchers.IO) {
        val accountId = ownerAccountId ?: return@withContext -1
        if (!ownerIsActive()) return@withContext -1
        try { FormationDatabase(applicationContext).use { helper ->
            check(helper.readableDatabase.rawQuery("select 1 from review_chapters where account_id=? and review_chapter_id=?", arrayOf(accountId, reviewChapterId)).use { it.moveToFirst() })
            val count = helper.readableDatabase.rawQuery(
                "select count(*) from review_chapter_results result join review_chapters current on current.account_id=result.account_id and current.review_chapter_id=result.review_chapter_id and current.review_cycle_id=(select review_cycle_id from review_chapters where account_id=? and review_chapter_id=?) where result.account_id=?",
                arrayOf(accountId, reviewChapterId, accountId),
            ).use { it.moveToFirst(); it.getInt(0) }
            if (ownerIsActive()) count else -1
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { -1 }
    }

    override suspend fun discardWorkingState(reviewChapterId: String): ReviewExitResult = withContext(Dispatchers.IO) {
        val accountId = ownerAccountId ?: return@withContext ReviewExitResult.Failed
        if (!ownerIsActive() || reviewChapterId.isBlank()) return@withContext ReviewExitResult.Failed
        val discard = {
            FormationDatabase(applicationContext).use { helper ->
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    ensureActive()
                    check(ownerIsActive())
                    val ready = db.rawQuery(
                        "select count(*) from review_chapters where account_id=? and review_chapter_id=? and status='READY'",
                        arrayOf(accountId, reviewChapterId),
                    ).use { it.moveToFirst(); it.getInt(0) == 1 }
                    check(ready)
                    val deleted = db.delete("review_working_state", "account_id=? and review_chapter_id=?",
                        arrayOf(accountId, reviewChapterId))
                    check(ownerIsActive())
                    ensureActive()
                    db.setTransactionSuccessful()
                    if (deleted > 0) ReviewExitResult.Discarded else ReviewExitResult.AlreadyDiscarded
                } finally { db.endTransaction() }
            }
        }
        try {
            val lease = patternEntryLease
            if (lease == null) discard() else lease.serialized {
                ensureActive()
                check(lease.isActiveFor(accountId, runtime.accountEpoch) && lease.reviewChapterId == reviewChapterId)
                // Register/commit/discard share the entry gate. Only a successful
                // transaction closes it, before ReturnHome can be delayed by UI.
                val result = discard()
                lease.revoke()
                result
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ReviewExitResult.Failed }
    }
}
