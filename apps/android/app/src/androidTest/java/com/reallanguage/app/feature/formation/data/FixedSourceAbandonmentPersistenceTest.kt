package com.reallanguage.app.feature.formation.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.domain.*
import com.reallanguage.app.feature.learning.data.AndroidChapterAbandonRepository
import com.reallanguage.app.feature.learning.domain.ChapterAbandonResult
import com.reallanguage.app.testing.IsolatedLearningDatabaseContext
import com.reallanguage.app.testing.withExplicitSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FixedSourceAbandonmentPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = session("owner")
    private val keys = FixedSourceTestKeys()
    private val codec get() = AesGcmFixedSourcePayloadCodec(keys)
    private val db get() = database.writableDatabase
    @Before fun setUp() { context = IsolatedLearningDatabaseContext(InstrumentationRegistry.getInstrumentation().targetContext, "fixed-source-abandon"); database = FormationDatabase(context); db }
    @After fun tearDown() { database.close(); context.deleteDatabase("real-language-learning.db") }

    @Test fun abandoningTheLastChapterRemovesOnlyItsOwnedSourceAndReplayStaysIdempotent() = runBlocking {
        val own = fix(runtime, "flow", 1)
        fix(runtime, "other-flow", 1)
        val other = session("other")
        fix(other, "flow", 1)
        val reader = AndroidFixedSourceRepository(context, runtime, codec)
        assertTrue(reader.load("flow") is FixedSourceLoad.Loaded)
        val repository = AndroidChapterAbandonRepository(context, runtime)
        val result = repository.abandonUnarchivedChapter(own.single(), "abandon")
        assertEquals(ChapterAbandonResult.Abandoned(false, null), result)
        assertEquals(FixedSourceLoad.Missing, reader.load("flow"))
        assertTrue(reader.load("other-flow") is FixedSourceLoad.Loaded)
        assertTrue(AndroidFixedSourceRepository(context, other, codec).load("flow") is FixedSourceLoad.Loaded)
        assertEquals(result, repository.abandonUnarchivedChapter(own.single(), "abandon"))
        assertEquals(2, count("fixed_source_payloads"))
    }

    @Test fun abandoningLaterChapterPreservesEntireSourceWhenEarlierChapterIsAlreadyCompleted() = runBlocking {
        val chapters = fix(runtime, "flow", 2)
        val reader = AndroidFixedSourceRepository(context, runtime, codec)
        val original = reader.load("flow") as FixedSourceLoad.Loaded
        assertEquals(listOf("u1", "u2", "outside"), original.source.units.map { it.unitId })
        // Isolated persistence prerequisite, not evidence that a real user completed the learning modules.
        db.execSQL("update chapters set status='COMPLETED' where account_id='owner' and chapter_id=?", arrayOf(chapters.first()))
        assertEquals(ChapterAbandonResult.Abandoned(false, null), AndroidChapterAbandonRepository(context, runtime)
            .abandonUnarchivedChapter(chapters.last(), "abandon-later"))
        assertEquals(original, reader.load("flow"))
        assertEquals(1, count("chapters")); assertEquals(1, count("fixed_source_payloads"))
    }

    @Test fun sourceDeletionFailureCannotReportSuccessOrLeaveHalfDeletedLastChapter() = runBlocking {
        val chapter = fix(runtime, "flow", 1).single()
        val reader = AndroidFixedSourceRepository(context, runtime, codec)
        val before = reader.load("flow")
        db.execSQL("create trigger isolated_reject_source_delete before delete on fixed_source_payloads begin select raise(ABORT,'isolated source deletion failure'); end")
        assertEquals(ChapterAbandonResult.Failed, AndroidChapterAbandonRepository(context, runtime).abandonUnarchivedChapter(chapter, "abandon"))
        assertEquals(before, reader.load("flow"))
        assertEquals(1, count("chapters")); assertEquals(1, count("fixed_sentences")); assertEquals(1, count("formation_flows"))
        assertEquals(0, count("chapter_abandon_commands"))
    }

    private suspend fun fix(owner: SessionRuntime, flow: String, chapterCount: Int): List<String> {
        val request = ContentFixRequest(flow, "fix:$flow", (1..chapterCount).map { index -> EnglishChapterDraft("c$index", listOf(
            EnglishSentenceDraft("u$index", "第${index}句原文。", true, listOf(EnglishCandidate("e$index", "Original sentence $index."))))) },
            (1..chapterCount).associate { "u$it" to "e$it" }, emptyList(), emptySet())
            .withExplicitSource("第1句原文。\n第2句原文。\n未进入章节的原文。", listOf(FixedSourceUnit("u1", 0, "第1句原文。"),
                FixedSourceUnit("u2", 1, "第2句原文。"), FixedSourceUnit("outside", 2, "未进入章节的原文。")))
        assertTrue(AndroidContentFixRepository(context, owner, sourceCipher = codec).fix(request) is ContentFixResult.Fixed)
        return db.rawQuery("select chapter_id from chapters where account_id=? and flow_id=? order by chapter_order", arrayOf(owner.accountId, flow))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }
    private fun count(table: String) = db.rawQuery("select count(*) from $table", null).use { it.moveToFirst(); it.getInt(0) }
    private fun session(account: String) = SessionRuntime().apply { update("isolated-token", account) }
}
