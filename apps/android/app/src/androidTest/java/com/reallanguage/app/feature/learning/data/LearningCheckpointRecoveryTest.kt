package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.CheckpointSaveResult
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningCheckpointRecoveryTest {
    @Test fun returningToChapterEntryDoesNotEraseTheActualResumePoint() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "checkpoint-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-test-token", account) }
        FormationDatabase(context).use { it.writableDatabase.execSQL("insert into chapters values (?, 'flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account)) }
        try {
            val repository = AndroidLearningCheckpointRepository(context, runtime)
            assertEquals(CheckpointSaveResult.Saved, repository.save(OrdinaryLearningCheckpoint("c1", "LR-05", "flow:s2")))
            assertEquals(CheckpointSaveResult.Saved, repository.save(OrdinaryLearningCheckpoint("c1", "LR-01", "entry")))
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select module_id,step_id from learning_checkpoints where account_id=? and chapter_id='c1'", arrayOf(account)).use {
                    it.moveToFirst(); assertEquals("LR-05", it.getString(0)); assertEquals("flow:s2", it.getString(1))
                }
            }
        } finally {
            FormationDatabase(context).use { helper -> listOf("learning_checkpoints", "chapters").forEach { helper.writableDatabase.delete(it, "account_id=?", arrayOf(account)) } }
        }
    }
    @Test fun deletedOrForeignChapterCannotAcquireAnOrphanCheckpoint() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "checkpoint-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-test-token", account) }
        try {
            val result = AndroidLearningCheckpointRepository(context, runtime)
                .save(OrdinaryLearningCheckpoint("absent", "LR-02", "explanation:s1"))
            assertEquals(CheckpointSaveResult.Failed, result)
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select count(*) from learning_checkpoints where account_id=?", arrayOf(account)).use {
                    it.moveToFirst()
                    assertEquals(0, it.getInt(0))
                }
            }
        } finally {
            FormationDatabase(context).use { it.writableDatabase.delete("learning_checkpoints", "account_id=?", arrayOf(account)) }
        }
        Unit
    }
}
