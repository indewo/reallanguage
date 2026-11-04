package com.reallanguage.app.feature.learning.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationCandidate
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationResult
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackResult
import com.reallanguage.app.feature.learning.domain.SpeakingTranscription
import com.reallanguage.app.feature.learning.domain.SpeakingTranscriptionResult
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidFullSentenceSpeakingRepositoryTest {
    @Test fun `retains raw audio through transcript confirmation and evaluation`() = runTest {
        val audio = File.createTempFile("full-speaking", ".m4a").apply { writeText("audio") }
        val transport = QueuedSpeakingTransport(
            HttpResponse(200, """{"transcript":"I felt calm.","lowConfidenceSegments":["calm"],"confidence":0.8}"""),
            HttpResponse(200, """{"decision":"PASS","completenessScore":90,"coreMeaningRecognizable":true,"pronunciationIntelligible":true,"feedbackZh":"内容完整。","pronunciationConfidence":0.9}"""),
        )
        val codec = RecordingFullSpeakingCodec()
        val repository = repository(transport, OneShotSpeakingCapture(CapturedReadSpeakAudio(audio)), codec)

        assertTrue(repository.startCapture())
        val transcription = repository.transcribeIndependentRetelling("s1")
        assertTrue(transcription is SpeakingTranscriptionResult.Ready)
        assertTrue(audio.exists())
        val evaluation = repository.evaluateIndependentRetelling("s1", "I felt calm.", true)

        assertEquals(
            SpeakingEvaluationDecision.PASS,
            (evaluation as SpeakingEvaluationResult.Ready).candidate.decision,
        )
        assertEquals(
            listOf(
                "/api/v1/learning/speaking-transcriptions",
                "/api/v1/learning/full-sentence-speaking-evaluations",
            ),
            transport.requests.map { it.path },
        )
        assertTrue(codec.answerAudioPlayed == true)
        assertFalse(audio.exists())
    }

    @Test fun `unknown transcription keeps raw attempt until explicit cancellation`() = runTest {
        val audio = File.createTempFile("full-speaking", ".m4a").apply { writeText("audio") }
        val transport = QueuedSpeakingTransport(HttpResponse(503, ""))
        val repository = repository(transport, OneShotSpeakingCapture(CapturedReadSpeakAudio(audio)), RecordingFullSpeakingCodec())

        assertEquals(
            SpeakingTranscriptionResult.Failed,
            repository.transcribeIndependentRetelling("s1"),
        )
        assertTrue(audio.exists())
        assertTrue(repository.hasPendingTranscriptionRetry())
        repository.cancelCapture()
        assertFalse(audio.exists())
        assertFalse(repository.hasPendingTranscriptionRetry())
    }

    private fun repository(transport: HttpTransport, capture: ReadSpeakAudioCapture, codec: FullSentenceSpeakingWireCodec) =
        AndroidFullSentenceSpeakingRepository(
            transport = transport,
            runtime = SessionRuntime().apply { update("token", "account") },
            chapterId = "c1",
            targetTextEn = "I felt calm.",
            capture = capture,
            codec = codec,
        )
}

private class RecordingFullSpeakingCodec : FullSentenceSpeakingWireCodec {
    var answerAudioPlayed: Boolean? = null
    override fun trainingRequest(chapterId: String, sentenceId: String, targetTextEn: String, audio: CapturedReadSpeakAudio) = "training"
    override fun trainingResult(body: String): ReadingFeedbackResult = ReadingFeedbackResult.Failed
    override fun transcriptionRequest(chapterId: String, sentenceId: String, audio: CapturedReadSpeakAudio) = "transcription"
    override fun transcriptionResult(body: String): SpeakingTranscriptionResult = SpeakingTranscriptionResult.Ready(
        SpeakingTranscription("I felt calm.", listOf("calm"), 0.8),
    )
    override fun evaluationRequest(chapterId: String, sentenceId: String, targetTextEn: String, confirmedTranscript: String, answerAudioPlayed: Boolean, audio: CapturedReadSpeakAudio): String {
        this.answerAudioPlayed = answerAudioPlayed
        return "evaluation"
    }
    override fun evaluationResult(body: String): SpeakingEvaluationResult = SpeakingEvaluationResult.Ready(
        SpeakingEvaluationCandidate(SpeakingEvaluationDecision.PASS, 90, true, true, "内容完整。"),
    )
}

private class OneShotSpeakingCapture(private val audio: CapturedReadSpeakAudio) : ReadSpeakAudioCapture {
    override fun start() = true
    override fun finish() = audio
    override fun cancel() = Unit
}

private class QueuedSpeakingTransport(vararg responses: HttpResponse) : HttpTransport {
    private val queued = ArrayDeque(responses.toList())
    val requests = mutableListOf<HttpRequest>()
    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return queued.removeFirst()
    }
}
