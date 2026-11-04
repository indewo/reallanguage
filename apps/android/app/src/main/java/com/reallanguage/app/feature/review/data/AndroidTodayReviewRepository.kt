package com.reallanguage.app.feature.review.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.review.domain.ReviewChapterSummary
import com.reallanguage.app.feature.review.domain.ReviewQueueResult
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import com.reallanguage.app.feature.review.domain.TodayReviewRepository
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Builds and reads the account-isolated, on-device review-cycle snapshot. */
class AndroidTodayReviewRepository(
    context: Context,
    private val runtime: SessionRuntime,
    private val localDate: () -> String = { LocalDate.now().toString() },
) : TodayReviewRepository {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId && runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    suspend fun hasDueReview(): Boolean = withContext(Dispatchers.IO) {
        val accountId = ownerAccountId ?: return@withContext false
        if (!ownerIsActive()) return@withContext false
        try { FormationDatabase(applicationContext).use { helper ->
            val db = helper.writableDatabase
            // Reconcile the same snapshot used by the list. Raw obligation counts
            // include shared knowledge already verified elsewhere.
            db.loadQueue(accountId, 3)
            ownerIsActive() && db.activeCycle(accountId) != null
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    override suspend fun loadTodayQueue(maxFocusSentences: Int): ReviewQueueResult = withContext(Dispatchers.IO) {
        val accountId = ownerAccountId ?: return@withContext ReviewQueueResult.Failed
        if (!ownerIsActive() || maxFocusSentences !in 1..3) return@withContext ReviewQueueResult.Failed
        try { FormationDatabase(applicationContext).use { helper ->
            val result = helper.writableDatabase.loadQueue(accountId, maxFocusSentences)
            if (ownerIsActive()) result else ReviewQueueResult.Failed
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ReviewQueueResult.Failed }
    }

    private fun SQLiteDatabase.loadQueue(
        accountId: String,
        maxFocusSentences: Int,
    ): ReviewQueueResult {
        beginTransaction()
        return try {
            check(ownerIsActive())
            val cycleId = activeCycle(accountId) ?: run {
                if (normalLearningDayCount(accountId) < 3) {
                    check(ownerIsActive())
                    setTransactionSuccessful()
                    return ReviewQueueResult.Ready(emptyList())
                }
                createCycle(accountId)
            }
            materializeQueue(accountId, cycleId)
            val pending = rawQuery(
                "select count(*) from review_chapters rc join review_chapter_items rci " +
                    "on rci.account_id=rc.account_id and rci.review_chapter_id=rc.review_chapter_id " +
                    "where rc.account_id=? and rc.review_cycle_id=? and rc.status='READY'",
                arrayOf(accountId, cycleId),
            ).use { it.moveToFirst(); it.getInt(0) }
            if (pending == 0) {
                // No chapter, score, result or learning-day credit is fabricated.
                // A cycle with no eligible work must not strand the user on an
                // empty page. Keep original obligations and completed evidence.
                completeReviewCycle(accountId, cycleId, localDate())
                check(ownerIsActive())
                setTransactionSuccessful()
                return ReviewQueueResult.Ready(emptyList())
            }
            val completedToday = rawQuery(
                "select coalesce(sum(completed_focus_count),0) from review_sessions where account_id=? and local_date=?",
                arrayOf(accountId, localDate()),
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val available = (maxFocusSentences - completedToday).coerceAtLeast(0)
            val chapters = if (available == 0) emptyList() else readSummaries(accountId, cycleId, available)
            check(ownerIsActive())
            setTransactionSuccessful()
            ReviewQueueResult.Ready(chapters, deferredByDailyLimit = chapters.isEmpty() && completedToday > 0)
        } finally {
            endTransaction()
        }
    }

    private fun SQLiteDatabase.activeCycle(accountId: String): String? = rawQuery(
        "select review_cycle_id from review_cycles where account_id=? and status='ACTIVE' order by started_local_date,review_cycle_id limit 1",
        arrayOf(accountId),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun SQLiteDatabase.normalLearningDayCount(accountId: String): Int = rawQuery(
        "select count(*) from normal_learning_days where account_id=?",
        arrayOf(accountId),
    ).use { it.moveToFirst(); it.getInt(0) }

    private fun SQLiteDatabase.createCycle(accountId: String): String {
        val seed = rawQuery(
            "select local_date from normal_learning_days where account_id=? order by local_date limit 3",
            arrayOf(accountId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        check(seed.size == 3)
        val cycleId = stableId("review-cycle", accountId, seed.joinToString("|"))
        insertOrThrow("review_cycles", null, ContentValues().apply {
            put("account_id", accountId)
            put("review_cycle_id", cycleId)
            put("status", "ACTIVE")
            put("started_local_date", localDate())
        })
        return cycleId
    }

    private fun SQLiteDatabase.materializeQueue(accountId: String, cycleId: String) {
        rawQuery(
            "select o.rowid,o.obligation_id,o.chapter_id,o.sentence_id,o.capability_type,c.flow_id,f.source_type,f.source_title,o.target_id " +
                "from learning_obligations o join chapters c on c.account_id=o.account_id and c.chapter_id=o.chapter_id " +
                "join formation_flows f on f.account_id=c.account_id and f.flow_id=c.flow_id " +
                "where o.account_id=? and c.status='COMPLETED' order by o.rowid,o.obligation_id",
            arrayOf(accountId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val enteredAt = cursor.getLong(0)
                val obligationId = cursor.getString(1)
                val chapterId = cursor.getString(2)
                val sentenceId = cursor.getString(3)
                val abilityType = cursor.getString(4)
                val sourceType = cursor.getString(6)
                val sourceTitle = cursor.getString(7)
                val queueItemId = stableId("review-item", cycleId, obligationId)
                val reviewChapterId = stableId("review-chapter", cycleId, chapterId)
                val capabilityState = reviewCapabilityState(accountId, sentenceId, abilityType, cursor.getString(8))
                if (!capabilityState.eligible) {
                    delete("review_chapter_items", "account_id=? and queue_item_id=?", arrayOf(accountId, queueItemId))
                    delete("review_queue_items", "account_id=? and queue_item_id=?", arrayOf(accountId, queueItemId))
                    continue
                }
                insertWithOnConflict("review_queue_items", null, ContentValues().apply {
                    put("account_id", accountId); put("review_cycle_id", cycleId)
                    put("queue_item_id", queueItemId); put("obligation_id", obligationId)
                    put("original_chapter_id", chapterId); put("sentence_id", sentenceId)
                    put("priority_rank", capabilityState.priority); put("entered_at", enteredAt)
                    put("occurrence_count", capabilityState.occurrenceCount)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                // Legacy rows have no occurrence snapshot. Capture once at first current
                // read, without inventing their historical count or changing entry order.
                update("review_queue_items", ContentValues().apply {
                    put("priority_rank", capabilityState.priority); put("occurrence_count", capabilityState.occurrenceCount)
                }, "account_id=? and queue_item_id=? and occurrence_count is null", arrayOf(accountId, queueItemId))
                insertWithOnConflict("review_chapters", null, ContentValues().apply {
                    put("account_id", accountId); put("review_cycle_id", cycleId)
                    put("review_chapter_id", reviewChapterId); put("original_chapter_id", chapterId)
                    put("source_type", sourceType); put("source_title", sourceTitle)
                    put("status", "READY")
                }, SQLiteDatabase.CONFLICT_IGNORE)
                insertWithOnConflict("review_chapter_items", null, ContentValues().apply {
                    put("account_id", accountId); put("review_chapter_id", reviewChapterId)
                    put("queue_item_id", queueItemId); put("sentence_id", sentenceId)
                    put("ability_type", abilityType)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    private fun SQLiteDatabase.readSummaries(
        accountId: String,
        cycleId: String,
        focusLimit: Int,
    ): List<ReviewChapterSummary> = rawQuery(
        "select rc.review_chapter_id,rc.original_chapter_id,rc.source_type,rc.source_title,fs.text_en,rci.sentence_id " +
            "from review_chapters rc join review_chapter_items rci on rci.account_id=rc.account_id and rci.review_chapter_id=rc.review_chapter_id " +
            "join review_queue_items qi on qi.account_id=rci.account_id and qi.queue_item_id=rci.queue_item_id " +
            "join fixed_sentences fs on fs.account_id=rci.account_id and fs.chapter_id=rc.original_chapter_id and fs.unit_id=rci.sentence_id " +
            "join learning_obligations o on o.account_id=qi.account_id and o.obligation_id=qi.obligation_id " +
            "where rc.account_id=? and rc.review_cycle_id=? and rc.status='READY' " +
            "order by qi.priority_rank,qi.occurrence_count,qi.entered_at,rc.review_chapter_id",
        arrayOf(accountId, cycleId),
    ).use { cursor ->
        val result = mutableListOf<ReviewChapterSummary>()
        val chapterRows = linkedMapOf<String, MutableList<List<String>>>()
        while (cursor.moveToNext()) {
            chapterRows.getOrPut(cursor.getString(0)) { mutableListOf() }.add(List(6) { cursor.getString(it) })
        }
        var used = 0
        for (rows in chapterRows.values) {
            val row = rows.first()
            val focusCount = rows.map { it[5] }.distinct().size
            if (focusCount <= 0 || used + focusCount > focusLimit) continue
            result += ReviewChapterSummary(
                reviewChapterId = row[0],
                originalChapterId = row[1],
                sourceType = row[2].toReviewSourceType(),
                sourceTitle = row[3],
                focusSentence = row[4],
                focusSentenceCount = focusCount,
                pendingAbilityCount = rows.size,
            )
            used += focusCount
            if (used == focusLimit) break
        }
        result
    }

    private fun stableId(vararg parts: String): String =
        UUID.nameUUIDFromBytes(parts.joinToString("").toByteArray()).toString()

    private fun String.toReviewSourceType(): ReviewSourceType = when (this) {
        "QUICK_RECORDING" -> ReviewSourceType.QUICK_RECORDING
        "AI_SCENE" -> ReviewSourceType.AI_SCENE
        else -> ReviewSourceType.DIARY
    }
}
