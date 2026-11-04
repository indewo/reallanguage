package com.reallanguage.app.feature.recording.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.RecordingEndReason
import com.reallanguage.app.feature.recording.domain.TranscriptionRequest
import com.reallanguage.app.feature.recording.domain.TranscriptionResult
import com.reallanguage.app.feature.recording.domain.TranscriptConfirmResult
import com.reallanguage.app.feature.recording.domain.TranscriptLoadResult
import com.reallanguage.app.feature.recording.domain.TranscriptRepairResult
import com.reallanguage.app.feature.recording.domain.TranscriptDraftSaveResult
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRecordingRepositoriesTest {
    @Test fun unconfirmedDraftSurvivesNewRepositoryWithoutBecomingConfirmedOrReplacingAudioEvidence() = runTest {
        val directory = Files.createTempDirectory("recording-draft-recovery-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val repository = repository(directory, FakeTransport(HttpResponse(200, transcriptionJson)))
        repository.transcribe(request, "command-1")
        assertEquals(TranscriptDraftSaveResult.Saved(1), repository.saveDraft("transcript-1", "尚未确认的修改。", true, 0))
        val restored = (repository(directory, FakeTransport(HttpResponse(503, ""))).load("transcript-1") as TranscriptLoadResult.Ready).transcript
        assertEquals("尚未确认的修改。", restored.fullText)
        assertEquals("今天我去公园。", restored.evidence?.originalText)
        assertTrue(restored.manuallyEdited)
        assertTrue(restored.hasUnconfirmedEdits)
        assertEquals(1, restored.draftRevision)
        assertTrue(org.json.JSONObject(File(directory, "transcript-1.transcript.json").readText()).isNull("confirmedSourceId"))
    }

    @Test fun emptyDraftAndSameWriteRetryArePreservedButStaleDifferentDraftIsRejected() = runTest {
        val directory = Files.createTempDirectory("recording-draft-version-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val repository = repository(directory, FakeTransport(HttpResponse(200, transcriptionJson)))
        repository.transcribe(request, "command-1")
        assertEquals(TranscriptDraftSaveResult.Saved(1), repository.saveDraft("transcript-1", "", true, 0))
        assertEquals(TranscriptDraftSaveResult.Saved(1), repository.saveDraft("transcript-1", "", true, 0))
        assertEquals(TranscriptDraftSaveResult.Failed, repository.saveDraft("transcript-1", "迟到旧编辑。", true, 0))
        assertEquals("", (repository.load("transcript-1") as TranscriptLoadResult.Ready).transcript.fullText)
    }

    @Test fun confirmingClearsDraftAndInvalidatesLateDraftWritesWithoutChangingSavedText() = runTest {
        val directory = Files.createTempDirectory("recording-draft-confirm-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val repository = repository(directory, FakeTransport(HttpResponse(200, transcriptionJson)))
        repository.transcribe(request, "command-1")
        assertEquals(TranscriptDraftSaveResult.Saved(1), repository.saveDraft("transcript-1", "草稿。", true, 0))
        assertTrue(repository.confirm("transcript-1", "最终确认。", "confirm-1") is TranscriptConfirmResult.Confirmed)
        assertEquals(TranscriptDraftSaveResult.Failed, repository.saveDraft("transcript-1", "晚到草稿。", true, 1))
        val loaded = (repository.load("transcript-1") as TranscriptLoadResult.Ready).transcript
        assertEquals("最终确认。", loaded.fullText)
        assertFalse(loaded.hasUnconfirmedEdits)
        assertEquals(2, loaded.draftRevision)
    }
    @Test fun collidingReturnedTranscriptIdCannotOverwriteAnotherRecording() = runTest {
        val directory = Files.createTempDirectory("recording-collision-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio-one".toByteArray())
        File(directory, "recording-2.m4a").writeBytes("isolated-audio-two".toByteArray())
        val repository = repository(directory, FakeTransport(HttpResponse(200, transcriptionJson)))
        repository.transcribe(request, "command-1")
        repository.confirm("transcript-1", "第一份录音的确认文字。", "confirm-1")
        val file = File(directory, "transcript-1.transcript.json")
        val before = file.readText()
        assertEquals(TranscriptionResult.Failed(null), repository.transcribe(request.copy(recordingId = "recording-2"), "command-2"))
        assertEquals(before, file.readText())
    }
    @Test fun lateDuplicateTranscriptionCannotEraseAlreadyConfirmedEdits() = runTest {
        val directory = Files.createTempDirectory("recording-late-duplicate-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstResponse = CompletableDeferred<HttpResponse>()
        val secondResponse = CompletableDeferred<HttpResponse>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = if (calls.incrementAndGet() == 1) {
                firstStarted.complete(Unit); firstResponse.await()
            } else { secondStarted.complete(Unit); secondResponse.await() }
        }
        val repository = repository(directory, transport)
        val first = async { repository.transcribe(request, "same-command") }
        firstStarted.await()
        val second = async { repository.transcribe(request, "same-command") }
        secondStarted.await()
        firstResponse.complete(HttpResponse(200, transcriptionJson))
        assertEquals(TranscriptionResult.Completed("transcript-1"), first.await())
        assertTrue(repository.confirm("transcript-1", "已确认，必须保留的修改。", "confirm-1") is TranscriptConfirmResult.Confirmed)
        secondResponse.complete(HttpResponse(200, transcriptionJson))
        assertEquals(TranscriptionResult.Completed("transcript-1"), second.await())
        assertEquals("已确认，必须保留的修改。", (repository.load("transcript-1") as TranscriptLoadResult.Ready).transcript.fullText)
    }
    @Test fun repairUploadsPreservedOriginalTimingNotTheManuallyEditedTextAsEvidence() = runTest {
        val directory = Files.createTempDirectory("recording-repair-evidence-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val transport = QueueTransport(HttpResponse(200, transcriptionJson), HttpResponse(200, """{"decision":"NO_CHANGE"}"""))
        val repository = repository(directory, transport)
        repository.transcribe(request, "command-1")
        assertEquals(TranscriptRepairResult.NoChange, repository.repair("transcript-1", "用户手改文字。", true, "repair-1"))
        val body = org.json.JSONObject(transport.lastRequest!!.body)
        val evidence = body.getJSONObject("evidence")
        assertEquals(4000, evidence.getInt("acceptedEndMs"))
        assertEquals(4, evidence.getInt("durationSeconds"))
        assertEquals("USER_STOPPED", evidence.getString("endReason"))
        assertEquals("今天我去公园。", evidence.getJSONArray("segments").getJSONObject(0).getString("text"))
        assertEquals("用户手改文字。", body.getString("currentText"))
    }

    @Test fun legacyTranscriptWithoutBoundaryCannotLaunchPaidRepairButRemainsEditable() = runTest {
        val directory = Files.createTempDirectory("recording-repair-legacy-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val transport = QueueTransport(HttpResponse(200, transcriptionJson), HttpResponse(200, """{"decision":"NO_CHANGE"}"""))
        val repository = repository(directory, transport)
        repository.transcribe(request, "command-1")
        val file = File(directory, "transcript-1.transcript.json")
        val saved = org.json.JSONObject(file.readText())
        listOf("segments", "durationSeconds", "finalBoundary").forEach { saved.remove(it) }
        file.writeText(saved.toString())
        assertEquals(TranscriptRepairResult.Failed, repository.repair("transcript-1", "用户手改文字。", true, "repair-1"))
        assertEquals(1, transport.calls)
        assertTrue(repository.confirm("transcript-1", "用户手改文字。", "confirm-1") is TranscriptConfirmResult.Confirmed)
    }

    @Test fun serverProposalWithoutMatchingEvidenceCannotBeOfferedForAcceptance() = runTest {
        val directory = Files.createTempDirectory("recording-repair-invalid-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val invalid = """{"decision":"PROPOSE_CHANGES","repairedText":"今天我去公园。","changes":[{"originalText":"我公园","proposedText":"我去公园","evidenceSegmentIds":["invented"],"reason":"CONFIRMED_OMISSION"}]}"""
        val repository = repository(directory, QueueTransport(HttpResponse(200, transcriptionJson), HttpResponse(200, invalid)))
        repository.transcribe(request, "command-1")
        assertEquals(TranscriptRepairResult.Failed, repository.repair("transcript-1", "今天我公园。", false, "repair-1"))
    }
    @Test fun acceptedTimingEvidenceSurvivesNewRepositoryAndConfirmedTextDoesNotRewriteOriginalSegments() = runTest {
        val directory = Files.createTempDirectory("recording-evidence-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val repository = repository(directory, FakeTransport(HttpResponse(200, transcriptionJson)))
        repository.transcribe(request, "command-1")
        repository.confirm("transcript-1", "用户修订的内容。", "confirm-1")
        val saved = org.json.JSONObject(File(directory, "transcript-1.transcript.json").readText())
        assertEquals(4000, saved.getJSONObject("finalBoundary").getInt("acceptedEndMs"))
        assertEquals("今天我去公园。", saved.getJSONArray("segments").getJSONObject(0).getString("text"))
        val reopened = repository(directory, FakeTransport(HttpResponse(503, "")))
        val loaded = reopened.load("transcript-1") as TranscriptLoadResult.Ready
        assertEquals("用户修订的内容。", loaded.transcript.fullText)
        assertEquals("今天我去公园。", loaded.transcript.evidence?.originalText)
        assertEquals(4000, loaded.transcript.evidence?.segments?.single()?.endMs)
        assertTrue(loaded.transcript.manuallyEdited)
    }

    @Test fun responseWithContradictoryTranscriptEvidenceCannotBeSavedAsSuccess() = runTest {
        val directory = Files.createTempDirectory("recording-invalid-evidence-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val response = org.json.JSONObject(transcriptionJson).put("fullText", "不在识别片段中的内容。").toString()
        val repository = repository(directory, FakeTransport(HttpResponse(200, response)))
        assertEquals(TranscriptionResult.Failed(null), repository.transcribe(request, "command-1"))
        assertFalse(File(directory, "transcript-1.transcript.json").exists())
    }
    @Test fun firstConfirmationCreatesRealSourceAndReloadsConfirmedText() = runTest {
        val directory = Files.createTempDirectory("recording-confirm-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val repository = repository(directory, FakeTransport(HttpResponse(200, transcriptionJson)))
        repository.transcribe(request, "command-1")
        val confirmed = repository.confirm("transcript-1", "用户确认的修订文本。", "confirm-1") as TranscriptConfirmResult.Confirmed
        assertTrue("JSON null is not a source identifier", confirmed.sourceId.isNotBlank() && confirmed.sourceId != "null")
        val reopened = repository(directory, FakeTransport(HttpResponse(503, "")))
        assertEquals(confirmed, reopened.confirm("transcript-1", "用户确认的修订文本。", "confirm-1"))
        assertEquals("用户确认的修订文本。", (reopened.load("transcript-1") as TranscriptLoadResult.Ready).transcript.fullText)
    }

    @Test fun confirmationCommandCannotOverwriteDifferentTextOnRetry() = runTest {
        val directory = Files.createTempDirectory("recording-confirm-command-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val repository = repository(directory, FakeTransport(HttpResponse(200, transcriptionJson)))
        repository.transcribe(request, "command-1")
        repository.confirm("transcript-1", "第一次确认。", "confirm-1")
        assertEquals(TranscriptConfirmResult.Failed, repository.confirm("transcript-1", "不同内容。", "confirm-1"))
        assertEquals("第一次确认。", (repository.load("transcript-1") as TranscriptLoadResult.Ready).transcript.fullText)
    }

    @Test fun previousAccountRepositoryCannotUploadItsAudioWithNewAccountCredentials() = runTest {
        val directory = Files.createTempDirectory("recording-owner-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val transport = FakeTransport(HttpResponse(200, transcriptionJson))
        val runtime = SessionRuntime().apply { update("first-test", "account-1") }
        val repository = HttpRecordingRepositories(directory, transport, runtime) {
            com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult.Stopped
        }
        runtime.update("second-test", "account-2")
        assertEquals(TranscriptionResult.Failed(null), repository.transcribe(request, "command-1"))
        assertEquals(null, transport.request)
    }

    @Test fun logoutWhileTranscribingCannotSaveLateResult() = runTest {
        val directory = Files.createTempDirectory("recording-late-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val runtime = SessionRuntime().apply { update("test", "account-1") }
        val repository = HttpRecordingRepositories(directory, object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                runtime.clear()
                return HttpResponse(200, transcriptionJson)
            }
        }, runtime) { com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult.Stopped }
        assertEquals(TranscriptionResult.Failed(null), repository.transcribe(request, "command-1"))
        assertFalse(File(directory, "transcript-1.transcript.json").exists())
    }

    @Test fun sameAccountReloginCannotLetOldRepositoryOverwriteNewSessionConfirmation() = runTest {
        withIsolatedEpochDirectory { directory, accountId ->
            val runtime = SessionRuntime().apply { update("isolated-before", accountId) }
            val transport = QueueTransport(HttpResponse(200, transcriptionJson))
            val oldRepository = epochRepository(directory, transport, runtime)
            assertEquals(TranscriptionResult.Completed("transcript-1"), oldRepository.transcribe(request, "transcribe-before"))
            val previousEpoch = runtime.accountEpoch
            runtime.clear()
            runtime.update("isolated-after", accountId)
            assertTrue(runtime.accountEpoch > previousEpoch)

            val currentRepository = epochRepository(directory, transport, runtime)
            val currentText = "新会话确认的中性文本。"
            assertTrue(currentRepository.confirm("transcript-1", currentText, "confirm-current") is TranscriptConfirmResult.Confirmed)
            val transcriptFile = File(directory, "transcript-1.transcript.json")
            val savedBytes = transcriptFile.readBytes()
            assertEquals(currentText, (epochRepository(directory, transport, runtime).load("transcript-1") as TranscriptLoadResult.Ready).transcript.fullText)

            assertEquals(TranscriptConfirmResult.Failed, oldRepository.confirm("transcript-1", "旧会话的中性文本。", "confirm-stale"))
            assertArrayEquals(savedBytes, transcriptFile.readBytes())
            assertEquals(TranscriptLoadResult.Failed, oldRepository.load("transcript-1"))
            assertEquals(currentText, (epochRepository(directory, transport, runtime).load("transcript-1") as TranscriptLoadResult.Ready).transcript.fullText)
            assertEquals(1, transport.calls)
        }
    }

    @Test fun sameAccountReloginDuringTranscriptionCannotPersistLateResponse() = runTest {
        withIsolatedEpochDirectory { directory, accountId ->
            val runtime = SessionRuntime().apply { update("isolated-before", accountId) }
            val previousEpoch = runtime.accountEpoch
            val audio = File(directory, "recording-1.m4a")
            val originalBytes = audio.readBytes()
            var calls = 0
            val transport = object : HttpTransport {
                override suspend fun execute(request: HttpRequest): HttpResponse {
                    calls += 1
                    runtime.clear()
                    runtime.update("isolated-after", accountId)
                    return HttpResponse(200, transcriptionJson)
                }
            }
            val oldRepository = epochRepository(directory, transport, runtime)

            assertEquals(TranscriptionResult.Failed(null), oldRepository.transcribe(request, "transcribe-stale"))
            assertTrue(runtime.accountEpoch > previousEpoch)
            assertEquals(1, calls)
            assertFalse(File(directory, "transcript-1.transcript.json").exists())
            assertArrayEquals(originalBytes, audio.readBytes())
            assertEquals(listOf("recording-1.m4a"), directory.listFiles()!!.map { it.name }.sorted())
        }
    }

    @Test fun sameAccountTokenRefreshPreservesRepositoryConfirmationAndReadback() = runTest {
        withIsolatedEpochDirectory { directory, accountId ->
            val runtime = SessionRuntime().apply { update("isolated-before", accountId) }
            val transport = QueueTransport(HttpResponse(200, transcriptionJson))
            val repository = epochRepository(directory, transport, runtime)
            assertEquals(TranscriptionResult.Completed("transcript-1"), repository.transcribe(request, "transcribe-refresh"))
            val previousEpoch = runtime.accountEpoch
            runtime.update("isolated-refreshed", accountId)
            assertEquals(previousEpoch, runtime.accountEpoch)

            val text = "刷新后正常确认的中性文本。"
            val confirmed = repository.confirm("transcript-1", text, "confirm-refreshed")
            assertTrue(confirmed is TranscriptConfirmResult.Confirmed)
            val reopened = epochRepository(directory, transport, runtime)
            assertEquals(text, (reopened.load("transcript-1") as TranscriptLoadResult.Ready).transcript.fullText)
            assertEquals(confirmed, reopened.confirm("transcript-1", text, "confirm-refreshed"))
            assertEquals(1, transport.calls)
        }
    }

    @Test fun cancelledUploadPropagatesCancellationAndKeepsOriginalAudio() = runTest {
        val directory = Files.createTempDirectory("recording-cancel-test").toFile()
        val audio = File(directory, "recording-1.m4a").apply { writeBytes("isolated-audio".toByteArray()) }
        val repository = repository(directory, object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse = throw CancellationException("left page")
        })
        var cancelled = false
        try { repository.transcribe(request, "command-1") } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(audio.isFile)
    }

    @Test fun recordingIdentifierCannotEscapeItsOwnedDirectory() = runTest {
        val root = Files.createTempDirectory("recording-path-test").toFile()
        val directory = File(root, "owned").apply { mkdirs() }
        File(root, "outside.m4a").writeBytes("isolated-outside-audio".toByteArray())
        val transport = FakeTransport(HttpResponse(200, transcriptionJson))
        assertEquals(TranscriptionResult.Failed(null), repository(directory, transport).transcribe(request.copy(recordingId = "../outside"), "command-1"))
        assertEquals(null, transport.request)
    }

    @Test fun returnedTranscriptIdentifierCannotWriteOutsideOwnedDirectory() = runTest {
        val root = Files.createTempDirectory("transcript-path-test").toFile()
        val directory = File(root, "owned").apply { mkdirs() }
        File(directory, "recording-1.m4a").writeBytes("isolated-audio".toByteArray())
        val transport = FakeTransport(HttpResponse(200, transcriptionJson.replace("transcript-1", "../outside")))
        assertEquals(TranscriptionResult.Failed(null), repository(directory, transport).transcribe(request, "command-1"))
        assertFalse(File(root, "outside.transcript.json").exists())
    }

    @Test fun transcriptionUploadsOnlyAfterExplicitCallAndPreservesLocalAudio() = runTest {
        val directory = Files.createTempDirectory("recording-test").toFile()
        val audio = File(directory, "recording-1.m4a").apply { writeBytes("audio".toByteArray()) }
        val transport = FakeTransport(HttpResponse(200, transcriptionJson))
        val repository = repository(directory, transport)

        val result = repository.transcribe(request, "command-1")

        assertEquals(TranscriptionResult.Completed("transcript-1"), result)
        assertTrue(audio.isFile)
        assertEquals("/api/v1/media/recording-transcriptions", transport.request?.path)
        assertTrue(transport.request!!.body.contains("YXVkaW8="))
        assertTrue(File(directory, "transcript-1.transcript.json").isFile)
    }

    @Test fun failedTranscriptionDoesNotDeleteOrCreateTranscript() = runTest {
        val directory = Files.createTempDirectory("recording-test").toFile()
        val audio = File(directory, "recording-1.m4a").apply { writeBytes("audio".toByteArray()) }
        val repository = repository(directory, FakeTransport(HttpResponse(503, "")))

        assertEquals(TranscriptionResult.Failed(null), repository.transcribe(request, "command-1"))
        assertTrue(audio.isFile)
        assertFalse(File(directory, "transcript-1.transcript.json").exists())
    }

    @Test fun loadRepairAndConfirmStayAccountIsolatedAndUseVisibleText() = runTest {
        val directory = Files.createTempDirectory("recording-test").toFile()
        File(directory, "recording-1.m4a").writeBytes("audio".toByteArray())
        val transport = QueueTransport(
            HttpResponse(200, transcriptionJson),
            HttpResponse(200, """{"decision":"NO_CHANGE"}"""),
        )
        val runtime = SessionRuntime().apply {
            update("token", "account-1")
        }
        val repository = HttpRecordingRepositories(directory, transport, runtime) {
            com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult.Stopped
        }
        repository.transcribe(request, "command-1")

        val loaded = repository.load("transcript-1") as TranscriptLoadResult.Ready
        assertEquals("今天我去公园。", loaded.transcript.fullText)
        assertEquals(TranscriptRepairResult.NoChange, repository.repair("transcript-1", "手动修改。", true, "repair-1"))
        val confirmed = repository.confirm("transcript-1", "手动修改。", "confirm-1")
        assertTrue(confirmed is TranscriptConfirmResult.Confirmed)
        assertEquals(confirmed, repository.confirm("transcript-1", "不同文字。", "confirm-2"))
    }

    private fun repository(directory: File, transport: HttpTransport): HttpRecordingRepositories {
        val runtime = SessionRuntime().apply { update("token", "account-1") }
        return HttpRecordingRepositories(directory, transport, runtime) {
            com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult.Stopped
        }
    }

    private fun epochRepository(directory: File, transport: HttpTransport, runtime: SessionRuntime) =
        HttpRecordingRepositories(directory, transport, runtime) {
            com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult.Stopped
        }

    private suspend fun withIsolatedEpochDirectory(block: suspend (File, String) -> Unit) {
        val fixtureId = UUID.randomUUID().toString()
        val prefix = "recording-epoch-$fixtureId-"
        val directory = Files.createTempDirectory(prefix).toFile().canonicalFile
        val parent = directory.parentFile
        try {
            // Transport-only fixture: these bytes are never played or sent to a real service.
            File(directory, "recording-1.m4a").writeBytes("isolated-transport-fixture".toByteArray())
            block(directory, "recording-account-$fixtureId")
        } finally {
            check(directory.name.startsWith(prefix) && directory.canonicalFile.parentFile == parent)
            val files = checkNotNull(directory.listFiles())
            files.forEach { file ->
                check(file.isFile && file.canonicalFile.parentFile == directory)
                check(file.name == "recording-1.m4a" || file.name == "transcript-1.transcript.json" ||
                    (file.name.startsWith("transcript-") && file.name.endsWith(".tmp")))
                check(file.delete())
            }
            check(directory.delete())
        }
    }

    private class FakeTransport(private val response: HttpResponse) : HttpTransport {
        var request: HttpRequest? = null
        override suspend fun execute(request: HttpRequest): HttpResponse { this.request = request; return response }
    }

    private class QueueTransport(vararg responses: HttpResponse) : HttpTransport {
        private val queue = ArrayDeque(responses.toList())
        var calls = 0
        var lastRequest: HttpRequest? = null
        override suspend fun execute(request: HttpRequest): HttpResponse { calls++; lastRequest = request; return queue.removeFirst() }
    }

    companion object {
        private val request = TranscriptionRequest(
            "recording-1", 4, "audio/mp4", "zh-CN", RecordingEndReason.USER_STOPPED,
        )
        private val transcriptionJson = """{
          "transcriptId":"transcript-1","language":"zh-CN","fullText":"今天我去公园。",
          "segments":[{"segmentId":"s1","startMs":0,"endMs":4000,"text":"今天我去公园。","speakerLabel":"UNKNOWN","confidence":null,"alternatives":[],"mostLikelyText":null,"mostLikelyIsInference":false}],"finalBoundary":{"acceptedEndMs":4000,"droppedIncompleteTail":false}
        }""".trimIndent()
    }
}
