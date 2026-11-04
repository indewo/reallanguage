package com.reallanguage.app.feature.review.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.review.domain.ReviewAbility
import com.reallanguage.app.feature.review.domain.ReviewAbilityModule
import com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft
import com.reallanguage.app.feature.review.domain.ReviewAbilityStatus
import com.reallanguage.app.feature.review.domain.ReviewChapterCommitResult
import com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult
import com.reallanguage.app.feature.review.domain.ReviewChapterRepository
import com.reallanguage.app.feature.review.domain.ReviewChapterSnapshot
import com.reallanguage.app.feature.review.domain.ReviewContextSentence
import com.reallanguage.app.feature.review.domain.ReviewSourceType
import com.reallanguage.app.feature.review.domain.ReviewResultQualification
import com.reallanguage.app.feature.review.domain.isValidFor
import com.reallanguage.app.feature.review.domain.ReviewPatternEntryLease
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest
import org.json.JSONArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidReviewChapterRepository(
    context: Context,
    private val runtime: SessionRuntime,
    private val patternEntryLease: ReviewPatternEntryLease? = null,
    private val localDate: () -> String = { LocalDate.now().toString() },
) : ReviewChapterRepository {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId && runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    override suspend fun loadChapter(reviewChapterId: String): ReviewChapterLoadResult = withContext(Dispatchers.IO) {
        val accountId = ownerAccountId ?: return@withContext ReviewChapterLoadResult.Failed
        if (!ownerIsActive() || reviewChapterId.isBlank()) return@withContext ReviewChapterLoadResult.Failed
        try { FormationDatabase(applicationContext).use { helper ->
            val result = helper.readableDatabase.readChapter(accountId, reviewChapterId)
                ?.let(ReviewChapterLoadResult::Ready)
                ?: ReviewChapterLoadResult.Failed
            if (ownerIsActive()) result else ReviewChapterLoadResult.Failed
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ReviewChapterLoadResult.Failed }
    }

    override suspend fun commitChapter(
        reviewChapterId: String,
        commandId: String,
        results: List<ReviewAbilityResultDraft>,
    ): ReviewChapterCommitResult = withContext(Dispatchers.IO) {
        val accountId = ownerAccountId ?: return@withContext ReviewChapterCommitResult.Failed
        if (!ownerIsActive() || reviewChapterId.isBlank() || commandId.isBlank()) return@withContext ReviewChapterCommitResult.Failed
        try { FormationDatabase(applicationContext).use { helper ->
            // Match register/discard lock order: entry gate first, SQLite second.
            // A historical replay still reaches its original digest check even
            // if this optional lease has since ended.
            val result = patternEntryLease?.serialized {
                helper.writableDatabase.commit(accountId, reviewChapterId, commandId, results)
            } ?: helper.writableDatabase.commit(accountId, reviewChapterId, commandId, results)
            if (ownerIsActive()) result else ReviewChapterCommitResult.Failed
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ReviewChapterCommitResult.Failed }
    }

    private fun SQLiteDatabase.readChapter(
        accountId: String,
        reviewChapterId: String,
    ): ReviewChapterSnapshot? {
        val header = rawQuery(
            "select original_chapter_id,source_type,source_title,status from review_chapters where account_id=? and review_chapter_id=?",
            arrayOf(accountId, reviewChapterId),
        ).use { if (it.moveToFirst()) ChapterHeader(it.getString(0), it.getString(1), it.getString(2), it.getString(3)) else null }
            ?: return null
        if (header.status != "READY") return null
        val chapterNumber = rawQuery(
            "select chapter_order from chapters where account_id=? and chapter_id=?",
            arrayOf(accountId, header.originalChapterId),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                cursor.getLong(0).takeIf { it in 0 until Int.MAX_VALUE.toLong() }?.plus(1)?.toInt()
            } else null
        }
        val scene = rawQuery(
            "select unit_id,text_en,text_zh,role,sentence_order from fixed_sentences where account_id=? and chapter_id=? order by sentence_order",
            arrayOf(accountId, header.originalChapterId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(ReviewContextSentence(
            cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3) == "FOCUS", cursor.getInt(4),
        )) } }
        if (scene.isEmpty() || scene.none { it.isFocus }) return null
        val abilities = pendingRows(accountId, reviewChapterId).map { row ->
            val state = reviewCapabilityState(accountId, row.sentenceId, row.capability, row.targetId)
            ReviewAbility(
                row.queueItemId,
                row.capability.toModule(),
                when (state.status) {
                    "PENDING" -> ReviewAbilityStatus.NOT_STARTED
                    "LEARNING" -> ReviewAbilityStatus.LEARNING
                    "SELF_ASSESSED" -> ReviewAbilityStatus.TENTATIVE
                    "VERIFIED" -> ReviewAbilityStatus.MASTERED
                    else -> error("Unknown review capability status")
                },
                row.sentenceId,
                row.targetId,
                requiresReview = state.eligible,
            )
        }
        if (abilities.isEmpty()) return null
        return ReviewChapterSnapshot(
            reviewChapterId = reviewChapterId,
            originalChapterId = header.originalChapterId,
            sourceType = header.sourceType.toSourceType(),
            sourceTitle = header.sourceTitle,
            originalScene = scene.map { it.textEn },
            focusSentence = scene.first { it.isFocus }.textEn,
            abilities = abilities,
            originalChapterNumber = chapterNumber,
            originalSentences = scene.takeIf { lines ->
                lines.all { it.sentenceId.isNotBlank() && it.sentenceOrder >= 0 } &&
                    lines.map { it.sentenceId }.distinct().size == lines.size &&
                    lines.zipWithNext().all { (first, second) -> first.sentenceOrder < second.sentenceOrder }
            },
        )
    }

    private fun SQLiteDatabase.commit(
        accountId: String,
        reviewChapterId: String,
        commandId: String,
        results: List<ReviewAbilityResultDraft>,
    ): ReviewChapterCommitResult {
        beginTransaction()
        return try {
            check(ownerIsActive())
            val digest = requestDigest(reviewChapterId, results)
            rawQuery(
                "select review_chapter_id,local_date,request_digest from review_chapter_results where account_id=? and command_id=?",
                arrayOf(accountId, commandId),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    if (cursor.getString(0) != reviewChapterId || cursor.getString(2) != digest) return ReviewChapterCommitResult.Failed
                    val sessionId = sessionId(accountId, reviewChapterId, cursor.getString(1))
                        ?: return ReviewChapterCommitResult.Failed
                    val next = nextChapter(accountId, reviewChapterId,
                        (3 - completedOnDate(accountId, cursor.getString(1))).coerceAtLeast(0))
                    check(ownerIsActive())
                    setTransactionSuccessful()
                    return ReviewChapterCommitResult.AlreadyCommitted(next, sessionId)
                }
            }
            val chapter = readChapter(accountId, reviewChapterId) ?: return ReviewChapterCommitResult.Failed
            val pending = pendingRows(accountId, reviewChapterId)
            val resultMap = results.associateBy { it.abilityId }
            if (results.any { it.abilityId.isBlank() } ||
                resultMap.size != results.size || resultMap.keys != pending.mapTo(mutableSetOf()) { it.queueItemId }
            ) return ReviewChapterCommitResult.Failed
            if (pending.any { row ->
                !resultMap.getValue(row.queueItemId).isValidFor(row.capability.toModule())
            }) return ReviewChapterCommitResult.Failed
            val patternReceipts = pending.filter { it.capability == "PATTERN_SPEAKING" &&
                resultMap.getValue(it.queueItemId).qualification == ReviewResultQualification.INDEPENDENT }
                .map { requireNotNull(resultMap.getValue(it.queueItemId).patternReceipt) }
            if (patternReceipts.isNotEmpty()) {
                val lease = patternEntryLease ?: return ReviewChapterCommitResult.Failed
                if (!lease.isActiveFor(accountId, runtime.accountEpoch) || lease.reviewChapterId != reviewChapterId ||
                    patternReceipts.any { receipt ->
                        receipt.identity.accountId != accountId || receipt.identity.accountEpoch != runtime.accountEpoch ||
                            receipt.identity.entryId != lease.entryId || receipt.identity.reviewChapterId != reviewChapterId ||
                            receipt.identity.originalChapterId != chapter.originalChapterId ||
                            !lease.containsRegistered(receipt) || !matchesPatternReceiptQueue(receipt.identity) || !hasPatternReceiptCommitment(receipt)
                    }
                ) return ReviewChapterCommitResult.Failed
            }
            val cycleId = cycleId(accountId, reviewChapterId) ?: return ReviewChapterCommitResult.Failed
            val day = localDate()
            val sessionId = stableId("review-session", accountId, cycleId, day)
            insertWithOnConflict("review_sessions", null, ContentValues().apply {
                put("account_id", accountId); put("review_session_id", sessionId)
                put("review_cycle_id", cycleId); put("local_date", day); put("completed_focus_count", 0)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            val focusCount = pending.map { it.sentenceId }.distinct().size
            val completed = completedOnDate(accountId, day)
            if (focusCount <= 0 || completed + focusCount > 3) return ReviewChapterCommitResult.Failed

            // Supported writing and an explicitly unavailable natural pattern scene
            // are handled, not judged. Keep their state, counters and obligations.
            // The existing daily count measures fully handled sentences, not mastery.
            val independent = pending.filter { resultMap.getValue(it.queueItemId).qualification == ReviewResultQualification.INDEPENDENT }
            val patternRows = independent.filter { it.capability == "PATTERN_WRITING" || it.capability == "PATTERN_SPEAKING" }
            independent.filterNot { it in patternRows }.forEach { settleAbility(accountId, it, resultMap.getValue(it.queueItemId).passed) }
            patternRows.groupBy { row ->
                val fixed = target(accountId, row.targetId) ?: error("Missing fixed target")
                row.capability to (normalize(fixed.display) + "|" + normalize(fixed.explanation))
            }.forEach { (identity, rows) ->
                settlePattern(accountId, rows, identity.second, identity.first == "PATTERN_WRITING",
                    rows.all { resultMap.getValue(it.queueItemId).passed })
            }
            independent.forEach { row ->
                if (resultMap.getValue(row.queueItemId).passed) {
                    delete("correction_flags", "account_id=? and obligation_id=?", arrayOf(accountId, row.obligationId))
                } else {
                    insertWithOnConflict("correction_flags", null, ContentValues().apply {
                        put("account_id", accountId); put("obligation_id", row.obligationId)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
            execSQL(
                "update review_sessions set completed_focus_count=completed_focus_count+? where account_id=? and review_session_id=?",
                arrayOf<Any>(focusCount, accountId, sessionId),
            )
            execSQL(
                "update review_chapters set status='COMPLETED' where account_id=? and review_chapter_id=? and status='READY'",
                arrayOf(accountId, reviewChapterId),
            )
            insertOrThrow("review_chapter_results", null, ContentValues().apply {
                put("account_id", accountId); put("review_chapter_id", reviewChapterId)
                put("command_id", commandId); put("local_date", day)
                put("completed_focus_count", focusCount)
                put("request_digest", digest)
            })
            delete("review_working_state", "account_id=? and review_chapter_id=?", arrayOf(accountId, reviewChapterId))
            val next = nextChapter(accountId, reviewChapterId, (3 - completed - focusCount).coerceAtLeast(0))
            check(ownerIsActive())
            if (patternReceipts.isNotEmpty()) check(patternEntryLease?.isActiveFor(accountId, runtime.accountEpoch) == true)
            setTransactionSuccessful()
            ReviewChapterCommitResult.Committed(next, sessionId)
        } finally {
            endTransaction()
        }
    }

    private fun requestDigest(chapterId: String, results: List<ReviewAbilityResultDraft>): String {
        val values = JSONArray()
        results.sortedBy { it.abilityId }.forEach {
            // Keep existing independent command digests byte-compatible. Supported
            // work must never replay as an independent false (or vice versa).
            val result = JSONArray().put(it.abilityId).put(it.evidenceId).put(it.passed)
            if (it.qualification != ReviewResultQualification.INDEPENDENT) result.put(it.qualification.name)
            values.put(result)
        }
        val payload = JSONArray().put(chapterId).put(values).toString()
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun SQLiteDatabase.completedOnDate(accountId: String, day: String): Int = rawQuery(
        "select coalesce(sum(completed_focus_count),0) from review_sessions where account_id=? and local_date=?",
        arrayOf(accountId, day),
    ).use { it.moveToFirst(); it.getInt(0) }

    private fun SQLiteDatabase.pendingRows(accountId: String, reviewChapterId: String): List<AbilityRow> = rawQuery(
        "select qi.queue_item_id,o.obligation_id,o.sentence_id,o.capability_type,o.target_id " +
            "from review_chapter_items rci join review_queue_items qi on qi.account_id=rci.account_id and qi.queue_item_id=rci.queue_item_id " +
            "join learning_obligations o on o.account_id=qi.account_id and o.obligation_id=qi.obligation_id " +
            "where rci.account_id=? and rci.review_chapter_id=? order by qi.priority_rank,qi.occurrence_count,qi.entered_at,qi.queue_item_id",
        arrayOf(accountId, reviewChapterId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(AbilityRow(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4))) } }
        .filter { reviewCapabilityState(accountId, it.sentenceId, it.capability, it.targetId).eligible }

    private fun SQLiteDatabase.settleAbility(accountId: String, row: AbilityRow, passed: Boolean) {
        when (row.capability) {
            "FULL_WRITING", "FULL_SPEAKING", "SCENE_RESPONSE" -> {
                check(update("sentence_capabilities", ContentValues().apply {
                    put("status", if (passed) "VERIFIED" else "LEARNING")
                }, "account_id=? and sentence_id=? and capability_type=?",
                    arrayOf(accountId, row.sentenceId, row.capability)) == 1)
                if (passed) deleteObligation(accountId, row.obligationId)
            }
            "MEANING_WRITING" -> {
                val target = target(accountId, row.targetId) ?: error("Missing fixed target")
                execSQL(
                    "update vocabulary_items set status=?,verified_success_count=verified_success_count+? where account_id=? and normalized_form=? and semantic_sense_key=?",
                    arrayOf<Any>(if (passed) "VERIFIED" else "LEARNING", if (passed) 1 else 0, accountId, normalize(target.display), normalize(target.explanation)),
                )
                check(rawQuery("select changes()", null).use { it.moveToFirst(); it.getInt(0) } == 1)
                if (passed) deleteObligation(accountId, row.obligationId)
            }
            else -> error("Unknown review capability")
        }
    }

    private fun SQLiteDatabase.settlePattern(accountId: String, rows: List<AbilityRow>, key: String, writing: Boolean, passed: Boolean) {
        val streakColumn = if (writing) "writing_streak" else "speaking_streak"
        val statusColumn = if (writing) "writing_status" else "speaking_status"
        execSQL(
            "update pattern_items set $statusColumn=?, $streakColumn=case when ?=1 then min($streakColumn+1,3) else 0 end where account_id=? and semantic_pattern_key=?",
            arrayOf<Any>(if (passed) "VERIFIED" else "LEARNING", if (passed) 1 else 0, accountId, key),
        )
        val streak = rawQuery(
            "select $streakColumn from pattern_items where account_id=? and semantic_pattern_key=?",
            arrayOf(accountId, key),
        ).use { check(it.moveToFirst()); it.getInt(0) }
        if (streak >= 3) {
            rows.forEach { deleteObligation(accountId, it.obligationId) }
        }
        execSQL("update pattern_items set active_validation=case when writing_status='VERIFIED' and writing_streak>=3 and speaking_status='VERIFIED' and speaking_streak>=3 then 0 else 1 end where account_id=? and semantic_pattern_key=?", arrayOf(accountId, key))
    }

    private fun SQLiteDatabase.deleteObligation(accountId: String, obligationId: String) {
        delete("correction_flags", "account_id=? and obligation_id=?", arrayOf(accountId, obligationId))
        delete("learning_obligations", "account_id=? and obligation_id=?", arrayOf(accountId, obligationId))
    }

    private fun SQLiteDatabase.target(accountId: String, targetId: String): Target? = rawQuery(
        "select display,explanation_zh from target_selections where account_id=? and target_id=?",
        arrayOf(accountId, targetId),
    ).use { if (it.moveToFirst()) Target(it.getString(0), it.getString(1)) else null }

    private fun SQLiteDatabase.cycleId(accountId: String, reviewChapterId: String): String? = rawQuery(
        "select review_cycle_id from review_chapters where account_id=? and review_chapter_id=?",
        arrayOf(accountId, reviewChapterId),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun SQLiteDatabase.sessionId(accountId: String, reviewChapterId: String, date: String): String? =
        cycleId(accountId, reviewChapterId)?.let { stableId("review-session", accountId, it, date) }

    private fun SQLiteDatabase.nextChapter(accountId: String, currentId: String, focusLimit: Int): String? = rawQuery(
        "select rc2.review_chapter_id from review_chapters current join review_chapters rc2 on rc2.account_id=current.account_id and rc2.review_cycle_id=current.review_cycle_id " +
            "join review_chapter_items i on i.account_id=rc2.account_id and i.review_chapter_id=rc2.review_chapter_id " +
            "join review_queue_items q on q.account_id=i.account_id and q.queue_item_id=i.queue_item_id " +
            "where current.account_id=? and current.review_chapter_id=? and rc2.status='READY' " +
            "group by rc2.review_chapter_id " +
            "order by ${firstQueuedValue("priority_rank")},${firstQueuedValue("occurrence_count")},${firstQueuedValue("entered_at")},rc2.review_chapter_id",
        arrayOf(accountId, currentId),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        .firstOrNull { candidate ->
            val next = readChapter(accountId, candidate)
            next != null && next.abilities.map { it.sentenceId }.distinct().size in 1..focusLimit
        }

    private fun firstQueuedValue(column: String): String =
        "(select q2.$column from review_queue_items q2 join review_chapter_items i2 on i2.account_id=q2.account_id and i2.queue_item_id=q2.queue_item_id " +
            "where i2.account_id=rc2.account_id and i2.review_chapter_id=rc2.review_chapter_id " +
            "order by q2.priority_rank,q2.occurrence_count,q2.entered_at,q2.queue_item_id limit 1)"

    private fun String.toModule() = when (this) {
        "FULL_WRITING" -> ReviewAbilityModule.COMPLETE_WRITING
        "FULL_SPEAKING" -> ReviewAbilityModule.FULL_SENTENCE_SPEAKING
        "SCENE_RESPONSE" -> ReviewAbilityModule.SCENE_RESPONSE
        "MEANING_WRITING" -> ReviewAbilityModule.WORD_MEANING
        "PATTERN_WRITING" -> ReviewAbilityModule.PATTERN_WRITING
        "PATTERN_SPEAKING" -> ReviewAbilityModule.PATTERN_SPEAKING
        else -> error("Unknown review capability")
    }

    private fun String.toSourceType() = when (this) {
        "QUICK_RECORDING" -> ReviewSourceType.QUICK_RECORDING
        "AI_SCENE" -> ReviewSourceType.AI_SCENE
        else -> ReviewSourceType.DIARY
    }

    private fun normalize(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    private fun stableId(vararg parts: String) = UUID.nameUUIDFromBytes(parts.joinToString("").toByteArray()).toString()

    private data class ChapterHeader(val originalChapterId: String, val sourceType: String, val sourceTitle: String, val status: String)
    private data class AbilityRow(val queueItemId: String, val obligationId: String, val sentenceId: String, val capability: String, val targetId: String)
    private data class Target(val display: String, val explanation: String)
}
