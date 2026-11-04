package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.testing.withExplicitSource
import com.reallanguage.app.testing.explicitSourceFixRepository
import com.reallanguage.app.feature.formation.domain.FixedSourceUnit

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.formation.data.AndroidFixedChapterRepository
import com.reallanguage.app.feature.formation.data.FormationDatabase
import com.reallanguage.app.feature.formation.domain.ContentFixRequest
import com.reallanguage.app.feature.formation.domain.ContentFixResult
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.home.data.AndroidIncompleteLearningRepository
import com.reallanguage.app.feature.home.domain.IncompleteLearningLoadResult
import com.reallanguage.app.feature.learning.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real local repositories in a UUID-named SQLite file. No user database or AI is used. */
class ChapterContinuationRecoveryTest {
    private lateinit var context: Context
    private lateinit var database: FormationDatabase
    private val runtime = session("continuation-owner")

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val isolatedFile = File(base.cacheDir, "chapter-continuation-${UUID.randomUUID()}.db")
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = isolatedFile
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(isolatedFile.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(isolatedFile.path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
            override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(isolatedFile)
        }
        database = FormationDatabase(context)
        database.writableDatabase
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase("real-language-learning.db")
    }

    @Test fun selectedLaterChapterKeepsItsActualWritingPositionAfterReturningHomeAndRecreatingSession() = runBlocking {
        val chapters = fixThreeChapters(runtime)
        archive(chapters[0])
        val selection = AndroidChapterSelectionRepository(context, runtime).loadRemaining(
            ChapterSelectionBoundary.ArchivedChapter(chapters[0].chapterId),
        ) as ChapterSelectionLoadResult.Ready
        assertEquals(chapters.drop(1).map { it.chapterId }, selection.chapters.map { it.chapterId })
        val selected = chapters[2]
        val sentenceId = selected.sentences.single().sentenceId
        val progress = mapOf("version" to "1", "phase" to "FINAL_BATCH", "finalInputs" to "saved fixture input")
        assertTrue(AndroidLearningProgressStore(context, runtime, selected.chapterId, sentenceId, "LR-08").write(progress))
        assertEquals(CheckpointSaveResult.Saved, AndroidLearningCheckpointRepository(context, runtime).save(
            OrdinaryLearningCheckpoint(selected.chapterId, "LR-08", sentenceId),
        ))

        val restored = session(requireNotNull(runtime.accountId))
        val entries = entries(restored)
        assertEquals("The chosen chapter must remain a direct resume option", selected.chapterId, entries.first().learningId)
        assertEquals(setOf(chapters[1].chapterId, selected.chapterId), entries.map { it.learningId }.toSet())
        val resumed = entries.single { it.learningId == selected.chapterId }
        assertEquals("LR-08", resumed.savedStepId)
        assertEquals(sentenceId, resumed.checkpointStepId)
        assertEquals(3, resumed.chapterNumber)
        assertEquals(OrdinaryLearningCheckpoint(selected.chapterId, "LR-08", sentenceId),
            AndroidLearningCheckpointRepository(context, restored).read(selected.chapterId))
        assertEquals(progress, AndroidLearningProgressStore(context, restored, selected.chapterId, sentenceId, "LR-08").read())
        assertEquals(selected, (AndroidFixedChapterRepository(context, restored).load(selected.chapterId) as FixedChapterLoadResult.Loaded).chapter)
    }

    @Test fun completingAChosenLastChapterStillOffersTheEarlierUnfinishedChapter() = runBlocking {
        val chapters = fixThreeChapters(runtime)
        archive(chapters[0])
        val lastSummary = archive(chapters[2])
        assertTrue("Remaining work is not limited to larger chapter numbers", lastSummary.hasNextChapter)
        val selection = AndroidChapterSelectionRepository(context, session(requireNotNull(runtime.accountId))).loadRemaining(
            ChapterSelectionBoundary.ArchivedChapter(chapters[2].chapterId),
        ) as ChapterSelectionLoadResult.Ready
        assertEquals(listOf(chapters[1].chapterId), selection.chapters.map { it.chapterId })
        assertEquals(listOf(chapters[1].chapterId), entries(runtime).map { it.learningId })
        assertFalse(archive(chapters[1]).hasNextChapter)
        assertTrue(entries(runtime).isEmpty())
    }

    @Test fun everySavedChapterRemainsAvailableButUnstartedSiblingsAreNotAllOpened() = runBlocking {
        val chapters = fixThreeChapters(runtime)
        listOf(chapters[0], chapters[2]).forEach { chapter ->
            assertEquals(CheckpointSaveResult.Saved, AndroidLearningCheckpointRepository(context, runtime).save(
                OrdinaryLearningCheckpoint(chapter.chapterId, "LR-08", chapter.sentences.single().sentenceId),
            ))
        }
        val loaded = entries(session(requireNotNull(runtime.accountId)))
        assertEquals(setOf(chapters[0].chapterId, chapters[2].chapterId), loaded.map { it.learningId }.toSet())
        assertTrue(loaded.all { it.savedStepId == "LR-08" })
    }

    @Test fun noProgressStillOffersOnlyFirstChapterAndOtherAccountProgressNeverLeaks() = runBlocking {
        val ownChapters = fixThreeChapters(runtime)
        val other = session("other-continuation-owner")
        val otherChapters = fixThreeChapters(other)
        assertEquals(CheckpointSaveResult.Saved, AndroidLearningCheckpointRepository(context, other).save(
            OrdinaryLearningCheckpoint(otherChapters[2].chapterId, "LR-09", otherChapters[2].sentences.single().sentenceId),
        ))
        val ownEntries = entries(session(requireNotNull(runtime.accountId)))
        assertEquals(listOf(ownChapters[0].chapterId), ownEntries.map { it.learningId })
        assertEquals("LR-01", ownEntries.single().savedStepId)
        assertEquals("entry", ownEntries.single().checkpointStepId)
        val otherEntries = entries(other)
        assertEquals(setOf(otherChapters[0].chapterId, otherChapters[2].chapterId), otherEntries.map { it.learningId }.toSet())
        assertEquals(otherChapters[2].chapterId, otherEntries.first().learningId)
    }

    @Test fun anotherAccountOrSourceDoesNotInventRemainingChaptersAfterCompletion() = runBlocking {
        val ownChapters = fixThreeChapters(runtime)
        fixThreeChapters(session("other-continuation-owner"))
        archive(ownChapters[0])
        archive(ownChapters[1])
        val sameOwnerOtherSource = fixThreeChapters(runtime)
        val summary = archive(ownChapters[2])
        assertFalse(summary.hasNextChapter)
        val remaining = AndroidChapterSelectionRepository(context, runtime).loadRemaining(
            ChapterSelectionBoundary.ArchivedChapter(ownChapters[2].chapterId),
        ) as ChapterSelectionLoadResult.Ready
        assertTrue(remaining.chapters.isEmpty())
        assertEquals(listOf(sameOwnerOtherSource[0].chapterId), entries(runtime).map { it.learningId })
    }

    private suspend fun fixThreeChapters(owner: SessionRuntime): List<FixedLearningChapter> {
        val flow = UUID.randomUUID().toString()
        val request = ContentFixRequest(flow, UUID.randomUUID().toString(),
            (1..3).map { number -> EnglishChapterDraft("c$number", listOf(
                EnglishSentenceDraft("s$number", "这是第${number}句。", true,
                    listOf(EnglishCandidate("e$number", "This is sentence $number."))),
            )) }, (1..3).associate { "s$it" to "e$it" }, emptyList(), emptySet()).withExplicitSource("这是第1句。\n这是第2句。\n这是第3句。", (1..3).map { FixedSourceUnit("s$it", it - 1, "这是第${it}句。") })
        assertTrue(explicitSourceFixRepository(context, owner).fix(request) is ContentFixResult.Fixed)
        val ids = database.readableDatabase.rawQuery(
            "select chapter_id from chapters where account_id=? and flow_id=? order by chapter_order",
            arrayOf(owner.accountId, flow),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return ids.map { (AndroidFixedChapterRepository(context, owner).load(it) as FixedChapterLoadResult.Loaded).chapter }
    }

    private suspend fun archive(chapter: FixedLearningChapter): ChapterArchiveSummary {
        val sentence = chapter.sentences.single()
        val result = CompleteWritingEvaluator(FixedWritingComparator()).evaluate(
            chapter.sentences, mapOf(sentence.sentenceId to sentence.textEn), emptyList(), helpUsed = false,
        )
        assertEquals(CompleteWritingSaveResult.Saved, AndroidCompleteWritingRepository(context, runtime).saveFinalBatch(chapter.chapterId, result))
        assertEquals(result, AndroidCompleteWritingRepository(context, session(requireNotNull(runtime.accountId))).loadFinalBatch(chapter.chapterId))
        // Explicit isolated prerequisites for this persistence/navigation test, not audio/AI evidence.
        listOf("full_speaking_result_drafts", "scene_response_result_drafts").forEach { table ->
            database.writableDatabase.execSQL("insert into $table(account_id,chapter_id,sentence_id,passed) values (?,?,?,1)",
                arrayOf(runtime.accountId, chapter.chapterId, sentence.sentenceId))
        }
        val archived = AndroidChapterArchiveRepository(context, runtime).archive(chapter.chapterId, UUID.randomUUID().toString())
        assertTrue(archived is ChapterArchiveResult.Archived)
        val summary = (archived as ChapterArchiveResult.Archived).summary
        val readBack = AndroidChapterArchiveRepository(context, session(requireNotNull(runtime.accountId))).archive(chapter.chapterId, UUID.randomUUID().toString())
        assertEquals(summary, (readBack as ChapterArchiveResult.AlreadyArchived).summary)
        return summary
    }

    private suspend fun entries(owner: SessionRuntime) =
        (AndroidIncompleteLearningRepository(context, owner).loadOrdinaryLearning() as IncompleteLearningLoadResult.Ready).entries

    private fun session(account: String) = SessionRuntime().apply { update("isolated-test-token", account) }
}
