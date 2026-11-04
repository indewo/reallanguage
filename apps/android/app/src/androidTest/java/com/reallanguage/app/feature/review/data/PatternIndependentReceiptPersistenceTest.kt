package com.reallanguage.app.feature.review.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.feature.review.domain.*
import com.reallanguage.app.testing.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PatternIndependentReceiptPersistenceTest {
    private val fixture = ReviewNoJudgmentFixture(InstrumentationRegistry.getInstrumentation().targetContext)
    @After fun cleanOnlyUuidFixture() { fixture.close() }

    @Test fun registeredHashIsReadBackBeforeCommitAndHistoricalReplayDoesNotNeedDeletedTemporaryEvidence() = runBlocking {
        fixture.seed()
        val (lease, identity) = fixture.receiptContext()
        val receipt = identity.receiptFixture()
        val store = AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity)
        assertTrue(store.register(receipt))
        assertEquals(receipt.evidenceId, storedEvidence(identity))
        assertTrue(receipt.evidenceId.matches(Regex("pattern-review-v1:[a-f0-9]{64}")))
        val repository = AndroidReviewChapterRepository(fixture.context, fixture.runtime, lease) { fixture.today }
        assertTrue(repository.commitChapter(identity.reviewChapterId, "real-receipt-commit", listOf(receipt.asDraft())) is ReviewChapterCommitResult.Committed)
        assertEquals(0, fixture.count("review_working_state"))
        assertEquals(3, fixture.scalar("select speaking_streak from pattern_items where account_id=?"))
        lease.revoke()
        // The already-committed digest must remain replayable without a live entry or a deleted working row.
        assertTrue(AndroidReviewChapterRepository(fixture.context, fixture.runtime).commitChapter(identity.reviewChapterId,
            "real-receipt-commit", listOf(receipt.asDraft())) is ReviewChapterCommitResult.AlreadyCommitted)
        assertEquals(1, fixture.count("review_chapter_results"))
    }

    @Test fun aRandomNonblankIdentifierCannotCreateANewIndependentPatternResult() = runBlocking {
        fixture.seed()
        val (lease, identity) = fixture.receiptContext()
        val before = fixture.formalSnapshot()
        val result = AndroidReviewChapterRepository(fixture.context, fixture.runtime, lease).commitChapter(identity.reviewChapterId,
            "no-receipt", listOf(ReviewAbilityResultDraft(identity.abilityId, "random-not-evidence", true)))
        assertEquals(ReviewChapterCommitResult.Failed, result)
        assertEquals(before, fixture.formalSnapshot())
        assertEquals(0, fixture.count("review_chapter_results"))
    }

    @Test fun wrongQueueTargetEntryOrDecisionCannotBorrowARegisteredReceipt() = runBlocking {
        fixture.seed()
        val (lease, identity) = fixture.receiptContext()
        val receipt = identity.receiptFixture()
        val store = AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity)
        assertTrue(store.register(receipt))
        val before = fixture.formalSnapshot()
        for (changed in listOf(identity.copy(abilityId = "another-queue"), identity.copy(targetId = "another-target"),
            identity.copy(entryId = "another-entry"), identity.copy(sourceSentenceId = "another-sentence"))) {
            assertFalse(store.register(receipt.copy(identity = changed)))
        }
        val tampered = receipt.copy(decision = PatternIndependentDecision.FAIL, structurePreserved = false)
        val result = AndroidReviewChapterRepository(fixture.context, fixture.runtime, lease).commitChapter(identity.reviewChapterId,
            "changed-decision", listOf(receipt.asDraft().copy(passed = false, patternReceipt = tampered)))
        assertEquals(ReviewChapterCommitResult.Failed, result)
        assertEquals(before, fixture.formalSnapshot())
        assertEquals(receipt.evidenceId, storedEvidence(identity))
    }

    @Test fun revokedEntryAndNewLoginCannotRegisterOrCommitAnOldInMemoryReceipt() = runBlocking {
        fixture.seed()
        val (lease, identity) = fixture.receiptContext()
        val receipt = identity.receiptFixture()
        val store = AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity)
        assertTrue(store.register(receipt))
        val before = fixture.formalSnapshot()
        lease.revoke()
        assertFalse(store.register(receipt.copy(commandId = "late-command")))
        assertEquals(ReviewChapterCommitResult.Failed, AndroidReviewChapterRepository(fixture.context, fixture.runtime, lease)
            .commitChapter(identity.reviewChapterId, "revoked", listOf(receipt.asDraft())))
        fixture.runtime.clear(); fixture.runtime.update("isolated-new-login", fixture.account)
        val newLease = ReviewPatternEntryLease(fixture.account, fixture.runtime.accountEpoch, identity.reviewChapterId, "new-entry")
        assertEquals(ReviewChapterCommitResult.Failed, AndroidReviewChapterRepository(fixture.context, fixture.runtime, newLease)
            .commitChapter(identity.reviewChapterId, "new-login-old-receipt", listOf(receipt.asDraft())))
        assertEquals(before, fixture.formalSnapshot())
        assertEquals(0, fixture.count("review_chapter_results"))
    }

    @Test fun missingOrChangedWorkingCommitmentRejectsTheWholeChapterBeforeStateChanges() = runBlocking {
        fixture.seed()
        val (lease, identity) = fixture.receiptContext()
        val receipt = identity.receiptFixture()
        val store = AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity)
        assertTrue(store.register(receipt))
        val before = fixture.formalSnapshot()
        fixture.db.execSQL("update review_working_state set evidence_id='changed' where account_id=?", arrayOf(fixture.account))
        assertEquals(ReviewChapterCommitResult.Failed, AndroidReviewChapterRepository(fixture.context, fixture.runtime, lease)
            .commitChapter(identity.reviewChapterId, "changed-row", listOf(receipt.asDraft())))
        assertEquals(before, fixture.formalSnapshot()); assertEquals(0, fixture.count("review_sessions"))
    }

    @Test fun aFreshLeaseWithTheSameFieldsCannotBorrowAnOldPersistedCommitment() = runBlocking {
        fixture.seed()
        val (lease, identity) = fixture.receiptContext()
        val receipt = identity.receiptFixture()
        assertTrue(AndroidPatternIndependentReceiptStore(fixture.context, fixture.runtime, lease, identity).register(receipt))
        val before = fixture.formalSnapshot()
        lease.revoke()
        val reconstructedLease = ReviewPatternEntryLease(identity.accountId, identity.accountEpoch, identity.reviewChapterId, identity.entryId)
        assertEquals(ReviewChapterCommitResult.Failed,
            AndroidReviewChapterRepository(fixture.context, fixture.runtime, reconstructedLease)
                .commitChapter(identity.reviewChapterId, "not-the-live-receipt-entry", listOf(receipt.asDraft())))
        assertEquals(before, fixture.formalSnapshot())
        assertEquals(receipt.evidenceId, storedEvidence(identity))
        assertEquals(0, fixture.count("review_chapter_results"))
    }

    private fun storedEvidence(identity: PatternIndependentReceiptIdentity): String? = fixture.db.rawQuery(
        "select evidence_id from review_working_state where account_id=? and review_chapter_id=? and ability_id=?",
        arrayOf(identity.accountId, identity.reviewChapterId, identity.abilityId),
    ).use { if (it.moveToFirst()) it.getString(0) else null }
}
