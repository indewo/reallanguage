package com.reallanguage.app.feature.recording.data

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Process
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpRequest
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.*
import com.reallanguage.app.feature.recording.presentation.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in write -> actual external force-stop -> read -> actual force-stop -> verify.
 * Actual JSON/repository/VM and private files, not a production Graph or real audio/HTTP/AI.
 * Existing recording repair storage has no key API; this fixture invents no encryption. */
class RecordingRepairProcessRecoveryTest {
    @Test fun unknownRepairSurvivesThreeProcessesWithoutAdoptingItsProposal() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val requestedPhase = args.getString("recordingRepairPhase")
        assumeTrue(requestedPhase in setOf("write", "read", "verify"))
        val phase = requireNotNull(requestedPhase)
        val runId = requireNotNull(args.getString("runId")).also { require(UUID.fromString(it).toString() == it) }
        withContext(Dispatchers.IO) {
            val f = Fixture(phase, runId)
            var verified = false
            try {
                if (phase == "write") f.seedThroughRepository()
                val previousWrite = if (phase == "write") null else f.context.evidence("write")
                if (previousWrite != null) {
                    assertNotEquals("Use a real external process stop between phases", previousWrite.getInt("pid"), Process.myPid())
                    assertEquals(1, previousWrite.getInt("calls")); assertEquals(1, previousWrite.getInt("seedCalls"))
                    assertEquals(previousWrite.getString("protectedSha"), f.protectedSha())
                    assertEquals(previousWrite.getString("baseTranscriptSha"), f.baseTranscriptSha())
                }
                if (phase == "read") assertEquals(previousWrite!!.getString("transcriptSha"), f.transcriptSha())
                val previousRead = if (phase == "verify") f.context.evidence("read") else null
                if (previousRead != null) {
                    assertNotEquals(previousRead.getInt("pid"), Process.myPid())
                    assertNotEquals(previousWrite!!.getInt("pid"), previousRead.getInt("pid"))
                    assertEquals(1, previousRead.getInt("calls")); assertEquals(0, previousRead.getInt("seedCalls"))
                    assertEquals(previousRead.getString("transcriptSha"), f.transcriptSha())
                }
                val protectedBefore = f.protectedSha()
                val baseBefore = f.baseTranscriptSha()
                val model = f.model()
                f.awaitLoaded(model)
                assertEquals(0, f.calls.size)
                f.assertOriginalUntouched(model)
                assertEquals(if (phase == "verify") PROPOSAL else null, model.uiState.value.repairComparison)
                if (phase != "verify") {
                    // Caller seeds differ after process death; the persisted request identity must win.
                    withContext(Dispatchers.Main) { model.onAction(TranscriptResultAction.RepairRequested) }
                    withTimeout(5_000) { model.uiState.first { !it.repairing } }
                    assertEquals(1, f.calls.size)
                    if (phase == "write") {
                        assertEquals(TranscriptResultError.REPAIR_FAILED, model.uiState.value.error)
                        assertNull(model.uiState.value.repairComparison)
                    } else {
                        assertNull(model.uiState.value.error)
                        assertEquals(PROPOSAL, model.uiState.value.repairComparison)
                    }
                }
                f.assertOriginalUntouched(model)
                val loaded = f.repository.load(f.transcriptId) as TranscriptLoadResult.Ready
                assertEquals(if (phase == "write") null else PROPOSAL, loaded.transcript.recoveredRepair)
                assertEquals(TEXT, loaded.transcript.fullText)
                assertEquals(TEXT, loaded.transcript.evidence!!.originalText)
                assertFalse(loaded.transcript.hasUnconfirmedEdits)
                val record = f.local().getJSONObject("repairRecovery")
                assertFalse(record.getBoolean("dismissed"))
                assertEquals(phase != "write", record.has("response"))
                val commandSha = sha(record.getString("commandId"))
                val inputSha = record.getString("inputHash").also { check(it.matches(Regex("[a-f0-9]{64}"))) }
                if (previousWrite != null) {
                    assertEquals(previousWrite.getString("commandSha"), commandSha)
                    assertEquals(previousWrite.getString("inputSha"), inputSha)
                } else assertEquals(sha(JSONObject(f.calls.single().body).getString("commandId")), commandSha)
                assertEquals(protectedBefore, f.protectedSha())
                assertEquals(baseBefore, f.baseTranscriptSha())
                assertEquals(if (phase == "write") 1 else 0, f.seedCalls)
                val transcriptAfter = f.transcriptSha()
                if (phase == "verify") {
                    assertEquals(0, f.calls.size)
                    assertEquals(previousRead!!.getString("transcriptSha"), transcriptAfter)
                } else {
                    f.context.writeEvidence(phase, JSONObject().put("pid", Process.myPid())
                        .put("calls", f.calls.size).put("seedCalls", f.seedCalls)
                        .put("bodySha", sha(f.calls.single().body)).put("commandSha", commandSha).put("inputSha", inputSha)
                        .put("protectedSha", protectedBefore).put("baseTranscriptSha", baseBefore).put("transcriptSha", transcriptAfter))
                }
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putInt("pid", Process.myPid()); putInt("calls", f.calls.size); putInt("seedCalls", f.seedCalls)
                    putString("transcriptSha", transcriptAfter); putString("protectedSha", protectedBefore)
                    putString("commandSha", commandSha)
                })
                verified = phase == "verify"
            } finally {
                // write/read keep the process-owned VM/session intact: disposal is not a process kill.
                // Failed phases preserve this UUID fixture. There are no keys or user DBs to clear.
                if (verified) f.deleteVerifiedFixture()
            }
        }
    }

    private class Fixture(private val phase: String, private val runId: String) {
        val context = ProcessContext(InstrumentationRegistry.getInstrumentation().targetContext, runId, phase == "write")
        private val account = "repair-process-$runId"
        val recordingId = "r-$runId"
        val transcriptId = "t-$runId"
        private val directory = accountRecordingsDirectory(context.filesDir, account)
        private val audio = File(directory, "$recordingId.m4a")
        private val transcript = File(directory, "$transcriptId.transcript.json")
        private val runtime = SessionRuntime().apply { update("ISOLATED_REPAIR_TOKEN", account) }
        private val models = ViewModelStore()
        val calls = mutableListOf<HttpRequest>()
        var seedCalls = 0; private set
        val repository = HttpRecordingRepositories(directory, HttpTransport { request ->
            calls += request
            assertTrue("Loading a proposal must not submit again", phase != "verify")
            assertEquals(1, calls.size)
            assertEquals("POST", request.method); assertEquals("/api/v1/media/transcript-repairs", request.path)
            val body = JSONObject(request.body)
            assertEquals(transcriptId, body.getString("transcriptId")); assertEquals(TEXT, body.getString("currentText"))
            assertFalse(body.getBoolean("manuallyEdited")); assertEquals("audio/mp4", body.getString("audioFormat"))
            assertArrayEquals(NOT_AUDIO.toByteArray(Charsets.UTF_8), Base64.getDecoder().decode(body.getString("audioBase64")))
            assertEquals(local().getJSONObject("repairRecovery").getString("commandId"), body.getString("commandId"))
            if (phase == "write") throw IOException("ISOLATED_UNKNOWN_REPAIR_REPLY")
            val first = context.evidence("write")
            assertEquals(first.getString("bodySha"), sha(request.body))
            assertEquals(first.getString("commandSha"), sha(body.getString("commandId")))
            HttpResponse(200, PROPOSAL_JSON)
        }, runtime) { error("NOT_AUDIO must never enter playback") }

        suspend fun seedThroughRepository() {
            check(directory.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            check(directory.mkdirs())
            writeNew(audio, NOT_AUDIO)
            writeNew(File(context.filesDir, "foreign-fixture-sentinel.txt"), "OTHER_UUID_FIXTURE_DATA")
            val seed = HttpRecordingRepositories(directory, HttpTransport { request ->
                seedCalls++
                assertEquals(1, seedCalls)
                assertEquals("POST", request.method); assertEquals("/api/v1/media/recording-transcriptions", request.path)
                assertEquals(recordingId, JSONObject(request.body).getString("recordingId"))
                HttpResponse(200, JSONObject(TRANSCRIPT_JSON).put("transcriptId", transcriptId).toString())
            }, runtime) { error("No recording playback") }
            assertEquals(TranscriptionResult.Completed(transcriptId), seed.transcribe(
                TranscriptionRequest(recordingId, 4, "audio/mp4", "zh-CN", RecordingEndReason.USER_STOPPED), "transcribe-$runId"))
            assertTrue(seed.confirm(transcriptId, TEXT, "confirm-original-$runId") is TranscriptConfirmResult.Confirmed)
            assertEquals(TEXT, local().getString("confirmedText"))
            assertFalse(local().isNull("confirmedSourceId"))
        }

        suspend fun model() = withContext(Dispatchers.Main) {
            TranscriptResultViewModel(recordingId, transcriptId, "repair-caller-$phase-$runId", "unused-confirm-$runId", repository)
                .also { models.put("repair-process", it) }
        }
        suspend fun awaitLoaded(model: TranscriptResultViewModel) {
            withTimeout(5_000) { model.uiState.first { !it.loading } }
            assertNotNull(model.uiState.value.transcript); assertNull(model.uiState.value.error)
        }
        fun assertOriginalUntouched(model: TranscriptResultViewModel) {
            val state = model.uiState.value
            val transcript = requireNotNull(state.transcript)
            assertEquals(TEXT, state.currentText); assertEquals(TEXT, transcript.fullText)
            assertEquals(TEXT, transcript.evidence!!.originalText)
            assertFalse(state.manuallyEdited); assertFalse(state.confirmed); assertFalse(state.confirming)
            assertFalse(state.draftSaving); assertFalse(state.segmentSaving); assertFalse(state.segmentDraftDirty)
            assertEquals(1, state.segmentRevision)
            val saved = local()
            assertEquals(TEXT, saved.getString("fullText")); assertEquals(TEXT, saved.getString("confirmedText"))
            assertFalse(saved.isNull("confirmedSourceId")); assertFalse(saved.has("draftText"))
            assertEquals(1, saved.getInt("draftRevision"))
            assertEquals(NOT_AUDIO, audio.readText())
        }
        fun local() = JSONObject(transcript.also { check(it.isFile && it.canonicalFile.parentFile == directory.canonicalFile) }.readText())
        fun transcriptSha() = sha(transcript.readBytes())
        fun baseTranscriptSha() = sha(local().apply { remove("repairRecovery") }.toString())
        fun protectedSha() = sha(context.filesDir.walkTopDown().filter { it.isFile && it != transcript }.map {
            check(it.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            "${it.relativeTo(context.filesDir).path}:${sha(it.readBytes())}"
        }.sorted().joinToString("\n"))
        suspend fun deleteVerifiedFixture() {
            withContext(Dispatchers.Main) { models.clear() }
            context.deleteVerifiedFixture()
        }
    }

    private class ProcessContext(base: Context, private val runId: String, create: Boolean) : ContextWrapper(base) {
        private val root = base.cacheDir.canonicalFile
        private val directory = File(root, "recording-repair-process-$runId")
        private val files = File(directory, "files")
        init {
            check(directory.canonicalFile == directory.absoluteFile && directory.parentFile == root)
            if (create) { check(directory.mkdir()); check(files.mkdir()) }
            else check(directory.isDirectory && files.isDirectory)
            check(files.canonicalFile == files.absoluteFile)
        }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = files
        override fun getNoBackupFilesDir() = files
        override fun getCacheDir() = directory
        private fun evidenceFile(phase: String): File {
            check(phase in setOf("write", "read"))
            return File(directory, "$phase-evidence.json").also { check(it.canonicalFile == it.absoluteFile) }
        }
        fun evidence(phase: String) = JSONObject(evidenceFile(phase).also { check(it.length() in 1..16_384) }.readText()).also {
            check(it.getInt("version") == 1 && it.getString("runSha") == sha(runId))
        }
        fun writeEvidence(phase: String, data: JSONObject) = writeNew(evidenceFile(phase),
            data.put("version", 1).put("runSha", sha(runId)).toString())
        fun deleteVerifiedFixture() {
            check(directory.name == "recording-repair-process-$runId" && directory.canonicalFile.parentFile == root)
            directory.walkTopDown().forEach { check(it.canonicalFile == it.absoluteFile) }
            check(directory.deleteRecursively())
        }
    }

    private companion object {
        const val TEXT = "今天我去公园。"
        const val NOT_AUDIO = "NOT_AUDIO_RECORDING_REPAIR_PROCESS_FIXTURE"
        const val PROPOSAL_JSON = """{"decision":"PROPOSE_CHANGES","repairedText":"今天我去了公园。","changes":[{"originalText":"我去公园","proposedText":"我去了公园","reason":"CONFIRMED_OMISSION","evidenceSegmentIds":["s1"]}]}"""
        const val TRANSCRIPT_JSON = """{"transcriptId":"t1","language":"zh-CN","fullText":"今天我去公园。","segments":[{"segmentId":"s1","startMs":0,"endMs":4000,"text":"今天我去公园。","speakerLabel":"UNKNOWN","confidence":null,"alternatives":[],"mostLikelyText":null,"mostLikelyIsInference":false,"needsConfirmation":false}],"finalBoundary":{"acceptedEndMs":4000,"droppedIncompleteTail":false}}"""
        val PROPOSAL = TranscriptRepairResult.Proposed("今天我去了公园。", listOf(
            RepairChange("我去公园", "我去了公园", RepairReason.CONFIRMED_OMISSION, listOf("s1"))))
        fun writeNew(file: File, text: String) {
            check(file.createNewFile())
            FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        }
        fun sha(text: String) = sha(text.toByteArray(Charsets.UTF_8))
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
