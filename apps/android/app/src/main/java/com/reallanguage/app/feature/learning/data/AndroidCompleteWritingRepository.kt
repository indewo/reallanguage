package com.reallanguage.app.feature.learning.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.decodeSourceEvidence
import com.reallanguage.app.feature.formation.domain.isValidFor
import com.reallanguage.app.feature.formation.domain.isValidForTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.uniqueLiteralSourceEvidence
import com.reallanguage.app.feature.learning.domain.CompleteWritingRepository
import com.reallanguage.app.feature.learning.domain.CompleteWritingSaveResult
import com.reallanguage.app.feature.learning.domain.FinalWritingBatchResult
import com.reallanguage.app.feature.learning.domain.LockedWritingTarget
import com.reallanguage.app.feature.learning.domain.WritingTargetType
import com.reallanguage.app.feature.learning.domain.SentenceWritingResult
import com.reallanguage.app.feature.learning.domain.WritingTargetResult
import com.reallanguage.app.feature.learning.domain.WritingTargetSpan
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidLockedWritingTargetReader(
    context: Context,
    private val runtime: SessionRuntime,
) {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    /** A review ability reads its exact locked target; ordinary writing still reads every target. */
    fun readTarget(chapterId: String, sentenceId: String, targetId: String): List<LockedWritingTarget>? {
        if (chapterId.isBlank() || sentenceId.isBlank() || targetId.isBlank()) return null
        return read(chapterId, TargetScope(sentenceId, targetId))
    }

    fun read(chapterId: String): List<LockedWritingTarget>? = read(chapterId, null)

    private data class TargetScope(val sentenceId: String, val targetId: String)

    private fun read(chapterId: String, scope: TargetScope?): List<LockedWritingTarget>? {
        if (!ownerIsActive()) return null
        return try { FormationDatabase(applicationContext).use { helper ->
            val db = helper.readableDatabase
            db.beginTransactionNonExclusive()
            try {
            check(ownerIsActive())
            check(db.rawQuery("select 1 from chapters c join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id where c.account_id=? and c.chapter_id=? and s.role='FOCUS'", arrayOf(ownerAccountId, chapterId)).use { it.moveToFirst() })
            val scopedWhere = if (scope == null) "" else " and t.unit_id=? and t.target_id=?"
            val arguments = if (scope == null) arrayOf(ownerAccountId, chapterId)
                else arrayOf(ownerAccountId, chapterId, scope.sentenceId, scope.targetId)
            val targets = db.rawQuery(
                "select t.target_id,t.unit_id,t.type,t.display,s.role,s.text_en,t.source_evidence from target_selections t left join fixed_sentences s on s.account_id=t.account_id and s.chapter_id=t.chapter_id and s.unit_id=t.unit_id " +
                    "where t.account_id=? and t.chapter_id=?$scopedWhere and ($ACTIVE_FIXED_TARGET_PREDICATE) order by t.target_id",
                arguments,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        check(!cursor.isNull(4) && cursor.getString(4) == "FOCUS" && cursor.getString(3).isNotBlank())
                        val english = cursor.getString(5)
                        val legacy = cursor.isNull(6)
                        val evidence = if (legacy) uniqueLiteralSourceEvidence(english, cursor.getString(3))
                            else decodeSourceEvidence(JSONObject(cursor.getString(6)))
                        check(evidence != null && if (legacy) evidence.isValidFor(english) else evidence.isValidForTarget(
                            english, cursor.getString(3), LearningTargetType.valueOf(cursor.getString(2)),
                        ))
                        add(
                            LockedWritingTarget(
                                targetId = cursor.getString(0),
                                sentenceId = cursor.getString(1),
                                type = writingTargetType(cursor.getString(2)),
                                expectedForms = evidence.allowedForms,
                                sourceSpan = WritingTargetSpan(evidence.startUtf16, evidence.endUtf16, evidence.surfaceForm),
                            ),
                        )
                    }
                }
            }
            check(scope == null || targets.size == 1)
            check(ownerIsActive())
            db.setTransactionSuccessful()
            targets
            } finally { db.endTransaction() }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

internal fun writingTargetType(value: String): WritingTargetType = when (value) {
    "PATTERN" -> WritingTargetType.PATTERN
    "WORD", "FIXED_EXPRESSION" -> WritingTargetType.MEANING
    else -> error("Unsupported locked writing target type: $value")
}

class AndroidCompleteWritingRepository(
    context: Context,
    private val runtime: SessionRuntime,
    private val commandId: String = UUID.randomUUID().toString(),
) : CompleteWritingRepository {
    private val context = context.applicationContext
    private val accountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    // Token refresh keeps the epoch; logout and same-account login do not grant
    // this exited entry authority over the new session's final batch.
    private fun ownerIsActive() = accountId != null && runtime.accountId == accountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    override suspend fun loadFinalBatch(chapterId: String): FinalWritingBatchResult? = withContext(Dispatchers.IO) {
        val account = checkNotNull(accountId)
        check(ownerIsActive())
        FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            check(ownerIsActive())
            check(db.rawQuery("select 1 from chapters where account_id=? and chapter_id=? and status!='COMPLETED'", arrayOf(account, chapterId)).use { it.moveToFirst() })
            val qualification = db.rawQuery("select qualification from writing_batch_completions where account_id=? and chapter_id=?", arrayOf(account, chapterId)).use { if (it.moveToFirst()) it.getString(0) else null }
            val result = when (qualification) {
                null -> null
                "SUPPORTED" -> FinalWritingBatchResult.SupportedLearningOnly
                "INDEPENDENT" -> {
                    val rows = db.savedRows(account, chapterId)
                    FinalWritingBatchResult.Valid(rows.filter { it.ability == "WHOLE_WRITING" }.map { whole ->
                        SentenceWritingResult(whole.sentence, whole.passed, rows.filter { it.sentence == whole.sentence && it.ability != "WHOLE_WRITING" }.map {
                            WritingTargetResult(it.target, if (it.ability == "MEANING_WRITING") WritingTargetType.MEANING else WritingTargetType.PATTERN, it.passed)
                        })
                    }).also { db.validateResult(account, chapterId, it, allowAcceptedRemovedTargets = true) }
                }
                else -> error("Unknown writing qualification")
            }
            check(ownerIsActive())
            result
        }
    }

    override suspend fun saveFinalBatch(
        chapterId: String,
        result: FinalWritingBatchResult,
    ): CompleteWritingSaveResult = withContext(Dispatchers.IO) {
        val account = accountId ?: return@withContext CompleteWritingSaveResult.Failed
        try {
            check(ownerIsActive())
            FormationDatabase(context).use { helper -> helper.writableDatabase.transaction {
                check(ownerIsActive())
                check(rawQuery(
                    "select 1 from chapters where account_id=? and chapter_id=? and status!='COMPLETED'",
                    arrayOf(account, chapterId),
                ).use { it.moveToFirst() })
                val qualification = if (result is FinalWritingBatchResult.Valid) "INDEPENDENT" else "SUPPORTED"
                val existing = rawQuery(
                    "select qualification from writing_batch_completions where account_id=? and chapter_id=?",
                    arrayOf(account, chapterId),
                ).use { if (it.moveToFirst()) it.getString(0) else null }
                validateResult(account, chapterId, result, allowAcceptedRemovedTargets = existing != null)
                if (existing != null) {
                    // A retry may acknowledge the same accepted result, never replace it.
                    check(existing == qualification && savedRows(account, chapterId) == result.rows())
                    check(ownerIsActive())
                    return@transaction
                }
                check(rawQuery(
                    "select 1 from writing_batch_completions where account_id=? and command_id=?",
                    arrayOf(account, commandId),
                ).use { !it.moveToFirst() })
                check(savedRows(account, chapterId).isEmpty())
                if (result is FinalWritingBatchResult.Valid) {
                    result.sentenceResults.forEach { sentence ->
                        insertWritingResult(
                            account,
                            chapterId,
                            sentence.sentenceId,
                            "WHOLE_WRITING",
                            "",
                            sentence.wholeSentencePassed,
                        )
                        sentence.targetResults.forEach { target ->
                            insertWritingResult(
                                account,
                                chapterId,
                                sentence.sentenceId,
                                when (target.type) {
                                    WritingTargetType.MEANING -> "MEANING_WRITING"
                                    WritingTargetType.PATTERN -> "PATTERN_WRITING"
                                },
                                target.targetId,
                                target.passed,
                            )
                        }
                    }
                }
                insertOrThrow(
                    "writing_batch_completions",
                    null,
                    ContentValues().apply {
                        put("account_id", account)
                        put("chapter_id", chapterId)
                        put("command_id", commandId)
                        put("qualification", qualification)
                    },
                )
                check(ownerIsActive())
            } }
            check(ownerIsActive())
            CompleteWritingSaveResult.Saved
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CompleteWritingSaveResult.Failed }
    }

    private fun SQLiteDatabase.validateResult(account: String, chapter: String, result: FinalWritingBatchResult,
        allowAcceptedRemovedTargets: Boolean = false,
    ) {
        val focuses = rawQuery("select unit_id from fixed_sentences where account_id=? and chapter_id=? and role='FOCUS'", arrayOf(account, chapter)).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        check(focuses.isNotEmpty())
        if (result !is FinalWritingBatchResult.Valid) return
        check(result.sentenceResults.size == focuses.size && result.sentenceResults.map { it.sentenceId }.toSet() == focuses)
        val lockedTargets = rawQuery("select unit_id,target_id,type from target_selections where account_id=? and chapter_id=?", arrayOf(account, chapter)).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), writingTargetType(cursor.getString(2)))) }
        }
        val submitted = result.sentenceResults.flatMap { sentence -> sentence.targetResults.map { Triple(sentence.sentenceId, it.targetId, it.type) } }
        val removed = removedFixedPatternTargets(account, chapter)
        val activeTargets = lockedTargets.filterNot { it.third == WritingTargetType.PATTERN &&
            RemovedFixedPatternTarget(it.first, it.second) in removed }.toSet()
        val submittedSet = submitted.toSet()
        check(submittedSet.size == submitted.size)
        if (allowAcceptedRemovedTargets) {
            // Read/replay the exact previously accepted batch, including historical
            // sub-results. The caller still compares all stored values on replay.
            check(submittedSet.containsAll(activeTargets) && lockedTargets.containsAll(submittedSet))
        } else check(submittedSet == activeTargets)
    }

    private data class ResultRow(val sentence: String, val ability: String, val target: String, val passed: Boolean)

    private fun FinalWritingBatchResult.rows(): Set<ResultRow> = if (this !is FinalWritingBatchResult.Valid) emptySet() else sentenceResults.flatMap { sentence ->
        listOf(ResultRow(sentence.sentenceId, "WHOLE_WRITING", "", sentence.wholeSentencePassed)) + sentence.targetResults.map {
            ResultRow(sentence.sentenceId, if (it.type == WritingTargetType.MEANING) "MEANING_WRITING" else "PATTERN_WRITING", it.targetId, it.passed)
        }
    }.toSet()

    private fun SQLiteDatabase.savedRows(account: String, chapter: String): Set<ResultRow> = rawQuery(
        "select sentence_id,ability_type,target_id,passed from writing_result_drafts where account_id=? and chapter_id=?", arrayOf(account, chapter),
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(ResultRow(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3) == 1)) } }

    private fun SQLiteDatabase.insertWritingResult(
        accountId: String,
        chapterId: String,
        sentenceId: String,
        abilityType: String,
        targetId: String,
        passed: Boolean,
    ) {
        insertOrThrow(
            "writing_result_drafts",
            null,
            ContentValues().apply {
                put("account_id", accountId)
                put("chapter_id", chapterId)
                put("sentence_id", sentenceId)
                put("ability_type", abilityType)
                put("target_id", targetId)
                put("passed", if (passed) 1 else 0)
            },
        )
    }
}

private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}
