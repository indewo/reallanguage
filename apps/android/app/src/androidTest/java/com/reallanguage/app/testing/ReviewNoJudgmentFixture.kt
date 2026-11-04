package com.reallanguage.app.testing

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.review.data.AndroidTodayReviewRepository
import com.reallanguage.app.feature.review.domain.ReviewQueueResult
import java.io.File
import java.time.LocalDate
import java.util.Collections
import java.util.UUID

/** Preaccepted completed chapters only; no recorded audio or oral judgment is fabricated. */
internal class ReviewNoJudgmentFixture(private val base: Context) {
    val runId = UUID.randomUUID().toString()
    val account = "review-no-judgment-$runId"
    val today = LocalDate.now().toString()
    private val directory = File(base.cacheDir, account)
    private val preferences = Collections.synchronizedSet(mutableSetOf<String>())
    private val isolatedDb = IsolatedLearningDatabaseContext(base, account)
    val context = object : ContextWrapper(isolatedDb) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$account-$name"
            preferences.add(isolatedName)
            return base.getSharedPreferences(isolatedName, mode)
        }
    }
    val runtime = SessionRuntime().apply { update("isolated-test-session", account) }
    val database = FormationDatabase(context)
    val db get() = database.writableDatabase

    fun seed(chapterCount: Int = 1, capability: String = "PATTERN_SPEAKING", existingWrongFlag: Boolean = true) {
        for (daysAgo in 1L..3L) insert("normal_learning_days", "local_date" to LocalDate.now().minusDays(daysAgo).toString())
        repeat(chapterCount) { index ->
            val id = index + 1
            val text = if (id == 1) "I used to walk." else "I prefer tea."
            val structure = if (id == 1) "used to + verb" else "prefer + noun"
            val meaning = if (id == 1) "过去习惯" else "表达偏好"
            insert("formation_flows", "flow_id" to "flow$id", "status" to "FIXED", "source_type" to "DIARY", "source_title" to "隔离回顾$id")
            insert("chapters", "flow_id" to "flow$id", "chapter_id" to "chapter$id", "chapter_order" to index, "status" to "COMPLETED")
            insert("fixed_sentences", "chapter_id" to "chapter$id", "unit_id" to "sentence$id", "sentence_order" to 0,
                "text_en" to text, "text_zh" to if (id == 1) "我以前常走路。" else "我更喜欢茶。", "role" to "FOCUS")
            insert("target_selections", "chapter_id" to "chapter$id", "target_id" to "target$id", "unit_id" to "sentence$id",
                "type" to "PATTERN", "display" to structure, "explanation_zh" to meaning,
                "structure_parts" to "", "related_vocabulary" to "")
            insert("pattern_items", "item_id" to "pattern$id", "semantic_pattern_key" to "$structure|$meaning",
                "structure_en" to structure, "meaning_zh" to meaning,
                "writing_status" to "VERIFIED", "speaking_status" to "VERIFIED",
                "writing_streak" to 2, "speaking_streak" to 2, "active_validation" to 1)
            insert("learning_obligations", "obligation_id" to "obligation$id", "chapter_id" to "chapter$id",
                "sentence_id" to "sentence$id", "capability_type" to capability, "target_id" to "target$id")
            if (existingWrongFlag) insert("correction_flags", "obligation_id" to "obligation$id")
        }
    }

    suspend fun queue() = (AndroidTodayReviewRepository(context, runtime) { today }.loadTodayQueue(3) as ReviewQueueResult.Ready).chapters

    fun formalSnapshot(): List<List<String?>> = listOf("pattern_items", "learning_obligations", "correction_flags").map { table ->
        db.rawQuery("select * from $table where account_id=? order by rowid", arrayOf(account)).use { cursor ->
            buildList { while (cursor.moveToNext()) for (column in 0 until cursor.columnCount) add(cursor.getString(column)) }
        }
    }

    fun count(table: String): Int = db.rawQuery("select count(*) from $table where account_id=?", arrayOf(account))
        .use { it.moveToFirst(); it.getInt(0) }

    fun scalar(sql: String): Int = db.rawQuery(sql, arrayOf(account)).use { it.moveToFirst(); it.getInt(0) }

    private fun insert(table: String, vararg pairs: Pair<String, Any>) {
        db.insertOrThrow(table, null, ContentValues().apply {
            put("account_id", account)
            pairs.forEach { (key, value) -> when (value) { is Int -> put(key, value); else -> put(key, value.toString()) } }
        })
    }

    fun close() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
        synchronized(preferences) { preferences.toList() }.forEach { name ->
            check(name.startsWith("$account-")); base.deleteSharedPreferences(name)
        }
        check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile && directory.name == account)
        check(!directory.exists() || directory.deleteRecursively())
    }
}
