package com.reallanguage.app.feature.learning.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.ChapterAbandonRepository
import com.reallanguage.app.feature.learning.domain.ChapterAbandonResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidChapterAbandonRepository(
    context: Context,
    private val runtime: SessionRuntime,
) : ChapterAbandonRepository {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null

    override suspend fun abandonUnarchivedChapter(
        chapterId: String,
        commandId: String,
    ): ChapterAbandonResult = withContext(Dispatchers.IO) {
        val accountId = ownerAccountId ?: return@withContext ChapterAbandonResult.Failed
        if (!ownerIsActive() || chapterId.isBlank() || commandId.isBlank()) return@withContext ChapterAbandonResult.Failed
        try {
            val result = FormationDatabase(applicationContext).use { it.writableDatabase.abandon(accountId, chapterId, commandId) }
            if (ownerIsActive()) result else ChapterAbandonResult.Failed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ChapterAbandonResult.Failed }
    }

    private fun SQLiteDatabase.abandon(
        accountId: String,
        chapterId: String,
        commandId: String,
    ): ChapterAbandonResult {
        beginTransaction()
        return try {
            check(ownerIsActive())
            readCommand(accountId, commandId, chapterId)?.let {
                setTransactionSuccessful()
                return it
            }
            val chapter = rawQuery(
                "select flow_id,status from chapters where account_id=? and chapter_id=?",
                arrayOf(accountId, chapterId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return ChapterAbandonResult.Failed
                ChapterRow(cursor.getString(0), cursor.getString(1))
            }
            if (chapter.status == "COMPLETED" || count(
                    "select count(*) from chapter_archives where account_id=? and chapter_id=?",
                    accountId, chapterId,
                ) > 0
            ) return ChapterAbandonResult.AlreadyArchived
            if (hasFormalContribution(accountId, chapterId)) return ChapterAbandonResult.Failed

            clearWorkingState(accountId, chapterId)
            delete("target_selections", "account_id=? and chapter_id=?", arrayOf(accountId, chapterId))
            delete("fixed_sentences", "account_id=? and chapter_id=?", arrayOf(accountId, chapterId))
            delete("chapters", "account_id=? and chapter_id=?", arrayOf(accountId, chapterId))

            val nextChapterId = rawQuery(
                "select chapter_id from chapters where account_id=? and flow_id=? and status='NOT_STARTED' order by chapter_order limit 1",
                arrayOf(accountId, chapter.flowId),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val hasRemaining = nextChapterId != null
            if (nextChapterId == null) {
                delete("fix_commands", "account_id=? and first_chapter_id=?", arrayOf(accountId, chapterId))
                if (count("select count(*) from chapters where account_id=? and flow_id=?", accountId, chapter.flowId) == 0) {
                    delete("fixed_source_payloads", "account_id=? and flow_id=?", arrayOf(accountId, chapter.flowId))
                    check(count("select count(*) from fixed_source_payloads where account_id=? and flow_id=?", accountId, chapter.flowId) == 0)
                    delete("formation_flows", "account_id=? and flow_id=?", arrayOf(accountId, chapter.flowId))
                }
            } else {
                update(
                    "fix_commands",
                    ContentValues().apply { put("first_chapter_id", nextChapterId) },
                    "account_id=? and first_chapter_id=?",
                    arrayOf(accountId, chapterId),
                )
            }
            insertOrThrow("chapter_abandon_commands", null, ContentValues().apply {
                put("account_id", accountId); put("command_id", commandId)
                put("chapter_id", chapterId)
                put("source_id", chapter.flowId); put("has_remaining", if (hasRemaining) 1 else 0)
            })
            check(ownerIsActive())
            setTransactionSuccessful()
            ChapterAbandonResult.Abandoned(hasRemaining, chapter.flowId.takeIf { hasRemaining })
        } finally {
            endTransaction()
        }
    }

    private fun SQLiteDatabase.readCommand(accountId: String, commandId: String, chapterId: String): ChapterAbandonResult? = rawQuery(
        "select source_id,has_remaining,chapter_id from chapter_abandon_commands where account_id=? and command_id=?",
        arrayOf(accountId, commandId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else {
            // A legacy command has no verifiable target. Never replay it for an arbitrary chapter.
            if (cursor.isNull(2) || cursor.getString(2) != chapterId) return@use ChapterAbandonResult.Failed
            val remaining = cursor.getInt(1) == 1
            ChapterAbandonResult.Abandoned(remaining, cursor.getString(0).takeIf { remaining })
        }
    }

    private fun SQLiteDatabase.hasFormalContribution(accountId: String, chapterId: String): Boolean =
        count("select count(*) from learning_obligations where account_id=? and chapter_id=?", accountId, chapterId) > 0 ||
            count("select count(*) from vocabulary_occurrences where account_id=? and chapter_id=?", accountId, chapterId) > 0 ||
            count("select count(*) from pattern_occurrences where account_id=? and chapter_id=?", accountId, chapterId) > 0 ||
            count(
                "select count(*) from sentence_capabilities where account_id=? and sentence_id in " +
                    "(select unit_id from fixed_sentences where account_id=? and chapter_id=?)",
                accountId, accountId, chapterId,
            ) > 0

    private fun SQLiteDatabase.clearWorkingState(accountId: String, chapterId: String) {
        listOf(
            "learning_steps", "writing_result_drafts", "writing_batch_completions",
            "full_speaking_result_drafts", "scene_response_result_drafts",
            "scene_response_tasks", "learning_checkpoints", "learning_explanation_cache", "learning_module_progress", "learning_self_assessments",
            "target_vocabulary_details",
            "target_pattern_details",
        ).forEach { delete(it, "account_id=? and chapter_id=?", arrayOf(accountId, chapterId)) }
    }

    private fun SQLiteDatabase.count(sql: String, vararg args: String) = rawQuery(sql, args).use {
        it.moveToFirst()
        it.getInt(0)
    }

    private data class ChapterRow(val flowId: String, val status: String)
}
