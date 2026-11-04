package com.reallanguage.app.feature.review.data

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.feature.review.domain.ReviewAbilityResultDraft
import com.reallanguage.app.feature.review.domain.ReviewChapterCommitResult
import com.reallanguage.app.feature.review.domain.ReviewChapterLoadResult
import com.reallanguage.app.feature.review.domain.ReviewChapterSnapshot
import com.reallanguage.app.feature.review.domain.ReviewExitResult
import com.reallanguage.app.feature.review.domain.ReviewQueueResult
import com.reallanguage.app.feature.review.domain.ReviewSettlementResult
import com.reallanguage.app.testing.ReviewNoJudgmentFixture
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** UUID SQLite authorization fixtures, not real speech, user credentials or AI-quality evidence. */
class ReviewRepositoryOwnerEpochTest {
    private lateinit var fixture: ReviewNoJudgmentFixture

    @Before fun setUp() {
        fixture = ReviewNoJudgmentFixture(InstrumentationRegistry.getInstrumentation().targetContext)
        fixture.seed(capability = "PATTERN_WRITING")
    }

    @After fun tearDown() { fixture.close() }

    @Test fun oldChapterReaderCannotReadAfterLogoutAndSameAccountLogin() = runBlocking {
        val chapterId = fixture.queue().single().reviewChapterId
        val old = chapterRepository()
        assertTrue(old.loadChapter(chapterId) is ReviewChapterLoadResult.Ready)
        reauthenticate()
        val before = databaseSnapshot()

        assertEquals(ReviewChapterLoadResult.Failed, old.loadChapter(chapterId))
        assertEquals(before, databaseSnapshot())
        assertTrue(chapterRepository().loadChapter(chapterId) is ReviewChapterLoadResult.Ready)
        assertEquals(before, databaseSnapshot())
    }

    @Test fun oldChapterCommitCannotChangeFreshSessionFormalResultsOrWorkingState() = runBlocking {
        val chapterId = fixture.queue().single().reviewChapterId
        val old = chapterRepository()
        val oldSnapshot = (old.loadChapter(chapterId) as ReviewChapterLoadResult.Ready).chapter
        reauthenticate()
        insertWorkingState(chapterId, oldSnapshot.abilities.single().abilityId)
        val before = databaseSnapshot()

        val rejected = old.commitChapter(chapterId, "stale-commit", drafts(oldSnapshot, "old"))
        assertEquals(before, databaseSnapshot())
        assertEquals(ReviewChapterCommitResult.Failed, rejected)

        val fresh = chapterRepository()
        val snapshot = (fresh.loadChapter(chapterId) as ReviewChapterLoadResult.Ready).chapter
        val freshDrafts = drafts(snapshot, "fresh")
        val committed = fresh.commitChapter(chapterId, "fresh-commit", freshDrafts)
        assertTrue(committed is ReviewChapterCommitResult.Committed)
        assertEquals(1, fixture.count("review_chapter_results"))
        assertEquals(0, fixture.count("review_working_state"))
        val after = databaseSnapshot()
        val replay = chapterRepository().commitChapter(chapterId, "fresh-commit", freshDrafts)
        assertTrue(replay is ReviewChapterCommitResult.AlreadyCommitted)
        assertEquals((committed as ReviewChapterCommitResult.Committed).reviewSessionId,
            (replay as ReviewChapterCommitResult.AlreadyCommitted).reviewSessionId)
        assertEquals(after, databaseSnapshot())
    }

    @Test fun oldTodayReaderCannotCreateQueueOrSettleCycleAfterSameAccountReauthentication() = runBlocking {
        val old = todayRepository()
        reauthenticate()
        val before = databaseSnapshot()

        val queue = old.loadTodayQueue(3)
        val due = old.hasDueReview()
        assertEquals(before, databaseSnapshot())
        assertEquals(ReviewQueueResult.Failed, queue)
        assertFalse(due)

        val fresh = todayRepository()
        assertEquals(1, (fresh.loadTodayQueue(3) as ReviewQueueResult.Ready).chapters.size)
        assertTrue(fresh.hasDueReview())
        assertEquals(1, fixture.count("review_cycles"))
        assertEquals(1, fixture.count("review_chapters"))
        assertEquals(3, fixture.count("normal_learning_days"))
    }

    @Test fun oldResultCannotSettleOrEraseLearningDaysAfterSameAccountReauthentication() = runBlocking {
        val sessionId = completeCurrentChapter()
        val old = AndroidReviewResultRepository(fixture.context, fixture.runtime)
        reauthenticate()
        val before = databaseSnapshot()

        val rejected = old.settleSession(sessionId, "stale-settlement")
        assertEquals(before, databaseSnapshot())
        assertEquals(ReviewSettlementResult.Failed, rejected)

        val fresh = AndroidReviewResultRepository(fixture.context, fixture.runtime)
        val settled = fresh.settleSession(sessionId, "fresh-settlement") as ReviewSettlementResult.Settled
        assertTrue(settled.summary.cycleCompleted)
        assertEquals(1, settled.summary.completedFocusSentenceCount)
        assertEquals(0, fixture.count("normal_learning_days"))
        val after = databaseSnapshot()
        val replay = AndroidReviewResultRepository(fixture.context, fixture.runtime)
            .settleSession(sessionId, "fresh-settlement") as ReviewSettlementResult.AlreadySettled
        assertEquals(settled.summary, replay.summary)
        assertEquals(after, databaseSnapshot())
    }

    @Test fun oldExitCannotDeleteNewSessionWorkingStateAfterSameAccountReauthentication() = runBlocking {
        val chapterId = fixture.queue().single().reviewChapterId
        val snapshot = (chapterRepository().loadChapter(chapterId) as ReviewChapterLoadResult.Ready).chapter
        val old = AndroidReviewExitRepository(fixture.context, fixture.runtime)
        reauthenticate()
        insertWorkingState(chapterId, snapshot.abilities.single().abilityId)
        val before = databaseSnapshot()

        val count = old.completedChapterCount(chapterId)
        val discarded = old.discardWorkingState(chapterId)
        assertEquals(before, databaseSnapshot())
        assertEquals(-1, count)
        assertEquals(ReviewExitResult.Failed, discarded)

        val fresh = AndroidReviewExitRepository(fixture.context, fixture.runtime)
        assertEquals(0, fresh.completedChapterCount(chapterId))
        assertEquals(ReviewExitResult.Discarded, fresh.discardWorkingState(chapterId))
        assertEquals(before + ("review_working_state" to before.getValue("review_working_state").copy(rows = emptyList())),
            databaseSnapshot())
    }

    @Test fun tokenRefreshPreservesAllFourRepositoriesAndExactSavedResultReplay() = runBlocking {
        val today = todayRepository()
        val chapter = chapterRepository()
        val result = AndroidReviewResultRepository(fixture.context, fixture.runtime)
        val exit = AndroidReviewExitRepository(fixture.context, fixture.runtime)
        val epoch = fixture.runtime.accountEpoch
        fixture.runtime.update("isolated-refreshed-session", fixture.account)
        assertEquals(epoch, fixture.runtime.accountEpoch)

        val chapterId = (today.loadTodayQueue(3) as ReviewQueueResult.Ready).chapters.single().reviewChapterId
        assertTrue(today.hasDueReview())
        val snapshot = (chapter.loadChapter(chapterId) as ReviewChapterLoadResult.Ready).chapter
        insertWorkingState(chapterId, snapshot.abilities.single().abilityId)
        val beforeExit = databaseSnapshot()
        assertEquals(0, exit.completedChapterCount(chapterId))
        assertEquals(ReviewExitResult.Discarded, exit.discardWorkingState(chapterId))
        assertEquals(beforeExit + ("review_working_state" to beforeExit.getValue("review_working_state").copy(rows = emptyList())),
            databaseSnapshot())

        val committed = chapter.commitChapter(chapterId, "refresh-commit", drafts(snapshot, "refresh")) as ReviewChapterCommitResult.Committed
        val settled = result.settleSession(committed.reviewSessionId, "refresh-settlement") as ReviewSettlementResult.Settled
        assertTrue(settled.summary.cycleCompleted)
        assertEquals(1, settled.summary.completedFocusSentenceCount)
        assertEquals(1, fixture.count("review_chapter_results"))
        assertEquals(1, fixture.count("review_session_settlements"))
        val after = databaseSnapshot()
        val replay = result.settleSession(committed.reviewSessionId, "refresh-settlement") as ReviewSettlementResult.AlreadySettled
        assertEquals(settled.summary, replay.summary)
        assertEquals(after, databaseSnapshot())
    }

    @Test fun reauthenticationWhileOpeningDatabaseCannotWriteAnOldChapterTransaction() = runBlocking {
        val chapterId = fixture.queue().single().reviewChapterId
        val snapshot = (chapterRepository().loadChapter(chapterId) as ReviewChapterLoadResult.Ready).chapter
        val before = databaseSnapshot()
        val epochBeforeOpen = fixture.runtime.accountEpoch
        var opened = false
        val intercepted = object : ContextWrapper(fixture.context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File {
                val file = fixture.context.getDatabasePath(name)
                if (!opened) { opened = true; reauthenticate() }
                return file
            }
        }
        val old = AndroidReviewChapterRepository(intercepted, fixture.runtime) { fixture.today }

        val rejected = old.commitChapter(chapterId, "changed-during-open", drafts(snapshot, "old"))
        assertTrue(opened)
        assertNotEquals(epochBeforeOpen, fixture.runtime.accountEpoch)
        assertEquals(before, databaseSnapshot())
        assertEquals(ReviewChapterCommitResult.Failed, rejected)
        assertTrue(chapterRepository().loadChapter(chapterId) is ReviewChapterLoadResult.Ready)
    }

    private fun chapterRepository() = AndroidReviewChapterRepository(fixture.context, fixture.runtime) { fixture.today }
    private fun todayRepository() = AndroidTodayReviewRepository(fixture.context, fixture.runtime) { fixture.today }
    private fun drafts(snapshot: ReviewChapterSnapshot, prefix: String) = snapshot.abilities.map {
        ReviewAbilityResultDraft(it.abilityId, "$prefix-${it.abilityId}", true)
    }
    private suspend fun completeCurrentChapter(): String {
        val chapterId = fixture.queue().single().reviewChapterId
        val repository = chapterRepository()
        val snapshot = (repository.loadChapter(chapterId) as ReviewChapterLoadResult.Ready).chapter
        return (repository.commitChapter(chapterId, "accepted-fixture-commit", drafts(snapshot, "accepted-fixture"))
            as ReviewChapterCommitResult.Committed).reviewSessionId
    }
    private fun reauthenticate() {
        val previous = fixture.runtime.accountEpoch
        fixture.runtime.clear()
        fixture.runtime.update("isolated-next-session", fixture.account)
        assertNotEquals(previous, fixture.runtime.accountEpoch)
    }
    private fun insertWorkingState(chapterId: String, abilityId: String) {
        fixture.db.insertOrThrow("review_working_state", null, ContentValues().apply {
            put("account_id", fixture.account); put("review_chapter_id", chapterId)
            put("ability_id", abilityId); put("evidence_id", "fresh-session-working-evidence")
        })
    }

    /** Every application table, including formal data, queue, day totals, commands and working state. */
    private fun databaseSnapshot(): Map<String, TableSnapshot> {
        val tables = fixture.db.rawQuery(
            "select name from sqlite_master where type='table' and name not like 'sqlite_%' and name!='android_metadata' order by name",
            null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return tables.associateWith { table ->
            check(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(table))
            fixture.db.rawQuery("select rowid,* from $table order by rowid", null).use { cursor ->
                TableSnapshot(cursor.columnNames.toList(), buildList {
                    while (cursor.moveToNext()) add(List(cursor.columnCount) { column ->
                        val type = cursor.getType(column)
                        val value = when (type) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList().toString()
                            else -> cursor.getString(column)
                        }
                        Cell(type, value)
                    })
                })
            }
        }
    }
    private data class Cell(val type: Int, val value: String?)
    private data class TableSnapshot(val columns: List<String>, val rows: List<List<Cell>>)
}
