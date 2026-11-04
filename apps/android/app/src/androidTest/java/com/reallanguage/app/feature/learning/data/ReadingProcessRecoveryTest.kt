package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit two-process test. Run write, force-stop the app, then run read with the same isolated id. */
class ReadingProcessRecoveryTest {
    @Test fun confirmedReadingSurvivesProcessTermination() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("progressPhase")
        assumeTrue(phase == "write" || phase == "read")
        val account = requireNotNull(args.getString("progressAccount")).also { require(it.matches(Regex("process-test-[a-z0-9-]+"))) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = SessionRuntime().apply { update("isolated-token-not-a-real-session", account) }
        val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true)))
        if (phase == "write") {
            FormationDatabase(context).use { helper ->
                helper.writableDatabase.execSQL("insert into chapters values (?, 'process-test-flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
                helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', 's1', 0, '我很平静。', 'I felt calm.', 'FOCUS')", arrayOf(account))
            }
        }
        var requests = 0
        val provider = object : ExplanationReadingProvider {
            override suspend fun incrementalHint(sentenceId: String, round: Int, baseExplanationZh: String): ExplanationContentResult {
                requests++
                check(phase == "write") { "Restoring delivered progress must not request another hint" }
                return ExplanationContentResult.Ready("这是隔离测试提示，不是生产 AI 结果。")
            }
            override suspend fun explainExpression(sentenceId: String, expressionId: String) = ExplanationContentResult.Failed
        }
        val model = withContext(Dispatchers.Main) {
            ExplanationReadingViewModel(chapter, "s1", "隔离测试基础讲解", emptyList(), provider,
                AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-02", "explanation:s1"))
        }
        suspend fun settled() = withTimeout(5_000) { model.uiState.first { !it.loading } }
        suspend fun act(action: ExplanationReadingAction) {
            withContext(Dispatchers.Main) { model.onAction(action) }
            settled().also { assertFalse(it.progressFailed) }
        }
        settled().also { assertFalse(it.progressFailed) }
        if (phase == "write") {
            act(ExplanationReadingAction.ExplanationFinished)
            act(ExplanationReadingAction.ReadFinished)
            act(ExplanationReadingAction.ExplanationFinished)
            assertEquals(1, requests)
        }
        try {
            assertEquals(1, model.uiState.value.completedReadCount)
            assertEquals("这是隔离测试提示，不是生产 AI 结果。", model.uiState.value.currentIncrementalHint)
            assertFalse(model.uiState.value.explaining)
            if (phase == "read") assertEquals(0, requests)
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select module_id,step_id from learning_checkpoints where account_id=? and chapter_id='c1'", arrayOf(account)).use {
                    assertTrue(it.moveToFirst()); assertEquals("LR-02", it.getString(0)); assertEquals("explanation:s1", it.getString(1))
                }
            }
        } finally {
            if (phase == "read") FormationDatabase(context).use { helper ->
                listOf("learning_module_progress", "learning_checkpoints", "fixed_sentences", "chapters").forEach {
                    helper.writableDatabase.delete(it, "account_id=?", arrayOf(account))
                }
            }
        }
    }
}
