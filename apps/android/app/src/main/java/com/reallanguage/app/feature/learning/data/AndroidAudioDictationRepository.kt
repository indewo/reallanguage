package com.reallanguage.app.feature.learning.data

import android.content.Context
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionRepository
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class AndroidAudioDictationCompletionRepository(
    private val runtime: SessionRuntime,
    private val chapterId: String,
    private val commandId: String,
    private val stepStore: LearningStepStore,
    private val isCorrection: Boolean = false,
) : AudioDictationCompletionRepository {
    private val ownerAccountId = runtime.accountId
    private val ownerAccountEpoch = runtime.accountEpoch
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId &&
        runtime.accountEpoch == ownerAccountEpoch && runtime.accessToken != null
    override suspend fun complete(sentenceId: String): AudioDictationCompletionResult {
        val accountId = ownerAccountId ?: return AudioDictationCompletionResult.Failed
        if (!ownerIsActive()) return AudioDictationCompletionResult.Failed
        return if (
            withContext(Dispatchers.IO) { ownerIsActive() && stepStore.completeWhileAuthorized(
                accountId,
                chapterId,
                sentenceId,
                if (isCorrection) "READ_WRITE_CORRECTION" else "AUDIO_DICTATION",
                commandId,
            ) { ownerIsActive() } } && ownerIsActive()
        ) {
            AudioDictationCompletionResult.Saved
        } else {
            AudioDictationCompletionResult.Failed
        }
    }
}

class AndroidLockedHintReader(
    context: Context,
    private val runtime: SessionRuntime,
) {
    private val applicationContext = context.applicationContext
    private val ownerAccountId = runtime.accountId
    private fun ownerIsActive() = ownerAccountId != null && runtime.accountId == ownerAccountId && runtime.accessToken != null

    fun read(chapterId: String, sentenceId: String): List<String>? {
        if (!ownerIsActive()) return null
        return try { FormationDatabase(applicationContext).use { helper ->
            val db = helper.readableDatabase
            db.beginTransactionNonExclusive()
            try {
            check(db.rawQuery("select 1 from chapters c join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id where c.account_id=? and c.chapter_id=? and s.unit_id=? and s.role='FOCUS'", arrayOf(ownerAccountId, chapterId, sentenceId)).use { it.moveToFirst() })
            val hints = db.rawQuery(
                "select display,explanation_zh from target_selections where account_id=? and chapter_id=? and unit_id=? order by target_id",
                arrayOf(ownerAccountId, chapterId, sentenceId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        check(cursor.getString(0).isNotBlank() && cursor.getString(1).isNotBlank())
                        add("${cursor.getString(0)}：${cursor.getString(1)}")
                    }
                }
            }
            check(ownerIsActive())
            db.setTransactionSuccessful()
            hints
            } finally { db.endTransaction() }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}
