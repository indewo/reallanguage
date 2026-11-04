package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.presentation.ReadSpeakAction
import com.reallanguage.app.feature.learning.presentation.ReadSpeakEffect
import com.reallanguage.app.feature.learning.presentation.ReadSpeakViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real SQLite repositories, explicit missing-audio fallback. Not an AI or pronunciation-quality test. */
class ReadSpeakProcessRecoveryTest {
    @Test fun confirmedTrainingSurvivesProcessDeathWithoutAnotherRequest() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("readSpeakPhase")
        assumeTrue(phase in setOf("write", "read"))
        val account = requireNotNull(args.getString("progressAccount")).also {
            require(it.matches(Regex("process-test-[a-z0-9-]+")))
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = SessionRuntime().apply { update("isolated-token-not-a-real-session", account) }
        val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true)))
        if (phase == "write") FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values (?, 'process-test-flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
            helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', 's1', 0, '我很平静。', 'I felt calm.', 'FOCUS')", arrayOf(account))
        }
        var requests = 0
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                requests++
                error("Manual confirmation or restored completion must not call AI")
            }
        }
        val capture = object : ReadSpeakAudioCapture {
            override fun start() = false
            override fun finish(): CapturedReadSpeakAudio? = null
            override fun cancel() = Unit
        }
        val repository = AndroidReadSpeakRepository(
            transport, runtime, "c1", "I felt calm.", "process-read-speak", capture,
            AndroidLearningStepStore(context),
        )
        val store = AndroidLearningProgressStore(context, runtime, "c1", "s1", "LR-03")
        val model = withContext(Dispatchers.Main) { ReadSpeakViewModel(chapter, "s1", repository, store) }
        suspend fun settled() = withTimeout(5_000) { model.uiState.first { !it.submitting } }
        suspend fun act(action: ReadSpeakAction) {
            withContext(Dispatchers.Main) { model.onAction(action) }
            settled()
        }
        settled()
        if (phase == "write") {
            act(ReadSpeakAction.StartRequested)
            act(ReadSpeakAction.PermissionOrRecognitionFailed)
            act(ReadSpeakAction.ManualFinished)
            assertTrue(model.uiState.value.completed)
            act(ReadSpeakAction.BackRequested)
            assertEquals(ReadSpeakEffect.ReturnToExplanation, withTimeout(5_000) { model.effects.first() })
        } else {
            assertTrue(model.uiState.value.completed)
            assertFalse(model.uiState.value.listening)
            assertFalse(model.uiState.value.progressFailed)
            assertEquals(null, model.uiState.value.feedbackZh)
            act(ReadSpeakAction.StartRequested)
            assertFalse(model.uiState.value.listening)
            act(ReadSpeakAction.ContinueRequested)
            assertEquals(ReadSpeakEffect.OpenHandwriting, withTimeout(5_000) { model.effects.first() })
        }
        assertEquals("true", store.read()?.get("completed"))
        assertEquals(0, requests)
        FormationDatabase(context).use { helper ->
            helper.readableDatabase.rawQuery(
                "select status from learning_steps where account_id=? and chapter_id='c1' and sentence_id='s1' and step='READ_SPEAK'",
                arrayOf(account),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("COMPLETED", cursor.getString(0)); assertFalse(cursor.moveToNext())
            }
            if (phase == "read") listOf("learning_module_progress", "learning_checkpoints", "learning_steps", "fixed_sentences", "chapters").forEach {
                helper.writableDatabase.delete(it, "account_id=?", arrayOf(account))
            }
        }
    }
}
