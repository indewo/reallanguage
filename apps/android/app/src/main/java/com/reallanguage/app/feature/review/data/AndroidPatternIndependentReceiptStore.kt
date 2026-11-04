package com.reallanguage.app.feature.review.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.review.domain.PatternIndependentReceipt
import com.reallanguage.app.feature.review.domain.PatternIndependentReceiptIdentity
import com.reallanguage.app.feature.review.domain.PatternIndependentReceiptStore
import com.reallanguage.app.feature.review.domain.ReviewPatternEntryLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

class AndroidPatternIndependentReceiptStore(
    context: Context,
    private val runtime: SessionRuntime,
    private val lease: ReviewPatternEntryLease,
    override val identity: PatternIndependentReceiptIdentity,
) : PatternIndependentReceiptStore {
    private val applicationContext = context.applicationContext
    override val generationMemory get() = lease.generationMemory
    override fun isActive(): Boolean = runtime.accessToken != null &&
        lease.isActiveFor(runtime.accountId, runtime.accountEpoch) && identity.accountId == lease.accountId &&
        identity.accountEpoch == lease.accountEpoch && identity.reviewChapterId == lease.reviewChapterId && identity.entryId == lease.entryId

    override suspend fun register(receipt: PatternIndependentReceipt, mayCommit: () -> Boolean): Boolean = withContext(Dispatchers.IO) {
        lease.serialized {
        ensureActive()
        if (!mayCommit() || !isActive() || receipt.identity != identity || !receipt.hasValidIndependentJudgment()) return@serialized false
        try { val registered = FormationDatabase(applicationContext).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                check(mayCommit() && isActive())
                check(db.matchesPatternReceiptQueue(identity))
                db.insertWithOnConflict("review_working_state", null, ContentValues().apply {
                    put("account_id", identity.accountId); put("review_chapter_id", identity.reviewChapterId)
                    put("ability_id", identity.abilityId); put("evidence_id", receipt.evidenceId)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
                check(db.hasPatternReceiptCommitment(receipt))
                check(mayCommit() && isActive())
                ensureActive()
                db.setTransactionSuccessful()
                true
            } finally { db.endTransaction() }
        }
            // SQL alone is not authority: a new entry, even with the same IDs,
            // cannot reconstruct this live receipt after its original lease ends.
            registered && mayCommit() && isActive() && lease.rememberRegistered(receipt)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        }
    }
}

/** Call inside the current review transaction; never infer a target from display text. */
internal fun SQLiteDatabase.matchesPatternReceiptQueue(identity: PatternIndependentReceiptIdentity): Boolean = rawQuery(
    "select rc.original_chapter_id,o.chapter_id,o.sentence_id,o.target_id,o.capability_type,t.type,t.unit_id,s.role " +
        "from review_chapters rc join review_chapter_items i on i.account_id=rc.account_id and i.review_chapter_id=rc.review_chapter_id " +
        "join review_queue_items q on q.account_id=i.account_id and q.queue_item_id=i.queue_item_id and q.review_cycle_id=rc.review_cycle_id " +
        "join learning_obligations o on o.account_id=q.account_id and o.obligation_id=q.obligation_id " +
        "join target_selections t on t.account_id=o.account_id and t.target_id=o.target_id and t.chapter_id=o.chapter_id " +
        "join fixed_sentences s on s.account_id=o.account_id and s.chapter_id=o.chapter_id and s.unit_id=o.sentence_id " +
        "where rc.account_id=? and rc.review_chapter_id=? and rc.status='READY' and i.queue_item_id=? " +
        "and q.original_chapter_id=rc.original_chapter_id and q.sentence_id=o.sentence_id and i.sentence_id=o.sentence_id and i.ability_type=o.capability_type",
    arrayOf(identity.accountId, identity.reviewChapterId, identity.abilityId),
).use { cursor ->
    cursor.moveToFirst() && cursor.getString(0) == identity.originalChapterId && cursor.getString(1) == identity.originalChapterId &&
        cursor.getString(2) == identity.sourceSentenceId && cursor.getString(3) == identity.targetId &&
        cursor.getString(4) == "PATTERN_SPEAKING" && cursor.getString(5) == "PATTERN" &&
        cursor.getString(6) == identity.sourceSentenceId && cursor.getString(7) == "FOCUS" && !cursor.moveToNext()
}

internal fun SQLiteDatabase.hasPatternReceiptCommitment(receipt: PatternIndependentReceipt): Boolean = rawQuery(
    "select evidence_id from review_working_state where account_id=? and review_chapter_id=? and ability_id=?",
    arrayOf(receipt.identity.accountId, receipt.identity.reviewChapterId, receipt.identity.abilityId),
).use { cursor -> cursor.moveToFirst() && cursor.getString(0) == receipt.evidenceId && !cursor.moveToNext() }
