package com.reallanguage.app.feature.learning.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.LearningProgressStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Saves ordinary module progress and its home resume pointer in one transaction. */
class AndroidLearningProgressStore(
    context: Context,
    private val runtime: SessionRuntime,
    private val chapterId: String,
    private val sentenceId: String,
    private val moduleId: String,
    private val stepId: String = sentenceId,
) : LearningProgressStore {
    private val context = context.applicationContext
    private val accountId = runtime.accountId
    private val accountEpoch = runtime.accountEpoch

    override suspend fun read(): Map<String, String>? = withContext(Dispatchers.IO) {
        requireAccount()
        FormationDatabase(context).use { helper ->
            val db = helper.readableDatabase
            requireChapter(db)
            db.rawQuery(
                "select payload from learning_module_progress where account_id=? and chapter_id=? and sentence_id=? and module_id=?",
                arrayOf(accountId, chapterId, sentenceId, moduleId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) null
                else JSONObject(cursor.getString(0)).let { json ->
                    json.keys().asSequence().associateWith { key -> json.get(key).also { require(it is String) } as String }
                }
            }
        }.also { requireAccount() }
    }

    override suspend fun write(values: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        try {
            requireAccount()
            require(moduleId in setOf("LR-02", "LR-03", "LR-04", "LR-05", "LR-06", "LR-07", "LR-08", "LR-09"))
            require(stepId.isNotBlank())
            FormationDatabase(context).use { helper ->
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    requireChapter(db)
                    check(db.rawQuery(
                        "select module_id,step_id from learning_checkpoints where account_id=? and chapter_id=?",
                        arrayOf(accountId, chapterId),
                    ).use { cursor ->
                        !cursor.moveToFirst() || (cursor.getString(0) == moduleId &&
                            cursor.getString(1) in setOf(stepId, sentenceId, "prepare:$sentenceId", "explanation:$sentenceId", "correction:$sentenceId"))
                    }) { "The active module has changed" }
                    check(db.insertWithOnConflict("learning_module_progress", null, ContentValues().apply {
                        put("account_id", accountId); put("chapter_id", chapterId)
                        put("sentence_id", sentenceId); put("module_id", moduleId)
                        put("payload", JSONObject(values).toString())
                    }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                    check(db.insertWithOnConflict("learning_checkpoints", null, ContentValues().apply {
                        put("account_id", accountId); put("chapter_id", chapterId)
                        put("module_id", moduleId); put("step_id", stepId)
                    }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                    requireAccount()
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private fun requireAccount() {
        check(accountId != null && runtime.accountId == accountId && runtime.accountEpoch == accountEpoch && runtime.accessToken != null)
    }

    private fun requireChapter(db: SQLiteDatabase) {
        check(db.rawQuery(
            "select 1 from chapters c join fixed_sentences s on s.account_id=c.account_id and s.chapter_id=c.chapter_id where c.account_id=? and c.chapter_id=? and c.status!='COMPLETED' and s.unit_id=? and s.role='FOCUS'",
            arrayOf(accountId, chapterId, sentenceId),
        ).use { it.moveToFirst() })
    }
}
