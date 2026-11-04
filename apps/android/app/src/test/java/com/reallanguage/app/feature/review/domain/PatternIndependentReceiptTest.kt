package com.reallanguage.app.feature.review.domain

import org.junit.Assert.*
import org.junit.Test

class PatternIndependentReceiptTest {
    private val identity = PatternIndependentReceiptIdentity("account", 1, "review", "entry", "chapter", "sentence", "ability", "target")
    private val valid = PatternIndependentReceipt(1, identity, "example", "command", "a".repeat(64), "b".repeat(64),
        PatternIndependentDecision.PASS, true, true, true, .9)

    @Test fun canonicalCommitmentBindsEveryIdentityAndResponseFieldWithoutAudioText() {
        assertTrue(valid.evidenceId.matches(Regex("pattern-review-v1:[a-f0-9]{64}")))
        assertEquals(valid.evidenceId, valid.copy().evidenceId)
        val variants = listOf(
            valid.copy(version = 2), valid.copy(identity = identity.copy(accountId = "other")),
            valid.copy(identity = identity.copy(accountEpoch = 2)), valid.copy(identity = identity.copy(reviewChapterId = "other")),
            valid.copy(identity = identity.copy(entryId = "other")), valid.copy(identity = identity.copy(originalChapterId = "other")),
            valid.copy(identity = identity.copy(sourceSentenceId = "other")), valid.copy(identity = identity.copy(abilityId = "other")),
            valid.copy(identity = identity.copy(targetId = "other")), valid.copy(exampleId = "other"), valid.copy(commandId = "other"),
            valid.copy(audioSha256 = "c".repeat(64)), valid.copy(requestSha256 = "d".repeat(64)),
            valid.copy(decision = PatternIndependentDecision.FAIL), valid.copy(structurePreserved = false),
            valid.copy(contentSuitable = false), valid.copy(pronunciationIntelligible = false), valid.copy(confidence = .8),
        )
        variants.forEach { assertNotEquals(valid.evidenceId, it.evidenceId) }
    }

    @Test fun onlyHighConfidenceConsistentPassOrFailCanBecomeAnIndependentReceipt() {
        assertTrue(valid.hasValidIndependentJudgment())
        assertTrue(valid.copy(decision = PatternIndependentDecision.FAIL, structurePreserved = false).hasValidIndependentJudgment())
        assertFalse(valid.copy(decision = PatternIndependentDecision.FAIL).hasValidIndependentJudgment())
        assertFalse(valid.copy(contentSuitable = false).hasValidIndependentJudgment())
        assertFalse(valid.copy(confidence = .599).hasValidIndependentJudgment())
        assertFalse(valid.copy(decision = PatternIndependentDecision.INCONCLUSIVE).hasValidIndependentJudgment())
    }

    @Test fun invalidBindingVersionHashAndNonFiniteConfidenceNeverBecomeProof() {
        for (receipt in listOf(valid.copy(version = 0), valid.copy(audioSha256 = "not-a-hash"),
            valid.copy(requestSha256 = ""), valid.copy(confidence = Double.NaN), valid.copy(confidence = 1.1),
            valid.copy(identity = identity.copy(entryId = "")), valid.copy(identity = identity.copy(accountEpoch = -1)))) {
            assertFalse(receipt.hasValidIndependentJudgment())
        }
    }

    @Test fun leaseBelongsToOneAccountEpochAndCannotReviveAfterRevocation() {
        val lease = ReviewPatternEntryLease("account", 1, "review", "entry")
        assertTrue(lease.isActiveFor("account", 1))
        assertFalse(lease.isActiveFor("other", 1)); assertFalse(lease.isActiveFor("account", 2))
        lease.revoke()
        assertFalse(lease.isActiveFor("account", 1))
        assertTrue(ReviewPatternEntryLease("account", 2, "review", "new-entry").isActiveFor("account", 2))
    }
}
