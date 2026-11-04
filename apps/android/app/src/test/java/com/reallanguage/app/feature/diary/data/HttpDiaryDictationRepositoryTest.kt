package com.reallanguage.app.feature.diary.data

import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.diary.domain.DiaryDictationResult
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpDiaryDictationRepositoryTest {
    @Test
    fun capturedAudioIsAuthenticatedRecognizedAndDeletedAfterUse() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-token", "account-a") }
        val audio = Files.createTempFile("diary-dictation-", ".m4a").toFile().apply {
            writeBytes("real-audio-bytes".toByteArray())
        }
        val capture = FakeCapture(audio)
        var encodedAudio: ByteArray? = null
        var sentCommandId: String? = null
        val repository = HttpDiaryDictationRepository(
            transport = { request ->
                assertEquals("/api/v1/learning/diary-dictations", request.path)
                assertEquals("Bearer isolated-token", request.headers["Authorization"])
                assertEquals("encoded-request", request.body)
                HttpResponse(200, """{"transcript":"今天开会。","lowConfidenceSegments":[],"confidence":null}""")
            },
            runtime = runtime,
            capture = capture,
            codec = object : DiaryDictationWireCodec {
                override fun request(audio: CapturedDiaryDictationAudio, commandId: String): String {
                    encodedAudio = audio.file.readBytes()
                    sentCommandId = commandId
                    return "encoded-request"
                }
                override fun transcript(body: String) = "今天开会。"
            },
        )

        assertTrue(repository.start())
        assertEquals(DiaryDictationResult.Recognized("今天开会。"), repository.finish("command-1"))
        assertEquals("real-audio-bytes", String(requireNotNull(encodedAudio)))
        assertEquals("command-1", sentCommandId)
        assertFalse(audio.exists())
    }

    @Test
    fun responseFromARevokedSessionCannotEnterTheEditor() = runTest {
        val runtime = SessionRuntime().apply { update("old-token", "account-a") }
        val audio = Files.createTempFile("diary-dictation-", ".m4a").toFile().apply { writeText("audio") }
        val repository = HttpDiaryDictationRepository(
            transport = {
                runtime.update("new-token", "account-b", newSession = true)
                HttpResponse(200, """{"transcript":"不应出现","lowConfidenceSegments":[],"confidence":null}""")
            },
            runtime = runtime,
            capture = FakeCapture(audio),
            codec = object : DiaryDictationWireCodec {
                override fun request(audio: CapturedDiaryDictationAudio, commandId: String) = "request"
                override fun transcript(body: String) = "不应出现"
            },
        )

        assertTrue(repository.start())
        assertEquals(DiaryDictationResult.Failed, repository.finish("command-2"))
        assertFalse(audio.exists())
    }

    private class FakeCapture(private val audio: java.io.File) : DiaryDictationAudioCapture {
        private var started = false
        override fun start(): Boolean = true.also { started = true }
        override fun finish(): CapturedDiaryDictationAudio? =
            if (started) CapturedDiaryDictationAudio(audio) else null
        override fun cancel() { started = false; audio.delete() }
    }
}
