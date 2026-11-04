package com.reallanguage.app.feature.learning.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.AndroidFixedChapterRepository
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.learning.domain.FixedChapterLoadResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LockedLearningReadBoundaryTest {
    @Test fun hintReaderCannotBorrowNewAccountAndMissingFocusCannotBecomeEmptyHints() = fixture { context, _, runtime ->
        val previous = SessionRuntime().apply { update("isolated", "old-owner") }
        val reader = AndroidLockedHintReader(context, previous)
        previous.update("isolated", runtime.accountId)
        assertNull(reader.read("c1", "s1"))
        assertNull(AndroidLockedHintReader(context, runtime).read("missing", "s1"))
        assertNull(AndroidLockedHintReader(context, runtime).read("c1", "context"))
    }

    @Test fun actualEmptySelectionIsValidButAReadFailureIsNot() = fixture { context, helper, runtime ->
        assertEquals(listOf("feel + adjective：表达感受"), AndroidLockedHintReader(context, runtime).read("c1", "s1"))
        helper.writableDatabase.delete("target_selections", "account_id=?", arrayOf(runtime.accountId))
        assertEquals(emptyList<Any>(), AndroidLockedHintReader(context, runtime).read("c1", "s1"))
        assertEquals(emptyList<Any>(), AndroidLockedPatternReader(context, runtime).read("c1", "s1"))
        assertEquals(emptyList<Any>(), AndroidLockedWritingTargetReader(context, runtime).read("c1"))
    }

    @Test fun chapterReaderCannotSwitchToAnotherAccountsChapter() = fixture { context, _, runtime ->
        val previous = SessionRuntime().apply { update("isolated", "old-owner") }
        val reader = AndroidFixedChapterRepository(context, previous)
        previous.update("isolated", runtime.accountId)
        assertEquals(FixedChapterLoadResult.Failed, reader.load("c1"))
    }

    @Test fun writingReaderCannotSwitchToAnotherAccountsTargets() = fixture { context, _, runtime ->
        val previous = SessionRuntime().apply { update("isolated", "old-owner") }
        val reader = AndroidLockedWritingTargetReader(context, previous)
        previous.update("isolated", runtime.accountId)
        assertNull(reader.read("c1"))
    }

    @Test fun patternReaderCannotSwitchToAnotherAccountsTargets() = fixture { context, _, runtime ->
        val previous = SessionRuntime().apply { update("isolated", "old-owner") }
        val reader = AndroidLockedPatternReader(context, previous)
        previous.update("isolated", runtime.accountId)
        assertNull(reader.read("c1", "s1"))
    }

    @Test fun missingChapterAndContextSentenceAreNotAnEmptyValidLearningPlan() = fixture { context, _, runtime ->
        assertNull(AndroidLockedWritingTargetReader(context, runtime).read("missing"))
        assertNull(AndroidLockedPatternReader(context, runtime).read("c1", "context"))
    }

    @Test fun orphanTargetCannotBeUsedOrSilentlyDroppedFromACompletePlan() = fixture { context, helper, runtime ->
        helper.writableDatabase.execSQL("update target_selections set unit_id='missing' where account_id=?", arrayOf(runtime.accountId))
        assertNull(AndroidLockedWritingTargetReader(context, runtime).read("c1"))
    }

    @Test fun completedChapterKeepsItsExactContextAndTargetsReadableForReview() = fixture { context, helper, runtime ->
        helper.writableDatabase.execSQL("update chapters set status='COMPLETED' where account_id=?", arrayOf(runtime.accountId))
        val chapter = (AndroidFixedChapterRepository(context, runtime).load("c1") as FixedChapterLoadResult.Loaded).chapter
        assertEquals(listOf("context", "s1"), chapter.sentences.map { it.sentenceId })
        assertEquals(listOf(false, true), chapter.sentences.map { it.isFocus })
        assertEquals("p1", AndroidLockedWritingTargetReader(context, runtime).read("c1")!!.single().targetId)
        assertEquals("p1", AndroidLockedPatternReader(context, runtime).read("c1", "s1")!!.single().patternId)
    }

    private fun fixture(block: suspend (Context, FormationDatabase, SessionRuntime) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = "read-boundary-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-not-real", account) }
        FormationDatabase(context).use { helper ->
            val db = helper.writableDatabase
            try {
                db.execSQL("insert into chapters values (?, 'f1', 'c1', 0, 'NOT_STARTED')", arrayOf(account))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', 'context', 0, '我去了公园。', 'I went to the park.', 'CONTEXT')", arrayOf(account))
                db.execSQL("insert into fixed_sentences(account_id,chapter_id,unit_id,sentence_order,text_zh,text_en,role) values (?, 'c1', 's1', 1, '我很平静。', 'I felt calm.', 'FOCUS')", arrayOf(account))
                db.execSQL("insert into target_selections(account_id,chapter_id,target_id,unit_id,type,display,explanation_zh,structure_parts,related_vocabulary) values (?, 'c1', 'p1', 's1', 'PATTERN', 'feel + adjective', '表达感受', '', '')", arrayOf(account))
                db.execSQL("update target_selections set source_evidence=? where account_id=?", arrayOf(
                    """{"version":1,"startUtf16":2,"endUtf16":11,"surfaceForm":"felt calm","normalizedForm":"feel + adjective","allowedForms":["felt calm"]}""", account,
                ))
                block(context, helper, runtime)
            } finally {
                listOf("target_selections", "fixed_sentences", "chapters").forEach { db.delete(it, "account_id=?", arrayOf(account)) }
            }
        }
    }
}
