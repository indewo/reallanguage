package com.reallanguage.app.feature.recording.domain

import org.junit.Assert.*
import org.junit.Test

class TranscriptSegmentReviewRulesTest {
    @Test fun changingSecondIdenticalSentenceNeverSelectsTheFirstOccurrenceAndKeepsSeparators() {
        val (evidence, reviews) = fixture()
        val changed = TranscriptSegmentReviewRules.change(evidence, reviews, "s2", TranscriptSegmentChange.Edit("第二段。"))
        assertEquals("  我去了公园。\n第二段。 ", TranscriptSegmentReviewRules.render(evidence, changed))
        assertEquals("我去了公园。", changed[0].text)
    }

    @Test fun currentSegmentCandidateCannotComeFromAnotherSegmentOrBeAcceptedBeforeUserCannotConfirm() {
        val (evidence, reviews) = fixture()
        assertTrue(runCatching { TranscriptSegmentReviewRules.change(evidence, reviews, "s2", TranscriptSegmentChange.ChooseAlternative(0)) }.isFailure)
        assertTrue(runCatching { TranscriptSegmentReviewRules.change(evidence, reviews, "s1", TranscriptSegmentChange.ChooseInference) }.isFailure)
    }

    @Test fun manualEditAfterInferenceKeepsItsProvenanceAndInvalidatesPreviousConfirmation() {
        val (evidence, initial) = fixture()
        var reviews = TranscriptSegmentReviewRules.change(evidence, initial, "s1", TranscriptSegmentChange.CannotConfirm)
        reviews = TranscriptSegmentReviewRules.change(evidence, reviews, "s1", TranscriptSegmentChange.ChooseInference)
        reviews = TranscriptSegmentReviewRules.change(evidence, reviews, "s1", TranscriptSegmentChange.Confirm)
        reviews = TranscriptSegmentReviewRules.change(evidence, reviews, "s1", TranscriptSegmentChange.Edit("我去了果园，随后回家。"))
        assertEquals(TranscriptSegmentReviewStatus.PENDING, reviews[0].status)
        assertEquals(TranscriptSegmentTextOrigin.MANUAL, reviews[0].origin)
        assertEquals("我去了果园。", reviews[0].inferenceText)
        assertFalse(TranscriptSegmentReviewRules.canConfirm(evidence, reviews))
    }

    @Test fun choosingOriginalExplicitlyRemovesThePreviouslyAdoptedInference() {
        val (evidence, initial) = fixture()
        var reviews = TranscriptSegmentReviewRules.change(evidence, initial, "s1", TranscriptSegmentChange.CannotConfirm)
        reviews = TranscriptSegmentReviewRules.change(evidence, reviews, "s1", TranscriptSegmentChange.ChooseInference)
        reviews = TranscriptSegmentReviewRules.change(evidence, reviews, "s1", TranscriptSegmentChange.ChooseOriginal)
        assertNull(reviews[0].inferenceText)
        assertFalse(reviews[0].unableToConfirm)
        assertEquals(TranscriptSegmentReviewStatus.PENDING, reviews[0].status)
    }

    @Test fun repairUsesExplicitSegmentIdentityAndInvalidatesOnlyChangedSegment() {
        val (evidence, initial) = fixture()
        var reviews = TranscriptSegmentReviewRules.change(evidence, initial, "s1", TranscriptSegmentChange.Confirm)
        reviews = TranscriptSegmentReviewRules.change(evidence, reviews, "s2", TranscriptSegmentChange.Confirm)
        val proposal = TranscriptRepairResult.Proposed("  我去了公园。\n我昨天去了公园。 ", listOf(
            RepairChange("我去了公园。", "我昨天去了公园。", RepairReason.CONFIRMED_OMISSION, listOf("s2")),
        ))
        val changed = TranscriptSegmentReviewRules.repair(evidence, reviews, proposal)
        assertEquals(TranscriptSegmentReviewStatus.CONFIRMED, changed[0].status)
        assertEquals(TranscriptSegmentReviewStatus.PENDING, changed[1].status)
        assertEquals(TranscriptSegmentTextOrigin.AI_REPAIR, changed[1].origin)
    }

    @Test fun repairRejectsCrossSegmentIdentityAndRepeatedOccurrencesWithinCurrentSegment() {
        val (evidence, reviews) = fixture()
        val cross = TranscriptRepairResult.Proposed("改写后的不同文本。", listOf(
            RepairChange("我去了公园。", "我去了果园。", RepairReason.CONFIRMED_DISFLUENCY, listOf("s1", "s2")),
        ))
        assertTrue(runCatching { TranscriptSegmentReviewRules.repair(evidence, reviews, cross) }.isFailure)
        val doubled = TranscriptSegmentReviewRules.change(evidence, reviews, "s1", TranscriptSegmentChange.Edit("我去了公园。我去了公园。"))
        val ambiguous = cross.copy(changes = cross.changes.map { it.copy(evidenceSegmentIds = listOf("s1")) })
        assertTrue(runCatching { TranscriptSegmentReviewRules.repair(evidence, doubled, ambiguous) }.isFailure)
    }

    @Test fun unknownConfidenceAndMissingQualitativeMarkerNeverBecomeRequiredOrReliableAutomatically() {
        val (evidence, _) = fixture()
        assertFalse(TranscriptSegmentReviewRules.required(evidence.copy(segments = evidence.segments.map { it.copy(needsConfirmation = null) })))
        assertTrue(TranscriptSegmentReviewRules.required(evidence))
    }

    private fun fixture(): Pair<RecordingTranscriptEvidence, List<TranscriptSegmentReview>> {
        val segments = listOf(
            RecordingTranscriptSegment("s1", 0, 2000, "我去了公园。", "UNKNOWN", null, listOf("我去了果园。"), "我去了果园。", true, true),
            RecordingTranscriptSegment("s2", 2000, 4000, "我去了公园。", "UNKNOWN", null, emptyList(), null, false, true),
        )
        val evidence = RecordingTranscriptEvidence("  我去了公园。\n我去了公园。 ", "zh-CN", 4, RecordingEndReason.USER_STOPPED, segments, 4000, false)
        return evidence to TranscriptSegmentReviewRules.initial(evidence)
    }
}
