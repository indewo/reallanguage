package com.reallanguage.app.feature.learning.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.AudioDictationCompletionResult
import com.reallanguage.app.feature.learning.domain.HandwritingCompletionResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real SQLite boundaries; no microphone, network, AI, or application database. */
class LearningCompletionOwnerSqliteTest {
    private lateinit var context: Context
    private val runtime = SessionRuntime().apply { update("isolated-first", "owner") }

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "completion-owner-sqlite")
        FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values('owner','flow','chapter',0,'NOT_STARTED')")
            helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values('owner','chapter','s1',0,'我喝茶。','I drink tea.','FOCUS')")
        }
    }
    @After fun tearDown() { context.deleteDatabase("real-language-learning.db") }

    @Test fun databaseOpenDuringHandwritingSaveCannotCommitAnExitedOwnersCompletion() = runBlocking {
        val boundary = OnDatabasePath(context)
        val old = AndroidHandwritingCompletionRepository(runtime, "chapter", "old", AndroidLearningStepStore(boundary))
        boundary.onPath = ::relogin
        val result = old.complete("s1", 2)
        assertNull("The test must actually cross the session boundary inside storage", boundary.onPath)
        assertEquals(HandwritingCompletionResult.Failed, result)
        assertEquals("A failed old operation must not leave a completion row", emptyList<String>(), steps())
        val fresh = AndroidHandwritingCompletionRepository(runtime, "chapter", "fresh", AndroidLearningStepStore(context))
        assertEquals(HandwritingCompletionResult.Saved, fresh.complete("s1", 2))
        assertEquals(listOf("HANDWRITING_COPY"), steps())
    }

    @Test fun databaseOpenDuringCorrectionCannotCommitEitherOfTheAtomicCompletionRows() = runBlocking {
        val boundary = OnDatabasePath(context)
        val old = AndroidAudioDictationCompletionRepository(runtime, "chapter", "old", AndroidLearningStepStore(boundary), isCorrection = true)
        boundary.onPath = ::relogin
        val result = old.complete("s1")
        assertNull("The boundary must be exercised, not merely configured", boundary.onPath)
        assertEquals(AudioDictationCompletionResult.Failed, result)
        assertEquals("Neither correction nor companion dictation may survive a rejected owner", emptyList<String>(), steps())
        val fresh = AndroidAudioDictationCompletionRepository(runtime, "chapter", "fresh", AndroidLearningStepStore(context), isCorrection = true)
        assertEquals(AudioDictationCompletionResult.Saved, fresh.complete("s1"))
        assertEquals(listOf("AUDIO_DICTATION", "READ_WRITE_CORRECTION"), steps())
        assertEquals(AudioDictationCompletionResult.Saved, fresh.complete("s1"))
        assertEquals(2, steps().size)
    }

    @Test fun reloggedOldEntriesCannotWriteAndFreshTokenRefreshRetainsBothCompletions() = runBlocking {
        val store = AndroidLearningStepStore(context)
        val oldCopy = AndroidHandwritingCompletionRepository(runtime, "chapter", "old-copy", store)
        val oldDictation = AndroidAudioDictationCompletionRepository(runtime, "chapter", "old-dictation", store)
        relogin()
        assertEquals(HandwritingCompletionResult.Failed, oldCopy.complete("s1", 2))
        assertEquals(AudioDictationCompletionResult.Failed, oldDictation.complete("s1"))
        assertTrue(steps().isEmpty())
        val copy = AndroidHandwritingCompletionRepository(runtime, "chapter", "copy", store)
        val dictation = AndroidAudioDictationCompletionRepository(runtime, "chapter", "dictation", store)
        val epoch = runtime.accountEpoch
        runtime.update("isolated-refresh", "owner")
        assertEquals(epoch, runtime.accountEpoch)
        assertEquals(HandwritingCompletionResult.Saved, copy.complete("s1", 2))
        assertEquals(AudioDictationCompletionResult.Saved, dictation.complete("s1"))
        assertEquals(listOf("AUDIO_DICTATION", "HANDWRITING_COPY"), steps())
    }

    @Test fun authorizationLossAfterBothCorrectionRowsWereInsertedRollsBackTheWholeTransaction() {
        var checks = 0
        val stored = AndroidLearningStepStore(context).completeWhileAuthorized("owner", "chapter", "s1", "READ_WRITE_CORRECTION", "late-logout") {
            checks++
            checks < 3 // Entry and transaction start valid; permission revoked before commit.
        }
        assertEquals("Both rows must reach the last authorization barrier", 3, checks)
        assertFalse(stored)
        assertTrue("No partial companion row may survive rollback", steps().isEmpty())
    }

    private fun relogin() { runtime.clear(); runtime.update("isolated-next", "owner") }
    private fun steps() = FormationDatabase(context).use { helper ->
        helper.readableDatabase.rawQuery("select step from learning_steps where account_id='owner' order by step", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
    }
    private class OnDatabasePath(base: Context) : ContextWrapper(base) {
        var onPath: (() -> Unit)? = null
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File {
            val file = super.getDatabasePath(name)
            onPath?.let { onPath = null; it() }
            return file
        }
    }
}
