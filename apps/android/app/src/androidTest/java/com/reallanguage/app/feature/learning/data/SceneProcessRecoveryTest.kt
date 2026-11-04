package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpTransport
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
import java.io.File

/** Real task/progress/result SQLite repositories; isolated AI/audio substitutions, not quality evidence. */
class SceneProcessRecoveryTest {
    @Test fun trainingAndAcceptedEvaluationSurviveSeparateProcesses() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("scenePhase")
        assumeTrue(phase in setOf("train", "pending", "save", "complete"))
        val account = requireNotNull(args.getString("progressAccount")).also { require(it.matches(Regex("process-test-[a-z0-9-]+"))) }
        val context = SceneProcessDatabaseContext(
            ApplicationProvider.getApplicationContext(), account, create = phase == "train",
        )
        val runtime = SessionRuntime().apply { update("isolated-token-not-real", account) }
        val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I wrote a diary.", "我写了日记。", true)))
        if (phase == "train") FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values (?, 'process-test-flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
            helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', 's1', 0, '我写了日记。', 'I wrote a diary.', 'FOCUS')", arrayOf(account))
            helper.writableDatabase.execSQL("insert into scene_response_tasks values (?, 'c1', 't1', 's1', 0, 'Your evening', ?, 'I wrote a diary.')", arrayOf(account, "AI\u001fWhat did you do?"))
        }
        val durable = AndroidSceneResponseRepository(context, HttpTransport { error("Network must not be used") }, runtime,
            object : ReadSpeakAudioCapture { override fun start() = false; override fun finish(): CapturedReadSpeakAudio? = null; override fun cancel() = Unit })
        var aiRequests = 0
        var localSaves = 0
        val repository = object : SceneResponseRepository by durable {
            override fun startCapture() = true
            override suspend fun transcribeResponse(taskId: String): SpeakingTranscriptionResult {
                check(phase in setOf("train", "pending")); aiRequests++
                return SpeakingTranscriptionResult.Ready(SpeakingTranscription("I wrote a diary.", emptyList(), null))
            }
            override suspend fun trainingFeedback(taskId: String, roundIndex: Int, confirmedTranscript: String): SceneTrainingFeedbackResult {
                check(phase == "train"); aiRequests++
                return SceneTrainingFeedbackResult.Ready("隔离测试训练反馈")
            }
            override suspend fun evaluateFinalRound(taskId: String, confirmedTranscript: String): SceneEvaluationResult {
                check(phase == "pending"); aiRequests++
                return SceneEvaluationResult.Ready(SceneEvaluationCandidate(SceneEvaluationDecision.PASS, true, true, true, "隔离测试评价，不是真实原音判断"))
            }
            override suspend fun saveFinalDecision(chapterId: String, sentenceId: String, decision: SceneEvaluationDecision): SceneResponseSaveResult {
                if (phase == "pending") return SceneResponseSaveResult.Failed
                check(phase == "save"); localSaves++
                return durable.saveFinalDecision(chapterId, sentenceId, decision)
            }
        }
        val store = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-09")
        val vm = withContext(Dispatchers.Main) { SceneResponseViewModel(chapter, null, repository, store) }
        val viewModels = ViewModelStore().apply { put("scene-process", vm) }
        var completed = false
        try {
        suspend fun settled() = withTimeout(5_000) { vm.uiState.first { !it.loading && !it.submitting && !it.transcribing } }.also { assertFalse(it.progressFailed) }
        suspend fun act(action: SceneResponseAction) { withContext(Dispatchers.Main) { vm.onAction(action) }; settled() }
        suspend fun back() { act(SceneResponseAction.BackRequested); assertEquals(SceneResponseEffect.ReturnToCompleteWriting, withTimeout(5_000) { vm.effects.first() }) }
        settled()
        when (phase) {
            "train" -> {
                repeat(2) {
                    act(SceneResponseAction.StartRequested); act(SceneResponseAction.FinishedRequested)
                    act(SceneResponseAction.ConfirmTranscriptRequested); act(SceneResponseAction.ContinueRoundRequested)
                }
                back(); assertEquals("2", store.read()?.get("roundIndex")); assertEquals(4, aiRequests)
            }
            "pending" -> {
                assertEquals(2, vm.uiState.value.roundIndex)
                act(SceneResponseAction.StartRequested); act(SceneResponseAction.FinishedRequested)
                act(SceneResponseAction.ConfirmTranscriptRequested)
                assertTrue(vm.uiState.value.saveFailed)
                back(); assertEquals("PASS", store.read()?.get("candidateDecision")); assertEquals(2, aiRequests)
            }
            "save" -> {
                assertEquals(SceneEvaluationDecision.PASS, vm.uiState.value.finalDecision)
                assertTrue(vm.uiState.value.saveFailed)
                act(SceneResponseAction.RetryRequested)
                assertEquals(SceneResponseEffect.OpenChapterResult, withTimeout(5_000) { vm.effects.first() })
                assertEquals(0, aiRequests); assertEquals(1, localSaves)
                assertEquals("true", store.read()?.get("completed"))
                FormationDatabase(context).use { helper -> helper.readableDatabase.rawQuery("select passed from scene_response_result_drafts where account_id=? and chapter_id='c1' and sentence_id='s1'", arrayOf(account)).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) } }
            }
            "complete" -> {
                assertTrue(vm.uiState.value.completed)
                assertEquals(SceneResponseEffect.OpenChapterResult, withTimeout(5_000) { vm.effects.first() })
                assertEquals(0, aiRequests); assertEquals(0, localSaves)
                FormationDatabase(context).use { helper -> helper.readableDatabase.rawQuery("select passed from scene_response_result_drafts where account_id=? and chapter_id='c1' and sentence_id='s1'", arrayOf(account)).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertFalse(it.moveToNext()) } }
                completed = true
            }
        }
        } finally {
            withContext(Dispatchers.Main) { viewModels.clear() }
            if (completed) context.deleteOwnedDatabase()
        }
    }

    /** The caller supplies one fresh UUID account and reuses it across all four processes. */
    private class SceneProcessDatabaseContext(base: Context, account: String, create: Boolean) : ContextWrapper(base) {
        private val cacheRoot = base.cacheDir.canonicalFile
        private val directory = File(cacheRoot, "scene-recovery-$account")
        private val databaseFile = File(directory, "formation.db")

        init {
            require(account.matches(Regex("process-test-[a-z0-9-]+")))
            check(directory.canonicalFile == directory.absoluteFile && directory.parentFile == cacheRoot)
            if (create) check(directory.mkdir()) { "A fresh isolated process account is required" }
            else check(directory.isDirectory && databaseFile.isFile) { "The preceding isolated process phase is required" }
            check(databaseFile.canonicalFile == databaseFile.absoluteFile)
        }

        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File {
            check(name == "real-language-learning.db")
            check(databaseFile.canonicalFile == databaseFile.absoluteFile && directory.canonicalFile.parentFile == cacheRoot)
            return databaseFile
        }
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
            SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))

        fun deleteOwnedDatabase() {
            check(deleteDatabase("real-language-learning.db"))
            check(!databaseFile.exists())
            check(directory.listFiles()?.isEmpty() == true)
            check(directory.delete())
            check(!directory.exists())
        }
    }
}
