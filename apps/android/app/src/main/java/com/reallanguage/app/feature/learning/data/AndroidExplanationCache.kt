package com.reallanguage.app.feature.learning.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.reallanguage.app.feature.formation.data.FormationDatabase

interface ExplanationCache {
    fun read(accountId: String, chapterId: String, sentenceId: String, key: String): String?
    fun write(accountId: String, chapterId: String, sentenceId: String, key: String, body: String)
}

/** Delivered learning content only; never stores credentials or unvalidated responses. */
class AndroidExplanationCache(context: Context) : ExplanationCache {
    private val context = context.applicationContext
    override fun read(accountId: String, chapterId: String, sentenceId: String, key: String): String? =
        FormationDatabase(context).use { helper ->
            helper.readableDatabase.rawQuery(
                "select response_body from learning_explanation_cache where account_id=? and chapter_id=? and sentence_id=? and cache_key=?",
                arrayOf(accountId, chapterId, sentenceId, key),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
        }

    override fun write(accountId: String, chapterId: String, sentenceId: String, key: String, body: String) {
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                check(db.rawQuery("select 1 from chapters where account_id=? and chapter_id=? and status!='COMPLETED'", arrayOf(accountId, chapterId)).use { it.moveToFirst() })
                check(db.insertWithOnConflict("learning_explanation_cache", null, ContentValues().apply {
                put("account_id", accountId); put("chapter_id", chapterId); put("sentence_id", sentenceId)
                put("cache_key", key); put("response_body", body)
                }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
}
