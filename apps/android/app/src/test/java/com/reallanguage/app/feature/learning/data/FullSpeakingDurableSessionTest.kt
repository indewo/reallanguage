package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.*
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Isolated custody tests: synthetic bytes, never evidence of speech recognition quality. */
class FullSpeakingDurableSessionTest {
    @Test fun uncertainTrainingRequiresExplicitNewAudioInTheSameRound() = runTest {
        Fixture().use { f ->
            f.trainingAction = ReadingFeedbackAction.REPEAT_FOR_CLARITY
            val repo = f.repository(); repo.restoreAttempt("s", 0)
            assertTrue(repo.prepareCapture("s", 0)); assertTrue(repo.startCapture())
            f.responses += HttpResponse(200, "training")
            assertTrue(repo.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW) is ReadingFeedbackResult.Ready)
            val next = f.repository()
            assertEquals(FullSpeakingRecovery.TrainingReady(ReadingFeedback("继续练习", ReadingFeedbackAction.REPEAT_FOR_CLARITY)), next.restoreAttempt("s", 0))
            assertTrue(next.prepareCapture("s", 0)); assertTrue(next.startCapture())
            f.responses += HttpResponse(200, "training")
            assertTrue(next.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW) is ReadingFeedbackResult.Ready)
            assertEquals(2, f.starts); assertEquals(2, f.finishes)
            assertNotEquals(f.requests.first().body, f.requests.last().body)
        }
    }

    @Test fun committedCandidateWithLostAckAndOneUnavailableReadRecoversWithoutRepeatingHttp() = runTest {
        Fixture().use { f ->
            val repo = f.repository(); repo.restoreAttempt("s", 0)
            assertTrue(repo.prepareCapture("s", 0)); assertTrue(repo.startCapture())
            f.afterRequest = { f.journal(0).loseNextAckAndRead = true }
            f.responses += HttpResponse(200, "training")
            assertEquals(ReadingFeedbackResult.Failed, repo.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW))
            assertTrue(repo.hasPendingTrainingRetry())
            assertTrue(repo.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW) is ReadingFeedbackResult.Ready)
            assertEquals(1, f.requests.size); assertEquals(1, f.starts)
        }
    }
    @Test fun trainingUnknownRestoresOriginalRequestWithoutStartingCaptureOrHttp() = runTest {
        Fixture().use { f ->
            val first = f.repository()
            assertEquals(FullSpeakingRecovery.None, first.restoreAttempt("s", 0))
            assertTrue(first.prepareCapture("s", 0)); assertTrue(first.startCapture())
            f.responses += HttpResponse(503, "")
            assertEquals(ReadingFeedbackResult.Failed, first.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW))
            val original = f.requests.single()
            first.cancelCapture()
            val next = f.repository()
            assertEquals(FullSpeakingRecovery.TrainingPending, next.restoreAttempt("s", 0))
            assertEquals(1, f.requests.size); assertEquals(1, f.starts)
            assertFalse(next.prepareCapture("s", 0))
            f.responses += HttpResponse(200, "training")
            assertTrue(next.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW) is ReadingFeedbackResult.Ready)
            assertEquals(original, f.requests.last())
            assertTrue(f.repository().restoreAttempt("s", 0) is FullSpeakingRecovery.TrainingReady)
            assertEquals(2, f.requests.size)
            assertEquals(FullSpeakingRecovery.None, f.repository().restoreAttempt("s", 1))
        }
    }

    @Test fun asrUnknownSurvivesReentryAndStillRequiresExplicitRetry() = runTest {
        Fixture().use { f ->
            val first = f.repository(); first.restoreAttempt("s", 4)
            assertTrue(first.prepareCapture("s", 4)); assertTrue(first.startCapture())
            f.responses += HttpResponse(503, "")
            assertEquals(SpeakingTranscriptionResult.Failed, first.transcribeIndependentRetelling("s"))
            val request = f.requests.single(); first.cancelCapture()
            val next = f.repository()
            assertEquals(FullSpeakingRecovery.TranscriptionPending, next.restoreAttempt("s", 4))
            assertTrue(next.hasPendingTranscriptionRetry()); assertEquals(1, f.requests.size)
            f.responses += HttpResponse(200, "asr")
            assertEquals(f.transcript, (next.transcribeIndependentRetelling("s") as SpeakingTranscriptionResult.Ready).value)
            assertEquals(request, f.requests.last())
        }
    }

    @Test fun deliveredAsrKeepsOriginalAudioEvidenceForEvaluationAfterReentry() = runTest {
        Fixture().use { f ->
            f.deliverAsr()
            val next = f.repository()
            assertEquals(FullSpeakingRecovery.TranscriptReady(f.transcript, f.journal(4).current!!.snapshot.recordingId), next.restoreAttempt("s", 4))
            assertEquals(1, f.requests.size)
            f.responses += HttpResponse(503, "")
            assertEquals(SpeakingEvaluationResult.Failed, next.evaluateIndependentRetelling("s", "I felt calm.", true))
            val request = f.requests.last()
            assertTrue(request.body!!.contains("NOT_AUDIO_EVIDENCE"))
            next.cancelCapture()
            val resumed = f.repository()
            assertEquals(FullSpeakingRecovery.EvaluationPending("I felt calm.", true), resumed.restoreAttempt("s", 4))
            assertEquals(SpeakingEvaluationResult.Failed, resumed.evaluateIndependentRetelling("s", "changed", false))
            assertEquals(2, f.requests.size)
            f.responses += HttpResponse(200, "evaluation")
            assertEquals(f.candidate, (resumed.evaluateIndependentRetelling("s", "I felt calm.", true) as SpeakingEvaluationResult.Ready).candidate)
            assertEquals(request, f.requests.last())
            val after = f.repository()
            assertEquals(FullSpeakingRecovery.EvaluationReady(f.candidate, "I felt calm.", true), after.restoreAttempt("s", 4))
            assertTrue(after.evaluateIndependentRetelling("s", "I felt calm.", true) is SpeakingEvaluationResult.Ready)
            assertEquals(3, f.requests.size)
        }
    }

    @Test fun failureToPersistOriginalRequestPreventsHttpAndRetainsExplicitRetry() = runTest {
        Fixture().use { f ->
            val repo = f.repository(); repo.restoreAttempt("s", 0)
            assertTrue(repo.prepareCapture("s", 0)); assertTrue(repo.startCapture())
            f.journal(0).writable = false
            assertEquals(ReadingFeedbackResult.Failed, repo.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW))
            assertEquals(0, f.requests.size)
            f.journal(0).writable = true
            f.responses += HttpResponse(200, "training")
            assertTrue(repo.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW) is ReadingFeedbackResult.Ready)
            assertEquals(1, f.requests.size); assertEquals(1, f.finishes)
        }
    }

    @Test fun receivedCandidateWriteFailureRetriesOnlyDurabilityNotPaidRequest() = runTest {
        Fixture().use { f ->
            val repo = f.repository(); repo.restoreAttempt("s", 0)
            assertTrue(repo.prepareCapture("s", 0)); assertTrue(repo.startCapture())
            f.afterRequest = { f.journal(0).writable = false }
            f.responses += HttpResponse(200, "training")
            assertEquals(ReadingFeedbackResult.Failed, repo.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW))
            assertTrue(repo.hasPendingTrainingRetry())
            f.journal(0).writable = true
            assertTrue(repo.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW) is ReadingFeedbackResult.Ready)
            assertEquals(1, f.requests.size)
        }
    }

    @Test fun staleRepositoryCannotReplayAfterNewerAttemptReplacesJournal() = runTest {
        Fixture().use { f ->
            val first = f.repository(); first.restoreAttempt("s", 0)
            val second = f.repository(); second.restoreAttempt("s", 0)
            assertTrue(second.prepareCapture("s", 0)); assertTrue(second.startCapture())
            assertFalse(first.prepareCapture("s", 0))
            assertFalse(first.startCapture())
            assertEquals(0, f.requests.size)
        }
    }

    @Test fun terminalTrainingRejectsOriginalRetryButExplicitNewRecordingIsAllowed() = runTest {
        Fixture().use { f ->
            val repo = f.repository(); repo.restoreAttempt("s", 0)
            assertTrue(repo.prepareCapture("s", 0)); assertTrue(repo.startCapture())
            f.responses += HttpResponse(410, "")
            assertEquals(ReadingFeedbackResult.Failed, repo.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW))
            val next = f.repository()
            assertEquals(FullSpeakingRecovery.Blocked, next.restoreAttempt("s", 0))
            assertEquals(ReadingFeedbackResult.Failed, next.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW))
            assertEquals(1, f.requests.size)
            assertTrue(next.prepareCapture("s", 0)); assertTrue(next.startCapture())
            f.responses += HttpResponse(200, "training")
            assertTrue(next.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW) is ReadingFeedbackResult.Ready)
            assertNotEquals(f.requests.first().body, f.requests.last().body)
        }
    }

    @Test fun changedSessionAndWrongRoundCannotUseRetainedOriginal() = runTest {
        Fixture().use { f ->
            val repo = f.repository(); repo.restoreAttempt("s", 0)
            assertTrue(repo.prepareCapture("s", 0)); assertTrue(repo.startCapture())
            assertEquals(ReadingFeedbackResult.Failed, repo.trainingFeedback("s", SpeakingTrainingMode.ENGLISH_READING))
            assertEquals(0, f.finishes); assertEquals(0, f.requests.size)
            f.runtime.update("different-token", "different-account")
            assertEquals(ReadingFeedbackResult.Failed, repo.trainingFeedback("s", SpeakingTrainingMode.BILINGUAL_FOLLOW))
            assertEquals(0, f.finishes); assertEquals(0, f.requests.size)
        }
    }

    private class Journal : FullSpeakingRecoveryJournal {
        var current: FullSpeakingRecoveryLoad.Loaded? = null
        var writable = true
        var loseNextAckAndRead = false
        private var unavailableRead = false
        override suspend fun load(): FullSpeakingRecoveryLoad {
            if (unavailableRead) { unavailableRead = false; return FullSpeakingRecoveryLoad.Unavailable }
            return current ?: FullSpeakingRecoveryLoad.Missing
        }
        override suspend fun save(expectedRevision: Long?, snapshot: FullSpeakingRecoverySnapshot): Long? {
            if (!writable || expectedRevision != current?.revision) return null
            val revision = (current?.revision ?: -1L) + 1L
            current = FullSpeakingRecoveryLoad.Loaded(revision, snapshot)
            if (loseNextAckAndRead) { loseNextAckAndRead = false; unavailableRead = true; return null }
            return revision
        }
    }

    private class Fixture : AutoCloseable {
        val runtime = SessionRuntime().apply { update("token", "account") }
        val journals = mutableMapOf<Int, Journal>()
        fun journal(round: Int) = journals.getOrPut(round) { Journal() }
        val requests = mutableListOf<HttpRequest>()
        val responses = ArrayDeque<HttpResponse>()
        var afterRequest: () -> Unit = {}
        var starts = 0; var finishes = 0; var commands = 0
        var trainingAction = ReadingFeedbackAction.CONTINUE
        val files = mutableListOf<File>()
        val transcript = SpeakingTranscription("I feel calm.", listOf("calm"), 0.7)
        val candidate = SpeakingEvaluationCandidate(SpeakingEvaluationDecision.PASS, 90, true, true, "表达清楚")
        val codec = object : FullSentenceSpeakingWireCodec {
            override fun trainingRequest(chapterId: String, sentenceId: String, targetTextEn: String, audio: CapturedReadSpeakAudio) = "training:${++commands}:${audio.file.readText()}"
            override fun trainingResult(body: String) = ReadingFeedbackResult.Ready(ReadingFeedback("继续练习", trainingAction))
            override fun transcriptionRequest(chapterId: String, sentenceId: String, audio: CapturedReadSpeakAudio) = "asr:${++commands}:${audio.file.readText()}"
            override fun transcriptionResult(body: String) = SpeakingTranscriptionResult.Ready(transcript)
            override fun evaluationRequest(chapterId: String, sentenceId: String, targetTextEn: String, confirmedTranscript: String, answerAudioPlayed: Boolean, audio: CapturedReadSpeakAudio): String = error("Restored audio must come from the protected original request")
            override fun evaluationRequestFromTranscription(originalRequest: String, targetTextEn: String, confirmedTranscript: String, answerAudioPlayed: Boolean) = "evaluation:${++commands}:$originalRequest:$confirmedTranscript:$answerAudioPlayed"
            override fun evaluationResult(body: String) = SpeakingEvaluationResult.Ready(candidate)
        }
        fun repository() = AndroidFullSentenceSpeakingRepository(
            transport = object : HttpTransport { override suspend fun execute(request: HttpRequest): HttpResponse {
                requests += request; afterRequest(); return responses.removeFirst()
            } }, runtime = runtime, chapterId = "c", targetTextEn = "I felt calm.", codec = codec,
            capture = object : ReadSpeakAudioCapture {
                override fun start(): Boolean { starts++; return true }
                override fun finish(): CapturedReadSpeakAudio {
                    finishes++
                    return CapturedReadSpeakAudio(File.createTempFile("lr06-isolated-", ".m4a").apply {
                        writeText("NOT_AUDIO_EVIDENCE"); files += this
                    })
                }
                override fun cancel() = Unit
            }, recoveryJournalFactory = { sentence, round -> check(sentence == "s"); journal(round) },
        )
        suspend fun deliverAsr() {
            val repo = repository(); repo.restoreAttempt("s", 4)
            check(repo.prepareCapture("s", 4)); check(repo.startCapture())
            responses += HttpResponse(200, "asr")
            check(repo.transcribeIndependentRetelling("s") is SpeakingTranscriptionResult.Ready)
            repo.cancelCapture()
        }
        override fun close() { files.forEach { it.delete() } }
    }
}
