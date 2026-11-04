package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exact-account reauthentication boundaries, using only this test's UUID database. */
class LearningProgressOwnerEpochTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val session = SessionRuntime().apply { update("isolated-first-token", "isolated-progress-owner") }

    @Before fun setUp() {
        context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "learning-progress-owner-epoch")
        database = FormationDatabase(context)
        val db = database.writableDatabase
        db.execSQL("insert into formation_flows values('isolated-progress-owner','flow','FIXED','DIARY','隔离学习进度')")
        db.execSQL("insert into chapters values('isolated-progress-owner','flow','c1',0,'NOT_STARTED')")
        db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values('isolated-progress-owner','c1','s1',0,'我喝茶。','I drink tea.','FOCUS')")
    }

    @After fun tearDown() { database.close(); context.deleteDatabase("real-language-learning.db") }

    @Test fun oldStoreCannotOverwriteNewProgressAfterLogoutAndLoginAsTheSameAccount() = runBlocking {
        val old = store()
        assertTrue(old.write(mapOf("version" to "1", "reads" to "1")))
        val epoch = session.accountEpoch
        session.clear()
        session.update("isolated-second-token", "isolated-progress-owner")
        assertNotEquals(epoch, session.accountEpoch)
        val fresh = store()
        val expected = mapOf("version" to "1", "reads" to "2")
        assertTrue(fresh.write(expected))

        assertFalse("The exited entry cannot regain write authority by reauthentication", old.write(mapOf("version" to "1", "reads" to "3")))
        assertTrue(runCatching { old.read() }.isFailure)
        assertEquals(expected, fresh.read())
        assertEquals(expected, store().read())
        database.readableDatabase.rawQuery("select module_id,step_id from learning_checkpoints", null).use {
            assertTrue(it.moveToFirst())
            assertEquals("LR-02", it.getString(0))
            assertEquals("explanation:s1", it.getString(1))
            assertFalse(it.moveToNext())
        }
    }

    @Test fun normalTokenRefreshKeepsTheSameAccountEpochAndCurrentModuleUsable() = runBlocking {
        val current = store()
        assertTrue(current.write(mapOf("reads" to "1")))
        val epoch = session.accountEpoch
        session.update("isolated-refreshed-token", "isolated-progress-owner")
        assertEquals(epoch, session.accountEpoch)
        assertTrue(current.write(mapOf("reads" to "2")))
        assertEquals(mapOf("reads" to "2"), current.read())
        assertEquals(mapOf("reads" to "2"), store().read())
    }

    private fun store() = AndroidLearningProgressStore(context, session, "c1", "s1", "LR-02", "explanation:s1")
}
