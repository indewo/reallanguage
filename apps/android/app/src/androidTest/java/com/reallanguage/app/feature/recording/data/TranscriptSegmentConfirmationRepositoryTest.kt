package com.reallanguage.app.feature.recording.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranscriptSegmentConfirmationRepositoryTest {
    @Test fun qualitativeUnknownAndInferenceSurviveRealJsonRoundTripWithoutInventedConfidence() = runTest {
        val f = Fixture()
        f.transcribe()
        val restored = f.reopen().load("t1") as TranscriptLoadResult.Ready
        val segments = restored.transcript.evidence!!.segments
        assertEquals(true, segments[0].needsConfirmation)
        assertNull(segments[0].confidence)
        assertEquals(listOf("我去了果园。"), segments[0].alternatives)
        assertEquals("我去了果园。", segments[0].mostLikelyText)
        assertTrue(segments[0].mostLikelyIsInference)
        assertNull(segments[1].needsConfirmation)
        assertEquals(listOf("s1", "s2"), restored.transcript.segmentReviews.map { it.segmentId })
    }

    @Test fun malformedFlagAndContradictoryInferenceNeverBecomeSuccessfulTranscripts() = runTest {
        listOf("true", JSONObject.NULL, false).forEach { flag ->
            val f = Fixture(flag = flag)
            assertEquals(TranscriptionResult.Failed(null), f.repository.transcribe(request, "transcribe-1"))
            assertFalse(File(f.directory, "t1.transcript.json").exists())
        }
    }

    @Test fun finalConfirmationCannotBypassRequiredSegmentReview() = runTest {
        val f = Fixture(); f.transcribe()
        assertEquals(TranscriptConfirmResult.Failed, f.repository.confirm("t1", original, "confirm-1"))
        assertEquals(TranscriptConfirmResult.Failed, f.repository.confirmReviewed("t1", original, 0, "confirm-1"))
        assertTrue(JSONObject(File(f.directory, "t1.transcript.json").readText()).isNull("confirmedSourceId"))
    }

    @Test fun inferenceRequiresCannotConfirmAndCurrentRevisionThenPersistsWithFinalText() = runTest {
        val f = Fixture(); f.transcribe()
        assertEquals(TranscriptSegmentUpdateResult.Failed, f.repository.updateSegment("t1", "s1", 0, TranscriptSegmentChange.ChooseInference))
        val unable = f.change("s1", 0, TranscriptSegmentChange.CannotConfirm)
        assertEquals(TranscriptSegmentReviewStatus.UNABLE_TO_CONFIRM, unable.segmentReviews[0].status)
        assertEquals(TranscriptSegmentUpdateResult.Failed, f.repository.updateSegment("t1", "s1", 0, TranscriptSegmentChange.ChooseInference))
        val chosen = f.change("s1", 1, TranscriptSegmentChange.ChooseInference)
        assertEquals("我去了果园。我去了公园。", chosen.fullText)
        assertEquals(TranscriptSegmentTextOrigin.INFERENCE, chosen.segmentReviews[0].origin)
        assertEquals(TranscriptConfirmResult.Failed, f.repository.confirmReviewed("t1", chosen.fullText, 2, "confirm-1"))
        val confirmed = f.change("s1", 2, TranscriptSegmentChange.Confirm)
        assertEquals(TranscriptSegmentReviewStatus.CONFIRMED, confirmed.segmentReviews[0].status)
        val saved = f.repository.confirmReviewed("t1", confirmed.fullText, 3, "confirm-1")
        assertTrue(saved is TranscriptConfirmResult.Confirmed)
        val reopened = f.reopen()
        assertEquals(saved, reopened.confirmReviewed("t1", confirmed.fullText, 3, "confirm-1"))
        val loaded = (reopened.load("t1") as TranscriptLoadResult.Ready).transcript
        assertEquals(confirmed.fullText, loaded.fullText)
        assertEquals(TranscriptSegmentTextOrigin.INFERENCE, loaded.segmentReviews[0].origin)
        assertTrue(loaded.segmentReviews[0].unableToConfirm)
        assertEquals(original, loaded.evidence!!.originalText)
        assertEquals("isolated-audio", File(f.directory, "r1.m4a").readText())
        assertEquals(1, f.calls)
    }

    @Test fun duplicateSentencesUseStableSegmentIdsNotFirstSubstringAndRejectWrongIdentity() = runTest {
        val f = Fixture(secondNeedsConfirmation = true); f.transcribe()
        assertEquals(TranscriptSegmentUpdateResult.Failed, f.repository.updateSegment("t1", "invented", 0, TranscriptSegmentChange.Confirm))
        f.change("s1", 0, TranscriptSegmentChange.Confirm)
        val changed = f.change("s2", 1, TranscriptSegmentChange.Edit("第二段手动改写。"))
        assertEquals("我去了公园。第二段手动改写。", changed.fullText)
        assertEquals(TranscriptSegmentTextOrigin.MANUAL, changed.segmentReviews[1].origin)
        assertEquals(TranscriptSegmentReviewStatus.PENDING, changed.segmentReviews[1].status)
        assertEquals(TranscriptSegmentUpdateResult.Failed, f.repository.updateSegment("t1", "s2", 1, TranscriptSegmentChange.Edit("迟到覆盖。")))
        assertEquals(changed, (f.reopen().load("t1") as TranscriptLoadResult.Ready).transcript)
    }

    @Test fun replayedSameSegmentMutationIsIdempotentButWholeTextCannotBypassScopedReview() = runTest {
        val f = Fixture(); f.transcribe()
        val first = f.change("s1", 0, TranscriptSegmentChange.CannotConfirm)
        assertEquals(TranscriptSegmentUpdateResult.Saved(first), f.repository.updateSegment("t1", "s1", 0, TranscriptSegmentChange.CannotConfirm))
        assertEquals(TranscriptDraftSaveResult.Failed, f.repository.saveDraft("t1", "跨段全文替换。", true, 1))
        assertEquals(first.fullText, (f.repository.load("t1") as TranscriptLoadResult.Ready).transcript.fullText)
    }

    @Test fun accountSwitchCannotConfirmOrMutatePreservedSegmentChoices() = runTest {
        val f = Fixture(); f.transcribe()
        val before = File(f.directory, "t1.transcript.json").readText()
        f.runtime.update("other-test", "other-account")
        assertEquals(TranscriptSegmentUpdateResult.Failed, f.repository.updateSegment("t1", "s1", 0, TranscriptSegmentChange.Confirm))
        assertEquals(TranscriptConfirmResult.Failed, f.repository.confirmReviewed("t1", original, 0, "confirm-1"))
        assertEquals(before, File(f.directory, "t1.transcript.json").readText())
    }

    @Test fun manuallyEditedInferenceKeepsItsOriginalInferenceMarkerAfterFinalReadBack() = runTest {
        val f = Fixture(); f.transcribe()
        f.change("s1", 0, TranscriptSegmentChange.CannotConfirm)
        f.change("s1", 1, TranscriptSegmentChange.ChooseInference)
        f.change("s1", 2, TranscriptSegmentChange.Edit("我去了果园，后来回家。"))
        val checked = f.change("s1", 3, TranscriptSegmentChange.Confirm)
        assertTrue(f.repository.confirmReviewed("t1", checked.fullText, 4, "confirm-1") is TranscriptConfirmResult.Confirmed)
        val restored = (f.reopen().load("t1") as TranscriptLoadResult.Ready).transcript
        assertEquals("我去了果园，后来回家。我去了公园。", restored.fullText)
        assertEquals(TranscriptSegmentTextOrigin.MANUAL, restored.segmentReviews[0].origin)
        assertEquals("我去了果园。", restored.segmentReviews[0].inferenceText)
        assertEquals(1, f.calls)
    }

    @Test fun mappedRepairIsSavedAndInvalidatesChangedSegmentWhileAmbiguousRepairKeepsCurrentDraft() = runTest {
        val f = Fixture(secondNeedsConfirmation = true); f.transcribe()
        f.change("s1", 0, TranscriptSegmentChange.Confirm)
        f.change("s2", 1, TranscriptSegmentChange.Confirm)
        val proposal = TranscriptRepairResult.Proposed("我去了公园。我昨天去了公园。", listOf(
            RepairChange("我去了公园。", "我昨天去了公园。", RepairReason.CONFIRMED_OMISSION, listOf("s2")),
        ))
        val saved = f.repository.applyReviewedRepair("t1", 2, proposal) as TranscriptSegmentUpdateResult.Saved
        assertEquals(proposal.repairedText, saved.transcript.fullText)
        assertEquals(TranscriptSegmentReviewStatus.CONFIRMED, saved.transcript.segmentReviews[0].status)
        assertEquals(TranscriptSegmentReviewStatus.PENDING, saved.transcript.segmentReviews[1].status)
        val before = File(f.directory, "t1.transcript.json").readText()
        val ambiguous = proposal.copy(changes = proposal.changes.map { it.copy(evidenceSegmentIds = listOf("s1", "s2")) })
        assertEquals(TranscriptSegmentUpdateResult.Failed, f.repository.applyReviewedRepair("t1", 3, ambiguous))
        assertEquals(before, File(f.directory, "t1.transcript.json").readText())
        assertEquals(saved.transcript, (f.reopen().load("t1") as TranscriptLoadResult.Ready).transcript)
    }

    private class Fixture(flag: Any = true, secondNeedsConfirmation: Boolean = false) {
        val directory: File = Files.createTempDirectory("transcript-segment-review-test").toFile()
        val runtime = SessionRuntime().apply { update("isolated-test", "account-1") }
        var calls = 0
        private val json = JSONObject().put("transcriptId", "t1").put("language", "zh-CN").put("fullText", original)
            .put("segments", JSONArray().put(segment("s1", 0, 2000).put("needsConfirmation", flag)
                .put("alternatives", JSONArray().put("我去了果园。")).put("mostLikelyText", "我去了果园。").put("mostLikelyIsInference", true))
                .put(segment("s2", 2000, 4000).apply { if (secondNeedsConfirmation) put("needsConfirmation", true) }))
            .put("finalBoundary", JSONObject().put("acceptedEndMs", 4000).put("droppedIncompleteTail", false))
        val repository = HttpRecordingRepositories(directory, object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse { calls++; return HttpResponse(200, json.toString()) }
        }, runtime) { RecordingPlaybackResult.Stopped }
        init { File(directory, "r1.m4a").writeText("isolated-audio") }
        suspend fun transcribe() { assertEquals(TranscriptionResult.Completed("t1"), repository.transcribe(request, "transcribe-1")) }
        suspend fun change(id: String, revision: Int, change: TranscriptSegmentChange): RecordingTranscript =
            (repository.updateSegment("t1", id, revision, change) as TranscriptSegmentUpdateResult.Saved).transcript
        fun reopen() = HttpRecordingRepositories(directory, object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = error("Reload must not call AI")
        }, runtime) { RecordingPlaybackResult.Stopped }
        private fun segment(id: String, start: Int, end: Int) = JSONObject().put("segmentId", id).put("startMs", start).put("endMs", end)
            .put("text", "我去了公园。").put("speakerLabel", "UNKNOWN").put("confidence", JSONObject.NULL)
            .put("alternatives", JSONArray()).put("mostLikelyText", JSONObject.NULL).put("mostLikelyIsInference", false)
    }
    companion object {
        private const val original = "我去了公园。我去了公园。"
        private val request = TranscriptionRequest("r1", 4, "audio/mp4", "zh-CN", RecordingEndReason.USER_STOPPED)
    }
}
