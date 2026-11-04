package com.reallanguage.app.feature.learning.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.CheckpointSaveResult
import com.reallanguage.app.feature.learning.domain.LearningCheckpointRepository
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidLearningCheckpointRepository private constructor(
    context: Context,
    private val runtime: SessionRuntime,
    private val sessionBound: Boolean,
    private val boundOwner: SessionOwner?,
) : LearningCheckpointRepository {
    constructor(context: Context, runtime: SessionRuntime) : this(context, runtime, false, null)
    private val context = context.applicationContext
    private data class SessionOwner(val accountId: String, val epoch: Long)

    /** A graph may be shared across logins; a page must keep its original authorization. */
    fun forCurrentSession(): AndroidLearningCheckpointRepository =
        AndroidLearningCheckpointRepository(context, runtime, true, captureOwner())

    private fun captureOwner(): SessionOwner? = synchronized(runtime) {
        runtime.accountId?.takeIf { runtime.accessToken != null }?.let { SessionOwner(it, runtime.accountEpoch) }
    }

    private fun callOwner(): SessionOwner? = if (sessionBound) boundOwner else captureOwner()

    private fun requireOwner(owner: SessionOwner) = synchronized(runtime) {
        check(runtime.accountId == owner.accountId && runtime.accountEpoch == owner.epoch && runtime.accessToken != null)
    }

    /** Absence is a new chapter; a read error must not silently restart learning. */
    suspend fun read(chapterId: String): OrdinaryLearningCheckpoint? {
        // Capture before dispatch: queued work cannot borrow a later login's identity.
        val owner = checkNotNull(callOwner())
        return withContext(Dispatchers.IO) {
            requireOwner(owner)
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery(
                    "select module_id,step_id from learning_checkpoints where account_id=? and chapter_id=?",
                    arrayOf(owner.accountId, chapterId),
                ).use { cursor ->
                    if (cursor.moveToFirst()) OrdinaryLearningCheckpoint(chapterId, cursor.getString(0), cursor.getString(1)) else null
                }
            }.also { requireOwner(owner); currentCoroutineContext().ensureActive() }
        }
    }

    override suspend fun save(
        checkpoint: OrdinaryLearningCheckpoint,
    ): CheckpointSaveResult {
        val owner = callOwner() ?: return CheckpointSaveResult.Failed
        if (checkpoint.chapterId.isBlank() || checkpoint.moduleId.isBlank() ||
            checkpoint.stepId.isBlank()
        ) return CheckpointSaveResult.Failed
        return withContext(Dispatchers.IO) {
        try {
            requireOwner(owner)
            currentCoroutineContext().ensureActive()
            FormationDatabase(context).use { helper ->
                val db = helper.writableDatabase
                db.beginTransaction()
                var transactionOpen = true
                try {
                    requireOwner(owner)
                    currentCoroutineContext().ensureActive()
                    check(db.rawQuery(
                        "select 1 from chapters where account_id=? and chapter_id=? and status!='COMPLETED'",
                        arrayOf(owner.accountId, checkpoint.chapterId),
                    ).use { it.moveToFirst() })
                    val keepExisting = checkpoint.moduleId == "LR-01" && db.rawQuery(
                        "select 1 from learning_checkpoints where account_id=? and chapter_id=?",
                        arrayOf(owner.accountId, checkpoint.chapterId),
                    ).use { it.moveToFirst() }
                    if (!keepExisting) {
                        check(db.insertWithOnConflict(
                            "learning_checkpoints", null,
                            ContentValues().apply {
                                put("account_id", owner.accountId)
                                put("chapter_id", checkpoint.chapterId)
                                put("module_id", checkpoint.moduleId)
                                put("step_id", checkpoint.stepId)
                            }, SQLiteDatabase.CONFLICT_REPLACE,
                        ) != -1L)
                    }
                    currentCoroutineContext().ensureActive()
                    // SessionRuntime.clear/update use this monitor too. Commit cannot
                    // slip between the final owner check and a new login taking over.
                    synchronized(runtime) {
                        requireOwner(owner)
                        db.setTransactionSuccessful()
                        transactionOpen = false
                        db.endTransaction()
                    }
                } finally { if (transactionOpen) db.endTransaction() }
            }
            requireOwner(owner)
            CheckpointSaveResult.Saved
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { CheckpointSaveResult.Failed }
        }
    }
}
