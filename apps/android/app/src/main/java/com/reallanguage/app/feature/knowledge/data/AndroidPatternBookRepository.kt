package com.reallanguage.app.feature.knowledge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.decodeRelatedVocabularySnapshot
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.PatternBookRepository
import com.reallanguage.app.feature.knowledge.domain.PatternExample
import com.reallanguage.app.feature.knowledge.domain.PatternLoadResult
import com.reallanguage.app.feature.knowledge.domain.PatternRecord
import com.reallanguage.app.feature.knowledge.domain.PatternRelatedVocabulary
import com.reallanguage.app.feature.knowledge.domain.PatternRemovalRepository
import com.reallanguage.app.feature.knowledge.domain.PatternRemovalResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class AndroidPatternBookRepository(
    context: Context,
    private val runtime: SessionRuntime,
) : PatternBookRepository {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    override suspend fun loadAllPatterns(): PatternLoadResult = withContext(Dispatchers.IO) {
        val accountId = ownerAccountId ?: return@withContext PatternLoadResult.Failed
        if (!ownerIsActive()) return@withContext PatternLoadResult.Failed
        try { FormationDatabase(applicationContext).use { helper ->
            val db = helper.readableDatabase
            val patterns = db.rawQuery(
                """
                select p.item_id,p.structure_en,p.meaning_zh,p.writing_status,p.speaking_status,
                  p.writing_streak,p.speaking_streak,p.representative_scene,p.natural_revalidation,
                  (select count(*) from pattern_occurrences o where o.account_id=p.account_id and o.item_id=p.item_id),
                  p.semantic_pattern_key,p.related_vocabulary_snapshot
                from pattern_items p where p.account_id=? order by p.rowid
                """.trimIndent(),
                arrayOf(accountId),
            ).use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    val itemId = cursor.getString(0)
                    val examples = db.rawQuery(
                        "select text_en,text_zh,pronunciation_text from pattern_examples where account_id=? and item_id=? order by example_order",
                        arrayOf(accountId, itemId),
                    ).use { examplesCursor -> buildList {
                        while (examplesCursor.moveToNext()) add(PatternExample(examplesCursor.getString(0), examplesCursor.getString(1), examplesCursor.getString(2)))
                    } }
                    val writingStreak = cursor.getInt(5)
                    val speakingStreak = cursor.getInt(6)
                    add(
                        PatternRecord(
                            patternId = itemId,
                            structureEn = cursor.getString(1),
                            functionZh = cursor.getString(2),
                            writingStatus = status(cursor.getString(3)),
                            speakingStatus = status(cursor.getString(4)),
                            consecutiveSuccessCount = maxOf(writingStreak, speakingStreak),
                            occurrenceCount = cursor.getInt(9),
                            representativeScene = cursor.getString(7),
                            examples = examples,
                            writingConsecutiveSuccessCount = writingStreak,
                            speakingConsecutiveSuccessCount = speakingStreak,
                            naturalRevalidation = cursor.getInt(8) == 1,
                            structureParts = db.readStoredStructureParts(accountId, itemId, cursor.getString(10)),
                            relatedVocabulary = if (cursor.isNull(11)) null else db.readRelatedVocabulary(accountId, cursor.getString(11)),
                        ),
                    )
                }
            } }
            if (ownerIsActive()) PatternLoadResult.Ready(patterns) else PatternLoadResult.Failed
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PatternLoadResult.Failed }
    }

    private fun SQLiteDatabase.readRelatedVocabulary(accountId: String, raw: String): List<PatternRelatedVocabulary> {
        val snapshot = decodeRelatedVocabularySnapshot(raw)
        // The envelope's exact sentence is retained after archive. An internally valid
        // envelope from a different account (or a missing sentence) is not our evidence.
        rawQuery("select text_en from fixed_sentences where account_id=? and unit_id=?", arrayOf(accountId, snapshot.unitId)).use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == snapshot.sourceTextEn && !cursor.moveToNext())
        }
        return snapshot.orderedItems().map { PatternRelatedVocabulary(it.display, it.senseZh) }
    }

    private fun SQLiteDatabase.readStoredStructureParts(accountId: String, itemId: String, semanticKey: String): List<String> {
        val alternatives = rawQuery(
            """
            select t.display,t.explanation_zh,t.structure_parts
            from pattern_occurrences o
            join chapters c on c.account_id=o.account_id and c.chapter_id=o.chapter_id
            join target_selections t on t.account_id=o.account_id and t.chapter_id=o.chapter_id and t.unit_id=o.sentence_id
            where o.account_id=? and o.item_id=? and c.status='COMPLETED' and t.type='PATTERN'
            order by o.rowid,t.rowid
            """.trimIndent(), arrayOf(accountId, itemId),
        ).use { cursor -> buildSet {
            while (cursor.moveToNext()) {
                val key = normalizePatternIdentity(cursor.getString(0)) + "|" + normalizePatternIdentity(cursor.getString(1))
                if (key == semanticKey) {
                    val saved = cursor.getString(2)
                    if (!saved.isNullOrBlank()) {
                        val parts = saved.split('\u001f')
                        if (parts.all(String::isNotBlank)) add(parts)
                    }
                }
            }
        } }
        // Older pattern rows do not record the representative source target.
        // Restore only agreeing saved evidence, never pick an arbitrary variant.
        return alternatives.singleOrNull().orEmpty()
    }

    private fun normalizePatternIdentity(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun status(value: String) = when (value) {
        "PENDING" -> KnowledgeStatus.NOT_STARTED
        "LEARNING" -> KnowledgeStatus.LEARNING
        "SELF_ASSESSED" -> KnowledgeStatus.TENTATIVE
        "VERIFIED" -> KnowledgeStatus.MASTERED
        else -> error("Unknown saved pattern status")
    }
}

class AndroidPatternRemovalRepository(
    context: Context,
    runtime: SessionRuntime,
) : PatternRemovalRepository {
    private val applicationContext = context.applicationContext
    private val owner = PatternRemovalOwner(runtime)

    override suspend fun removeFromPatternBook(patternId: String, commandId: String): PatternRemovalResult = withContext(Dispatchers.IO) {
        val accountId = owner.accountId ?: return@withContext PatternRemovalResult.Failed
        if (!owner.isAuthorized() || patternId.isBlank() || commandId.isBlank()) return@withContext PatternRemovalResult.Failed
        try { FormationDatabase(applicationContext).use { helper ->
            val result = helper.writableDatabase.removePatternBookKnowledge(accountId, patternId, commandId, owner::isAuthorized)
            if (owner.isAuthorized()) result else PatternRemovalResult.Failed
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PatternRemovalResult.Failed }
    }
}

/** Captured operation owner; the SQL boundary uses the same authority as its repository. */
internal class PatternRemovalOwner(private val runtime: SessionRuntime) {
    val accountId = runtime.accountId
    private val accountEpoch = runtime.accountEpoch
    fun isAuthorized() = accountId != null && runtime.accountId == accountId &&
        runtime.accountEpoch == accountEpoch && runtime.accessToken != null
}

/** Executes only local removal; callers supply their captured owner, never a page preference. */
internal fun SQLiteDatabase.removePatternBookKnowledge(
    accountId: String,
    patternId: String,
    commandId: String,
    authorized: () -> Boolean,
): PatternRemovalResult {
    check(authorized())
    beginTransaction()
    return try {
        check(authorized())
        rawQuery("select pattern_id from pattern_removal_commands where account_id=? and command_id=?", arrayOf(accountId, commandId)).use {
            if (it.moveToFirst()) {
                val replay = if (it.getString(0) == patternId) PatternRemovalResult.AlreadyRemoved else PatternRemovalResult.Failed
                check(authorized())
                setTransactionSuccessful()
                return replay
            }
        }
        check(authorized())
        val patternKey = rawQuery("select semantic_pattern_key from pattern_items where account_id=? and item_id=?", arrayOf(accountId, patternId)).use {
            if (!it.moveToFirst()) {
                check(authorized())
                setTransactionSuccessful()
                return PatternRemovalResult.AlreadyRemoved
            }
            it.getString(0)
        }
        check(authorized())
        // Apply exactly the same semantic normalization as chapter archive. SQLite's
        // lower(trim(...)) neither folds repeated whitespace nor all Unicode case.
        val targetIds = rawQuery(
            "select target_id,display,explanation_zh from target_selections where account_id=? and type='PATTERN'",
            arrayOf(accountId),
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val key = normalizePatternRemovalIdentity(cursor.getString(1)) + "|" + normalizePatternRemovalIdentity(cursor.getString(2))
                if (key == patternKey) add(cursor.getString(0))
            }
        } }
        check(authorized())
        targetIds.forEach { targetId ->
            check(authorized())
            execSQL(
                "delete from review_chapter_items where account_id=? and queue_item_id in (select q.queue_item_id from review_queue_items q join learning_obligations o on o.account_id=q.account_id and o.obligation_id=q.obligation_id where q.account_id=? and o.target_id=?)",
                arrayOf(accountId, accountId, targetId),
            )
            execSQL(
                "delete from review_queue_items where account_id=? and obligation_id in (select obligation_id from learning_obligations where account_id=? and target_id=?)",
                arrayOf(accountId, accountId, targetId),
            )
            execSQL(
                "delete from correction_flags where account_id=? and obligation_id in (select obligation_id from learning_obligations where account_id=? and target_id=?)",
                arrayOf(accountId, accountId, targetId),
            )
            delete("learning_obligations", "account_id=? and target_id=?", arrayOf(accountId, targetId))
            check(authorized())
        }
        check(authorized())
        delete("pattern_examples", "account_id=? and item_id=?", arrayOf(accountId, patternId))
        delete("pattern_occurrences", "account_id=? and item_id=?", arrayOf(accountId, patternId))
        delete("pattern_items", "account_id=? and item_id=?", arrayOf(accountId, patternId))
        check(authorized())
        insertOrThrow("pattern_removal_commands", null, ContentValues().apply {
            put("account_id", accountId); put("command_id", commandId); put("pattern_id", patternId)
        })
        check(authorized())
        setTransactionSuccessful()
        PatternRemovalResult.Removed
    } finally {
        endTransaction()
    }
}

private fun normalizePatternRemovalIdentity(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
