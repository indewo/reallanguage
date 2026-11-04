package com.reallanguage.app.feature.recording.data

import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Isolated transport/files only. NOT_AUDIO is never decoded or sent to any provider. */
class RecordingRepairRecoveryTest {
    @Test fun unknownReplyReopensWithTheExactOriginalRequestDespiteANewCallerSeed() = runTest {
        fixture { directory, runtime ->
            var original: HttpRequest? = null
            val first = repository(directory, runtime) { original = it; throw IOException("unknown submission") }
            assertEquals(TranscriptRepairResult.Failed, first.repair("t1", TEXT, false, "first-command"))
            val second = repository(directory, runtime) {
                assertEquals(original!!.body, it.body)
                HttpResponse(200, NO_CHANGE)
            }
            assertEquals(TranscriptRepairResult.NoChange, second.repair("t1", TEXT, false, "new-caller-command"))
        }
    }

    @Test fun deliveredProposalIsReadAfterRecreationAndExplicitRetryDoesNotCallAI() = runTest {
        fixture { directory, runtime ->
            val first = repository(directory, runtime) { HttpResponse(200, PROPOSAL) }
            val proposal = first.repair("t1", TEXT, false, "repair-1")
            assertTrue(proposal is TranscriptRepairResult.Proposed)
            val reopened = repository(directory, runtime) { error("Saved repair must not call HTTP") }
            assertEquals(proposal, (reopened.load("t1") as TranscriptLoadResult.Ready).transcript.recoveredRepair)
            assertEquals(proposal, reopened.repair("t1", TEXT, false, "repair-2"))
            assertEquals(TEXT, (reopened.load("t1") as TranscriptLoadResult.Ready).transcript.fullText)
        }
    }

    @Test fun noChangeIsDurableAndDismissalDoesNotChargeAgainOrReopenTheComparison() = runTest {
        fixture { directory, runtime ->
            val first = repository(directory, runtime) { HttpResponse(200, NO_CHANGE) }
            assertEquals(TranscriptRepairResult.NoChange, first.repair("t1", TEXT, false, "repair-1"))
            val reopened = repository(directory, runtime) { error("No second paid request") }
            assertEquals(TranscriptRepairResult.NoChange, reopened.repair("t1", TEXT, false, "repair-2"))
            assertTrue(reopened.dismissRepair("t1"))
            assertNull((reopened.load("t1") as TranscriptLoadResult.Ready).transcript.recoveredRepair)
            assertEquals(TranscriptRepairResult.NoChange, reopened.repair("t1", TEXT, false, "repair-3"))
        }
    }

    @Test fun changedTextOrOriginalAudioCannotReuseAnOldResult() = runTest {
        fixture { directory, runtime ->
            var calls = 0
            val commands = mutableListOf<String>()
            val repo = repository(directory, runtime) {
                calls++; commands += JSONObject(it.body).getString("commandId")
                HttpResponse(200, NO_CHANGE)
            }
            repo.repair("t1", TEXT, false, "same-caller-seed")
            assertTrue(repo.saveDraft("t1", "今天我去了公园。", true, 0) is TranscriptDraftSaveResult.Saved)
            assertNull((repo.load("t1") as TranscriptLoadResult.Ready).transcript.recoveredRepair)
            repo.repair("t1", "今天我去了公园。", true, "same-caller-seed")
            File(directory, "r1.m4a").writeText("OTHER_NOT_AUDIO")
            assertNull((repo.load("t1") as TranscriptLoadResult.Ready).transcript.recoveredRepair)
            repo.repair("t1", "今天我去了公园。", true, "same-caller-seed")
            assertEquals(3, calls)
            assertEquals(3, commands.toSet().size)
        }
    }

    @Test fun lateRepairDoesNotOverwriteAConcurrentSavedDraft() = runTest {
        fixture { directory, runtime ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repo = repository(directory, runtime) {
                entered.complete(Unit); release.await(); HttpResponse(200, PROPOSAL)
            }
            val request = async { repo.repair("t1", TEXT, false, "repair-1") }
            entered.await()
            assertTrue(repo.saveDraft("t1", "新的用户原文。", true, 0) is TranscriptDraftSaveResult.Saved)
            release.complete(Unit)
            assertEquals(TranscriptRepairResult.Failed, request.await())
            val loaded = (repo.load("t1") as TranscriptLoadResult.Ready).transcript
            assertEquals("新的用户原文。", loaded.fullText)
            assertNull(loaded.recoveredRepair)
        }
    }

    @Test fun twoRepositoryInstancesCoalesceTheSamePendingOperation() = runTest {
        fixture { directory, runtime ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val transport: suspend (HttpRequest) -> HttpResponse = {
                calls++; entered.complete(Unit); release.await(); HttpResponse(200, NO_CHANGE)
            }
            val first = async { repository(directory, runtime, transport).repair("t1", TEXT, false, "one") }
            entered.await()
            val second = async { repository(directory, runtime, transport).repair("t1", TEXT, false, "two") }
            release.complete(Unit)
            assertEquals(TranscriptRepairResult.NoChange, first.await())
            assertEquals(TranscriptRepairResult.NoChange, second.await())
            assertEquals(1, calls)
        }
    }

    private fun repository(directory: File, runtime: SessionRuntime, action: suspend (HttpRequest) -> HttpResponse) =
        HttpRecordingRepositories(directory, object : HttpTransport {
            override suspend fun execute(request: HttpRequest) = action(request)
        }, runtime) { RecordingPlaybackResult.Stopped }

    private suspend fun fixture(block: suspend (File, SessionRuntime) -> Unit) {
        val directory = Files.createTempDirectory("recording-repair-recovery-").toFile().canonicalFile
        val runtime = SessionRuntime().apply { update("isolated-token", "isolated-repair-owner") }
        try {
            File(directory, "r1.m4a").writeText("NOT_AUDIO")
            val initial = repository(directory, runtime) { HttpResponse(200, TRANSCRIPT) }
            assertTrue(initial.transcribe(TranscriptionRequest("r1", 4, "audio/mp4", "zh-CN", RecordingEndReason.USER_STOPPED), "transcribe-1") is TranscriptionResult.Completed)
            block(directory, runtime)
        } finally {
            directory.listFiles().orEmpty().forEach {
                check(it.isFile && it.canonicalFile.parentFile == directory)
                check(it.name in setOf("r1.m4a", "t1.transcript.json") || it.name.startsWith("transcript-"))
                check(it.delete())
            }
            check(directory.delete())
        }
    }

    private companion object {
        const val TEXT = "今天我去公园。"
        const val NO_CHANGE = """{"decision":"NO_CHANGE"}"""
        const val PROPOSAL = """{"decision":"PROPOSE_CHANGES","repairedText":"今天我去了公园。","changes":[{"originalText":"我去公园","proposedText":"我去了公园","reason":"CONFIRMED_OMISSION","evidenceSegmentIds":["s1"]}]}"""
        const val TRANSCRIPT = """{"transcriptId":"t1","language":"zh-CN","fullText":"今天我去公园。","segments":[{"segmentId":"s1","startMs":0,"endMs":4000,"text":"今天我去公园。","speakerLabel":"UNKNOWN","confidence":null,"alternatives":[],"mostLikelyText":null,"mostLikelyIsInference":false}],"finalBoundary":{"acceptedEndMs":4000,"droppedIncompleteTail":false}}"""
    }
}
