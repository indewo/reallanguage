package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

/** Isolated transport/codec: request identity and storage ordering, not audio quality. */
class ReadSpeakDurableRequestTest {
    private val signal = ReadSpeakCompletionSignal.TOUCH_REACHED_END
    private val ready = ReadSpeakFeedbackResult.Ready("隔离已交付反馈")

    @Test fun retryWithoutAnySubmittedEvidenceCannotFinishCaptureOrCreateTraining() = runTest {
        val capture = Capture()
        var requests = 0
        var saves = 0
        try {
            val repository = repository(MemoryJournal(), capture, HttpTransport { requests++; HttpResponse(503, "") },
                LearningStepStore { _, _, _, _, _ -> saves++; true })
            assertEquals(ReadSpeakFeedbackRecovery.None, repository.restoreFeedback())
            assertEquals(ReadSpeakFeedbackResult.Failed,
                repository.retryFeedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION))
            assertEquals(0, capture.finishes); assertEquals(0, requests); assertEquals(0, saves)
        } finally { capture.file.delete() }
    }

    @Test fun cachedPendingBodyCannotBypassRevokedOrUnreadableDurableEvidence() = runTest {
        val journal = MemoryJournal()
        val capture = Capture()
        var requests = 0
        try {
            val repository = repository(journal, capture, HttpTransport { requests++; HttpResponse(503, "") })
            repository.prepareCapture(); repository.startCapture(); repository.feedback("s1", signal)
            assertEquals(1, requests)
            journal.unreadable = true
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.retryFeedback("s1", signal))
            assertEquals("Cached body cannot substitute for current account/source/storage authorization", 1, requests)
            assertEquals(ReadSpeakRecoveryPhase.Pending, journal.snapshot?.phase)
        } finally { capture.file.delete() }
    }

    @Test fun lostPendingSaveAcknowledgementDoesNotBlockExplicitManualFallback() = runTest {
        val stored = MemoryJournal()
        var loseOnce = true
        val journal = object : ReadSpeakRecoveryJournal {
            override suspend fun load() = stored.load()
            override suspend fun save(expectedRevision: Long?, snapshot: ReadSpeakRecoverySnapshot): Long? {
                val saved = stored.save(expectedRevision, snapshot)
                return if (loseOnce) { loseOnce = false; null } else saved
            }
        }
        val capture = Capture()
        var requests = 0
        var saves = 0
        try {
            val repository = repository(journal, capture, HttpTransport { requests++; HttpResponse(503, "") },
                LearningStepStore { _, _, _, _, _ -> saves++; true })
            repository.prepareCapture(); repository.startCapture()
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", signal))
            assertEquals(ReadSpeakRecoveryPhase.Pending, stored.snapshot?.phase)
            assertEquals(0, requests)
            assertEquals(ReadSpeakFeedbackResult.CompletedWithoutAudio,
                repository.feedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION))
            assertEquals(1, saves); assertEquals(0, requests)
            assertEquals(ReadSpeakRecoveryPhase.Completed, stored.snapshot?.phase)
        } finally { capture.file.delete() }
    }

    @Test fun consumedPartialRecognitionCanStillBeExplicitlyConfirmedWithoutInventingAudioFeedback() = runTest {
        val journal = MemoryJournal()
        journal.save(null, ReadSpeakRecoverySnapshot(ReadSpeakCompletionSignal.RECOGNITION_COMPLETE,
            UUID.randomUUID().toString(), null, ReadSpeakFeedbackResult.Ready("未读完整", false), ReadSpeakRecoveryPhase.Completed))
        val capture = Capture()
        var saves = 0
        var requests = 0
        try {
            val repository = repository(journal, capture, HttpTransport { requests++; error("No audio request authorized") },
                LearningStepStore { _, _, _, _, _ -> saves++; true })
            assertEquals(ReadSpeakFeedbackRecovery.Completed(ReadSpeakCompletionSignal.RECOGNITION_COMPLETE,
                ReadSpeakFeedbackResult.Ready("未读完整", false)), repository.restoreFeedback())
            assertEquals(0, saves)
            assertEquals(ReadSpeakFeedbackResult.CompletedWithoutAudio,
                repository.feedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION))
            assertEquals(1, saves); assertEquals(0, requests); assertEquals(0, capture.finishes)
        } finally { capture.file.delete() }
    }

    @Test fun deliveredManualFallbackStillRequiresTheFailedTrainingSaveOnExplicitRetry() = runTest {
        val journal = MemoryJournal()
        val capture = Capture()
        var requests = 0
        val transport = HttpTransport { requests++; HttpResponse(503, "") }
        try {
            val first = repository(journal, capture, transport, LearningStepStore { _, _, _, _, _ -> false })
            first.prepareCapture(); first.startCapture(); first.feedback("s1", signal)
            assertEquals(ReadSpeakFeedbackResult.Failed, first.feedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION))
            assertEquals(ReadSpeakRecoveryPhase.Delivered, journal.snapshot?.phase)
            assertEquals(ReadSpeakFeedbackResult.CompletedWithoutAudio, journal.snapshot?.delivered)
            val nextCapture = Capture()
            try {
                var saves = 0
                val next = repository(journal, nextCapture, transport, LearningStepStore { _, _, _, _, _ -> saves++; true })
                assertEquals(ReadSpeakFeedbackRecovery.Pending(ReadSpeakCompletionSignal.MANUAL_CONFIRMATION), next.restoreFeedback())
                assertEquals(ReadSpeakFeedbackResult.CompletedWithoutAudio,
                    next.retryFeedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION))
                assertEquals("A durable candidate is not proof that SQL succeeded", 1, saves)
                assertEquals(1, requests); assertEquals(0, nextCapture.finishes)
                assertEquals(ReadSpeakRecoveryPhase.Completed, journal.snapshot?.phase)
            } finally { nextCapture.file.delete() }
        } finally { capture.file.delete() }
    }

    @Test fun retryOfUserFinishedRecordingIsNotAnImplicitManualNoAudioFallback() = runTest {
        val journal = MemoryJournal()
        val capture = Capture()
        val requests = mutableListOf<HttpRequest>()
        val transport = HttpTransport { requests += it; HttpResponse(if (requests.size == 1) 503 else 200, "isolated") }
        try {
            val first = repository(journal, capture, transport)
            first.prepareCapture(); first.startCapture()
            assertEquals(ReadSpeakFeedbackResult.Failed, first.feedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION))
            assertEquals(ready, first.retryFeedback("s1", ReadSpeakCompletionSignal.MANUAL_CONFIRMATION))
            assertEquals(2, requests.size); assertEquals(requests[0].body, requests[1].body)
            assertEquals(1, capture.finishes)
        } finally { capture.file.delete() }
    }

    @Test fun unknownOriginalSurvivesNewRepositoryWithoutAnotherCaptureOrNewCommand() = runTest {
        val journal = MemoryJournal()
        val capture = Capture()
        val requests = mutableListOf<HttpRequest>()
        val transport = HttpTransport { request -> requests += request; HttpResponse(if (requests.size == 1) 503 else 200, "isolated") }
        try {
            val first = repository(journal, capture, transport)
            assertTrue(first.prepareCapture()); assertTrue(first.startCapture())
            assertEquals(ReadSpeakFeedbackResult.Failed, first.feedback("s1", signal))
            assertNotNull("Frozen request must be durably acknowledged before POST", journal.snapshot)
            // No dispose/cancel: models abrupt process death, not a user discard.
            val nextCapture = Capture()
            try {
                val next = repository(journal, nextCapture, transport)
                assertEquals(ReadSpeakFeedbackRecovery.Pending(signal), next.restoreFeedback())
                assertEquals(1, requests.size)
                assertTrue(next.hasPendingFeedbackRetry())
                assertFalse(next.prepareCapture())
                assertEquals(ready, next.feedback("s1", signal))
                assertEquals(requests[0].body, requests[1].body)
                assertEquals(0, nextCapture.starts); assertEquals(0, nextCapture.finishes)
                assertEquals(1, capture.finishes)
            } finally { nextCapture.file.delete() }
        } finally { capture.file.delete() }
    }

    @Test fun failedDurableSaveDoesNotSendFirstRequestAndRetriesWithoutFinishingTwice() = runTest {
        val journal = MemoryJournal().apply { rejectSave = true }
        val capture = Capture()
        var requests = 0
        try {
            val repository = repository(journal, capture, HttpTransport { requests++; HttpResponse(200, "isolated") })
            repository.prepareCapture(); repository.startCapture()
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", signal))
            assertEquals(0, requests); assertTrue(capture.file.exists())
            journal.rejectSave = false
            assertEquals(ready, repository.feedback("s1", signal))
            assertEquals(1, requests); assertEquals(1, capture.finishes)
        } finally { capture.file.delete() }
    }

    @Test fun deliveredCandidateSurvivesLocalCompletionFailureWithoutSecondHttp() = runTest {
        val journal = MemoryJournal()
        val capture = Capture()
        var requests = 0
        val transport = HttpTransport { requests++; HttpResponse(200, "isolated") }
        try {
            val first = repository(journal, capture, transport, LearningStepStore { _, _, _, _, _ -> false })
            first.prepareCapture(); first.startCapture()
            assertEquals(ReadSpeakFeedbackResult.Failed, first.feedback("s1", signal))
            assertEquals(ReadSpeakRecoveryPhase.Delivered, journal.snapshot?.phase)
            val nextCapture = Capture()
            try {
                var saves = 0
                val next = repository(journal, nextCapture, transport, LearningStepStore { _, _, _, _, _ -> saves++; true })
                assertEquals(ReadSpeakFeedbackRecovery.Pending(signal), next.restoreFeedback())
                assertEquals(ready, next.feedback("s1", signal))
                assertEquals(1, requests); assertEquals(1, saves); assertEquals(0, nextCapture.finishes)
                assertEquals(ReadSpeakRecoveryPhase.Completed, journal.snapshot?.phase)
            } finally { nextCapture.file.delete() }
        } finally { capture.file.delete() }
    }

    @Test fun corruptRecoveryBlocksCaptureAndHttpInsteadOfOverwritingEvidence() = runTest {
        val journal = MemoryJournal().apply { unreadable = true }
        val capture = Capture()
        var requests = 0
        try {
            val repository = repository(journal, capture, HttpTransport { requests++; HttpResponse(200, "") })
            assertEquals(ReadSpeakFeedbackRecovery.Unavailable, repository.restoreFeedback())
            assertFalse(repository.prepareCapture()); assertFalse(repository.startCapture())
            assertEquals(ReadSpeakFeedbackResult.Failed, repository.feedback("s1", signal))
            assertEquals(0, capture.starts); assertEquals(0, capture.finishes); assertEquals(0, requests)
            assertEquals(0, journal.writes)
        } finally { capture.file.delete() }
    }

    @Test fun terminalIdentitySurvivesReentryAndOnlyExplicitNewCaptureClearsIt() = runTest {
        val journal = MemoryJournal()
        val capture = Capture()
        var requests = 0
        val transport = HttpTransport { requests++; HttpResponse(410, "") }
        try {
            val first = repository(journal, capture, transport)
            first.prepareCapture(); first.startCapture(); first.feedback("s1", signal)
            assertEquals(ReadSpeakRecoveryPhase.Blocked, journal.snapshot?.phase)
            val nextCapture = Capture()
            try {
                val next = repository(journal, nextCapture, transport)
                assertEquals(ReadSpeakFeedbackRecovery.Blocked, next.restoreFeedback())
                assertEquals(ReadSpeakFeedbackResult.Failed, next.feedback("s1", signal))
                assertEquals(1, requests); assertEquals(0, nextCapture.finishes)
                assertTrue(next.prepareCapture()); assertTrue(next.startCapture())
                assertEquals(ReadSpeakRecoveryPhase.Cleared, journal.snapshot?.phase)
            } finally { nextCapture.file.delete() }
        } finally { capture.file.delete() }
    }

    private fun repository(journal: ReadSpeakRecoveryJournal, capture: Capture, transport: HttpTransport,
        steps: LearningStepStore = LearningStepStore { _, _, _, _, _ -> true }) = AndroidReadSpeakRepository(
        transport, SessionRuntime().apply { update("isolated-token", "isolated-account") }, "c1", "I drink tea.",
        UUID.randomUUID().toString(), capture, steps, object : ReadSpeakWireCodec {
            override fun request(chapterId: String, sentenceId: String, targetTextEn: String,
                completionSignal: ReadSpeakCompletionSignal, audio: CapturedReadSpeakAudio, commandId: String) =
                "$chapterId|$sentenceId|$targetTextEn|${completionSignal.name}|$commandId|${audio.file.readText()}"
            override fun result(body: String) = ready
        }, recoveryJournal = journal, recoverySentenceId = "s1")

    private class MemoryJournal : ReadSpeakRecoveryJournal {
        var snapshot: ReadSpeakRecoverySnapshot? = null
        var revision: Long? = null
        var rejectSave = false
        var unreadable = false
        var writes = 0
        override suspend fun load(): ReadSpeakRecoveryLoad = if (unreadable) ReadSpeakRecoveryLoad.Unavailable
            else snapshot?.let { ReadSpeakRecoveryLoad.Loaded(requireNotNull(revision), it) } ?: ReadSpeakRecoveryLoad.Missing
        override suspend fun save(expectedRevision: Long?, snapshot: ReadSpeakRecoverySnapshot): Long? {
            writes++
            if (rejectSave || unreadable || expectedRevision != revision) return null
            revision = (revision ?: -1) + 1
            this.snapshot = snapshot
            return revision
        }
    }
    private class Capture : ReadSpeakAudioCapture {
        val file = File.createTempFile("read-durable-isolated", ".m4a").apply { writeText("NOT_AUDIO:${UUID.randomUUID()}") }
        var starts = 0
        var finishes = 0
        override fun start(): Boolean { starts++; return true }
        override fun finish(): CapturedReadSpeakAudio { finishes++; return CapturedReadSpeakAudio(file, "audio/mp4") }
        override fun cancel() = Unit
    }
}
