package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.SpeakingDecisionSaveResult
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real UUID SQLite storage and session epochs; supplied decisions are not audio judgments. */
class FullSpeakingDecisionOwnerEpochTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private lateinit var runtime: SessionRuntime
    private val account = "full-speaking-owner-${UUID.randomUUID()}"
    private val target = "I drink tea."

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "full-speaking-decision-owner-epoch",
        )
        runtime = SessionRuntime().apply { update("isolated-first-session", account) }
        database = FormationDatabase(context)
        val db = database.writableDatabase
        db.execSQL(
            "insert into formation_flows values (?, 'flow', 'FIXED', 'DIARY', '隔离整句结果')",
            arrayOf(account),
        )
        db.execSQL("insert into chapters values (?, 'flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
        db.execSQL(
            "insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) " +
                "values (?, 'c1', 's1', 0, '我喝茶。', ?, 'FOCUS')",
            arrayOf(account, target),
        )
    }

    @After fun tearDown() {
        database.close()
        assertTrue(context.deleteDatabase("real-language-learning.db"))
        runtime.clear()
    }

    @Test fun oldSameAccountEntryCannotReplaceANewSessionsPersistedPassWithFailure() = runBlocking {
        val old = repository()
        val originalEpoch = runtime.accountEpoch
        runtime.clear()
        runtime.update("isolated-second-session", account)
        assertNotEquals(originalEpoch, runtime.accountEpoch)
        val fresh = repository()
        assertEquals(SpeakingDecisionSaveResult.Saved, fresh.saveIndependentDecision("s1", SpeakingEvaluationDecision.PASS))
        assertEquals(1, savedDecision())

        assertEquals(
            SpeakingDecisionSaveResult.Failed,
            old.saveIndependentDecision("s1", SpeakingEvaluationDecision.FAIL),
        )
        assertEquals("The new session's accepted result must survive the old entry", 1, savedDecision())
    }

    @Test fun oldSameAccountEntryCannotTurnANewSessionsFailureIntoAFalsePass() = runBlocking {
        val old = repository()
        old.cancelCapture()
        runtime.clear()
        runtime.update("isolated-second-session", account)
        val fresh = repository()
        assertEquals(SpeakingDecisionSaveResult.Saved, fresh.saveIndependentDecision("s1", SpeakingEvaluationDecision.FAIL))
        assertEquals(0, savedDecision())

        assertEquals(
            SpeakingDecisionSaveResult.Failed,
            old.saveIndependentDecision("s1", SpeakingEvaluationDecision.PASS),
        )
        assertEquals("An exited entry must not fabricate a later passing result", 0, savedDecision())
    }

    @Test fun aFreshRepositoryCanContinueAfterOrdinaryReturnWithoutErasingTheSavedResult() = runBlocking {
        val first = repository()
        assertNull(savedDecision())
        assertEquals(SpeakingDecisionSaveResult.Saved, first.saveIndependentDecision("s1", SpeakingEvaluationDecision.PASS))
        first.cancelCapture()
        assertEquals(1, savedDecision())
        val returned = repository()
        assertEquals(SpeakingDecisionSaveResult.Saved, returned.saveIndependentDecision("s1", SpeakingEvaluationDecision.PASS))
        assertEquals(1, savedDecision())
    }

    @Test fun aNormalTokenRefreshKeepsTheCurrentRepositoryAuthorized() = runBlocking {
        val current = repository()
        val epoch = runtime.accountEpoch
        runtime.update("isolated-refreshed-session", account)
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(SpeakingDecisionSaveResult.Saved, current.saveIndependentDecision("s1", SpeakingEvaluationDecision.PASS))
        assertEquals(1, savedDecision())
    }

    private fun repository() = AndroidFullSentenceSpeakingRepository(
        context,
        HttpTransport { error("This isolated persistence test cannot access HTTP or AI") },
        runtime,
        "c1",
        target,
        object : ReadSpeakAudioCapture {
            override fun start() = false
            override fun finish(): CapturedReadSpeakAudio? = null
            override fun cancel() = Unit
        },
    )

    private fun savedDecision(): Int? = FormationDatabase(context).use { helper ->
        helper.readableDatabase.rawQuery(
            "select passed from full_speaking_result_drafts where account_id=? and chapter_id='c1' and sentence_id='s1'",
            arrayOf(account),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getInt(0).also { assertFalse(cursor.moveToNext()) }
        }
    }
}
