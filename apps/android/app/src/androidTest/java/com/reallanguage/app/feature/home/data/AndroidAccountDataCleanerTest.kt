package com.reallanguage.app.feature.home.data

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.data.accountLearningAttemptsDirectory
import com.reallanguage.app.feature.recording.data.accountRecordingsDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAccountDataCleanerTest {
    @Test fun deletesOnlyCurrentAccountsDatabaseRowsAndRecordings() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = FormationDatabase(context)
        val owned = "account-deletion-owned"
        val other = "account-deletion-other"
        val ownedDirectory = accountRecordingsDirectory(context.filesDir, owned)
        val otherDirectory = accountRecordingsDirectory(context.filesDir, other)
        val ownedAttempts = accountLearningAttemptsDirectory(context.filesDir, owned)
        val otherAttempts = accountLearningAttemptsDirectory(context.filesDir, other)
        database.writableDatabase.apply {
            delete("learning_checkpoints", "account_id in (?, ?)", arrayOf(owned, other))
            insertOrThrow("learning_checkpoints", null, checkpoint(owned, "owned-chapter"))
            insertOrThrow("learning_checkpoints", null, checkpoint(other, "other-chapter"))
            for (account in listOf(owned, other)) {
                execSQL(
                    "insert or replace into scene_recommendation_cache_state values(?,?,?)",
                    arrayOf<Any>(account, "signature-$account", 1L),
                )
                execSQL(
                    "insert or replace into scene_recommendation_cache_items values(?,?,?,?,?,?,?)",
                    arrayOf<Any>(account, 0, "recommendation-$account", "主题", "理由", "[]", "2026-09-14"),
                )
            }
        }
        ownedDirectory.mkdirs(); ownedDirectory.resolve("owned.m4a").writeText("owned")
        otherDirectory.mkdirs(); otherDirectory.resolve("other.m4a").writeText("other")
        ownedAttempts.mkdirs(); ownedAttempts.resolve("owned.m4a").writeText("owned")
        otherAttempts.mkdirs(); otherAttempts.resolve("other.m4a").writeText("other")
        val cache = com.reallanguage.app.feature.learning.data.AndroidExplanationCache(context)
        database.writableDatabase.execSQL("insert into chapters(account_id,flow_id,chapter_id,chapter_order,status) values (?,?,?,0,'NOT_STARTED')", arrayOf(owned, "test-flow", "owned-chapter"))
        database.writableDatabase.execSQL("insert into chapters(account_id,flow_id,chapter_id,chapter_order,status) values (?,?,?,0,'NOT_STARTED')", arrayOf(other, "test-flow", "other-chapter"))
        cache.write(owned, "owned-chapter", "s1", "base", "owned-content")
        cache.write(other, "other-chapter", "s1", "base", "other-content")

        try {
            assertTrue(AndroidAccountDataCleaner(context).clear(owned))
            assertEquals(0, count(database, owned))
            assertEquals(1, count(database, other))
            assertEquals(0, countTable(database, "scene_recommendation_cache_state", owned))
            assertEquals(0, countTable(database, "scene_recommendation_cache_items", owned))
            assertEquals(1, countTable(database, "scene_recommendation_cache_state", other))
            assertEquals(1, countTable(database, "scene_recommendation_cache_items", other))
            org.junit.Assert.assertNull(cache.read(owned, "owned-chapter", "s1", "base"))
            assertEquals("other-content", cache.read(other, "other-chapter", "s1", "base"))
            assertFalse(ownedDirectory.exists())
            assertTrue(otherDirectory.resolve("other.m4a").exists())
            assertFalse(ownedAttempts.exists())
            assertTrue(otherAttempts.resolve("other.m4a").exists())
        } finally {
            database.writableDatabase.delete("learning_checkpoints", "account_id in (?, ?)", arrayOf(owned, other))
            database.writableDatabase.delete("learning_explanation_cache", "account_id in (?, ?)", arrayOf(owned, other))
            database.writableDatabase.delete("chapters", "account_id in (?, ?)", arrayOf(owned, other))
            database.writableDatabase.delete("scene_recommendation_cache_state", "account_id in (?, ?)", arrayOf(owned, other))
            database.writableDatabase.delete("scene_recommendation_cache_items", "account_id in (?, ?)", arrayOf(owned, other))
            ownedDirectory.deleteRecursively(); otherDirectory.deleteRecursively()
            ownedAttempts.deleteRecursively(); otherAttempts.deleteRecursively()
        }
    }

    private fun checkpoint(accountId: String, chapterId: String) = ContentValues().apply {
        put("account_id", accountId); put("chapter_id", chapterId); put("module_id", "LR-01"); put("step_id", "step-1")
    }

    private fun count(database: FormationDatabase, accountId: String): Int =
        database.readableDatabase.rawQuery("select count(*) from learning_checkpoints where account_id = ?", arrayOf(accountId))
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun countTable(database: FormationDatabase, table: String, accountId: String): Int =
        database.readableDatabase.rawQuery("select count(*) from $table where account_id = ?", arrayOf(accountId))
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
}
