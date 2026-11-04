package com.reallanguage.app.feature.review.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.review.domain.*
import com.reallanguage.app.testing.ReviewNoJudgmentFixture
import com.reallanguage.app.testing.receiptContext
import com.reallanguage.app.testing.receiptFixture
import com.reallanguage.app.testing.asDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PatternNoJudgmentPersistenceTest {
    private val fixture = ReviewNoJudgmentFixture(InstrumentationRegistry.getInstrumentation().targetContext)
    @After fun cleanOnlyUuidFixture() { fixture.close() }
    private fun repository(runtime: SessionRuntime = fixture.runtime) = AndroidReviewChapterRepository(fixture.context, runtime) { fixture.today }
    private suspend fun snapshot(reviewId: String) = (repository().loadChapter(reviewId) as ReviewChapterLoadResult.Ready).chapter

    @Test fun unavailableScenePreservesAllFormalRowsCountsHandledSentenceOnceAndContinuesNextChapter() = runBlocking {
        fixture.seed(chapterCount = 2)
        val queue = fixture.queue()
        val first = snapshot(queue.first().reviewChapterId)
        val before = fixture.formalSnapshot()
        val drafts = first.abilities.map { ReviewAbilityResultDraft(it.abilityId, "", false, ReviewResultQualification.NOT_NATURALLY_VERIFIABLE) }
        val result = repository().commitChapter(first.reviewChapterId, "unavailable-command", drafts)
        assertTrue("An explicitly handled but unjudged pattern must not become a failure or strand the chapter", result is ReviewChapterCommitResult.Committed)
        result as ReviewChapterCommitResult.Committed
        assertEquals(queue[1].reviewChapterId, result.nextReviewChapterId)
        assertTrue(result.reviewSessionId.isNotBlank())
        assertEquals(before, fixture.formalSnapshot())
        assertEquals(1, fixture.scalar("select completed_focus_count from review_sessions where account_id=?"))
        assertEquals(1, fixture.count("review_chapter_results"))
        assertTrue(repository().commitChapter(first.reviewChapterId, "unavailable-command", drafts) is ReviewChapterCommitResult.AlreadyCommitted)
        assertEquals(before, fixture.formalSnapshot())
        assertEquals(1, fixture.scalar("select completed_focus_count from review_sessions where account_id=?"))
        assertEquals(ReviewChapterCommitResult.Failed, repository().commitChapter(first.reviewChapterId, "unavailable-command",
            drafts.map { it.copy(evidenceId = "pretend-audio", qualification = ReviewResultQualification.INDEPENDENT) }))
    }

    @Test fun unavailableQualificationRejectsInventedEvidenceOrPretendPassAtomically() = runBlocking {
        fixture.seed()
        val chapter = snapshot(fixture.queue().single().reviewChapterId)
        val before = fixture.formalSnapshot()
        for ((evidence, passed) in listOf("fake-audio" to false, "fake-audio" to true, "" to true)) {
            val result = repository().commitChapter(chapter.reviewChapterId, "invalid-$evidence-$passed",
                chapter.abilities.map { ReviewAbilityResultDraft(it.abilityId, evidence, passed, ReviewResultQualification.NOT_NATURALLY_VERIFIABLE) })
            assertEquals(ReviewChapterCommitResult.Failed, result)
            assertEquals(before, fixture.formalSnapshot())
            assertEquals(0, fixture.count("review_chapter_results"))
            assertEquals(0, fixture.count("review_sessions"))
        }
    }

    @Test fun unavailableQualificationCannotSkipPatternWritingAndCannotCrossAccount() = runBlocking {
        fixture.seed(capability = "PATTERN_WRITING")
        val chapter = snapshot(fixture.queue().single().reviewChapterId)
        val before = fixture.formalSnapshot()
        for (evidence in listOf("", "fake-audio")) assertEquals(ReviewChapterCommitResult.Failed,
            repository().commitChapter(chapter.reviewChapterId, "wrong-module-$evidence", chapter.abilities.map {
                ReviewAbilityResultDraft(it.abilityId, evidence, false, ReviewResultQualification.NOT_NATURALLY_VERIFIABLE)
            }))
        val other = SessionRuntime().apply { update("isolated-test-other", "other-${fixture.runId}") }
        assertEquals(ReviewChapterCommitResult.Failed, repository(other).commitChapter(chapter.reviewChapterId, "other",
            chapter.abilities.map { ReviewAbilityResultDraft(it.abilityId, "", false, ReviewResultQualification.NOT_NATURALLY_VERIFIABLE) }))
        assertEquals(before, fixture.formalSnapshot())
        assertEquals(0, fixture.count("review_chapter_results"))
    }

    @Test fun anActualIndependentPassStillIncreasesStreakAndRetiresItsObligation() = runBlocking {
        fixture.seed()
        val chapter = snapshot(fixture.queue().single().reviewChapterId)
        val (lease, identity) = fixture.receiptContext()
        val receipt = identity.receiptFixture(true)
        assertTrue(AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity).register(receipt))
        assertTrue(AndroidReviewChapterRepository(fixture.context, fixture.runtime, lease).commitChapter(chapter.reviewChapterId,
            "isolated-pass", listOf(receipt.asDraft())) is ReviewChapterCommitResult.Committed)
        assertEquals(3, fixture.scalar("select speaking_streak from pattern_items where account_id=?"))
        assertEquals(1, fixture.scalar("select count(*) from pattern_items where account_id=? and speaking_status='VERIFIED'"))
        assertEquals(0, fixture.count("learning_obligations")); assertEquals(0, fixture.count("correction_flags"))
    }

    @Test fun anActualIndependentFailureStillResetsStreakAndAddsTheWrongItem() = runBlocking {
        fixture.seed(existingWrongFlag = false)
        val chapter = snapshot(fixture.queue().single().reviewChapterId)
        val (lease, identity) = fixture.receiptContext()
        val receipt = identity.receiptFixture(false)
        assertTrue(AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity).register(receipt))
        assertTrue(AndroidReviewChapterRepository(fixture.context, fixture.runtime, lease).commitChapter(chapter.reviewChapterId,
            "isolated-fail", listOf(receipt.asDraft())) is ReviewChapterCommitResult.Committed)
        assertEquals(0, fixture.scalar("select speaking_streak from pattern_items where account_id=?"))
        assertEquals(1, fixture.scalar("select count(*) from pattern_items where account_id=? and speaking_status='LEARNING'"))
        assertEquals(1, fixture.count("learning_obligations")); assertEquals(1, fixture.count("correction_flags"))
    }
}
