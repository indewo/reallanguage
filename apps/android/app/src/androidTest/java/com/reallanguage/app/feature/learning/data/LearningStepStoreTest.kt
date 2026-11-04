package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.feature.formation.data.FormationDatabase
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LearningStepStoreTest {
    @Test fun correctionMarkerRollsBackIfItsCompanionCompletionCannotBeSaved() {
        withChapter { context, account ->
            val store = AndroidLearningStepStore(context)
            val conflictingCommand = UUID.nameUUIDFromBytes("correction-dictation:correction".toByteArray()).toString()
            assertTrue(store.complete(account, "c1", "s2", "READ_SPEAK", conflictingCommand))
            assertFalse(store.complete(account, "c1", "s1", "READ_WRITE_CORRECTION", "correction"))
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select count(*) from learning_steps where account_id=? and sentence_id='s1'", arrayOf(account)).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
        }
    }

    @Test fun confirmedCorrectionAndDictationCompletionAreSavedTogetherWithoutDoubleCounting() {
        withChapter { context, account ->
            val store = AndroidLearningStepStore(context)
            assertTrue(store.complete(account, "c1", "s1", "READ_WRITE_CORRECTION", "correction"))
            assertTrue(store.complete(account, "c1", "s1", "READ_WRITE_CORRECTION", "retry"))
            FormationDatabase(context).use { helper ->
                helper.readableDatabase.rawQuery("select step from learning_steps where account_id=? and chapter_id='c1' and sentence_id='s1'", arrayOf(account)).use {
                    val steps = buildList { while (it.moveToNext()) add(it.getString(0)) }
                    assertEquals(setOf("AUDIO_DICTATION", "READ_WRITE_CORRECTION"), steps.toSet())
                    assertEquals(2, steps.size)
                }
            }
        }
    }

    @Test fun missingChapterContextSentenceAndArchivedChapterCannotAcquireCompletionRows() {
        withChapter { context, account ->
            val store = AndroidLearningStepStore(context)
            assertFalse(store.complete(account, "missing", "s1", "READ_SPEAK", "missing"))
            assertFalse(store.complete(account, "c1", "context", "HANDWRITING_COPY", "context"))
            assertFalse(store.complete(account, "c1", "s1", "INVENTED_STEP", "invalid"))
            FormationDatabase(context).use { it.writableDatabase.execSQL("update chapters set status='COMPLETED' where account_id=?", arrayOf(account)) }
            assertFalse(store.complete(account, "c1", "s1", "AUDIO_DICTATION", "late"))
        }
    }

    @Test fun commandForAnotherSentenceDoesNotPretendThatTheRequestedSentenceWasSaved() {
        withChapter { context, account ->
            val store = AndroidLearningStepStore(context)
            assertTrue(store.complete(account, "c1", "s1", "READ_SPEAK", "one"))
            assertTrue(store.complete(account, "c1", "s1", "READ_SPEAK", "retry"))
            assertFalse(store.complete(account, "c1", "s2", "READ_SPEAK", "one"))
            assertTrue(store.complete(account, "c1", "s2", "READ_SPEAK", "two"))
        }
    }

    private fun withChapter(block: (Context, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "step-store-${UUID.randomUUID()}"
        FormationDatabase(context).use { helper ->
            helper.writableDatabase.execSQL("insert into chapters values (?, 'flow', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
            listOf("s1", "s2", "context").forEachIndexed { index, id ->
                helper.writableDatabase.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', ?, ?, '测试', 'Test.', ?)", arrayOf<Any>(account, id, index, if (id == "context") "CONTEXT" else "FOCUS"))
            }
        }
        try { block(context, account) }
        finally { FormationDatabase(context).use { helper -> listOf("learning_steps", "fixed_sentences", "chapters").forEach { helper.writableDatabase.delete(it, "account_id=?", arrayOf(account)) } } }
    }
}
