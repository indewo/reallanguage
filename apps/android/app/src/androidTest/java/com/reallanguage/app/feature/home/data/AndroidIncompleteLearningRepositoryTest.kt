package com.reallanguage.app.feature.home.data

import com.reallanguage.app.testing.withExplicitSource
import com.reallanguage.app.testing.explicitSourceFixRepository
import com.reallanguage.app.feature.formation.domain.FixedSourceUnit

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.data.AndroidFixedChapterRepository
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import java.util.UUID
import com.reallanguage.app.feature.home.domain.IncompleteLearningLoadResult
import com.reallanguage.app.feature.home.domain.OrdinarySourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidIncompleteLearningRepositoryTest {
    @Test fun fixedContentRemainsDiscoverableWithoutExplicitExitCheckpoint() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val account = "resume-test-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("test-token", account) }
        val database = FormationDatabase(context)
        try {
            val sentence = EnglishSentenceDraft("u1", "我今天去公园。", true, listOf(EnglishCandidate("e1", "I went to the park today.")))
            val nextSentence = EnglishSentenceDraft("u2", "我在那里散步。", true, listOf(EnglishCandidate("e2", "I took a walk there.")))
            val request = ContentFixRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                listOf(EnglishChapterDraft("c1", listOf(sentence)), EnglishChapterDraft("c2", listOf(nextSentence))),
                mapOf("u1" to "e1", "u2" to "e2"), emptyList(), emptySet()).withExplicitSource("我今天去公园。\n我在那里散步。", listOf(FixedSourceUnit("u1", 0, "我今天去公园。"), FixedSourceUnit("u2", 1, "我在那里散步。")))
            val fixed = explicitSourceFixRepository(context, runtime).fix(request) as ContentFixResult.Fixed
            // Reconstruct repositories/session without calling the explicit exit/save action.
            val restored = SessionRuntime().apply { update("test-token", account) }
            val entries = (AndroidIncompleteLearningRepository(context, restored).loadOrdinaryLearning() as IncompleteLearningLoadResult.Ready).entries
            assertEquals("Fixed content must not disappear from the home resume entry", 1, entries.size)
            assertEquals(fixed.firstChapterId, entries.single().learningId)
            assertEquals("LR-01", entries.single().savedStepId)
            assertEquals("entry", entries.single().checkpointStepId)
            org.junit.Assert.assertTrue(AndroidFixedChapterRepository(context, restored).load(entries.single().learningId) is FixedChapterLoadResult.Loaded)
        } finally {
            listOf("fixed_source_payloads", "fix_commands", "fixed_sentences", "chapters", "formation_flows").forEach {
                database.writableDatabase.delete(it, "account_id=?", arrayOf(account))
            }
            database.close()
        }
    }
    @Test fun readsOnlyOwnedUnarchivedOrdinaryCheckpointsWithSourceIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = FormationDatabase(context)
        database.writableDatabase.apply {
            delete("learning_checkpoints", null, null); delete("chapter_archives", null, null)
            delete("fixed_sentences", null, null); delete("chapters", null, null); delete("formation_flows", null, null)
            insertOrThrow("formation_flows", null, values("account_id" to "account", "flow_id" to "flow", "status" to "FIXED", "source_type" to "AI_SCENE", "source_title" to "工作沟通"))
            insertOrThrow("chapters", null, values("account_id" to "account", "flow_id" to "flow", "chapter_id" to "chapter", "chapter_order" to 1, "status" to "NOT_STARTED"))
            insertOrThrow("learning_checkpoints", null, values("account_id" to "account", "chapter_id" to "chapter", "module_id" to "LR-06", "step_id" to "sentence-1"))
            insertOrThrow("learning_checkpoints", null, values("account_id" to "other", "chapter_id" to "other-chapter", "module_id" to "LR-09", "step_id" to "x"))
        }
        val runtime = SessionRuntime().apply { update("token", "account") }
        try {
            val result = AndroidIncompleteLearningRepository(context, runtime).loadOrdinaryLearning() as IncompleteLearningLoadResult.Ready
            assertEquals(1, result.entries.size)
            assertEquals(OrdinarySourceType.AI_SCENE, result.entries.single().sourceType)
            assertEquals(2, result.entries.single().chapterNumber)
            assertEquals("整句会说", result.entries.single().savedStepLabelZh)
        } finally {
            database.writableDatabase.apply {
                delete("learning_checkpoints", "account_id in (?, ?)", arrayOf("account", "other"))
                delete("chapters", "account_id = ?", arrayOf("account"))
                delete("formation_flows", "account_id = ?", arrayOf("account"))
            }
        }
    }
    private fun values(vararg pairs: Pair<String, Any>) = ContentValues().apply { pairs.forEach { (key, value) -> when (value) { is String -> put(key, value); is Int -> put(key, value) } } }
}
