package com.reallanguage.app.feature.recording.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.core.session.UrlConnectionSessionTransport
import com.reallanguage.app.feature.recording.domain.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real service/AI check with the previously measured synthetic audio, never a user's audio. */
class LiveRecordingRepairTest {
    @Test fun originalAudioRepairReplaysWithoutAnotherCallAndConfirmedTextReloads() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRepair") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionFile = File(context.filesDir, "live-repair-session.json")
        val session = JSONObject(sessionFile.readText())
        sessionFile.delete()
        val runtime = SessionRuntime().apply { update(session.getString("accessToken"), session.getString("accountId")) }
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "live-repair-").toFile()
        val audio = File(context.filesDir, "live-repair-audio.m4a")
        audio.copyTo(File(directory, "r1.m4a"))
        audio.delete()
        // Prior real ASR/semantic evidence: first two complete expressions end at 4020 ms.
        // Deliberately exclude the final complete expression to test prefix transport, not cutoff detection.
        val original = "今天我去了公园,我和朋友一起散步,"
        val evidence = JSONObject("""{"transcriptId":"t1","recordingId":"r1","language":"chinese","durationSeconds":6,"endReason":"USER_STOPPED","fullText":"$original","segments":[{"segmentId":"segment-1","startMs":0,"endMs":1760,"text":"今天我去了公园,","speakerLabel":"UNKNOWN","confidence":null,"alternatives":[],"mostLikelyText":null,"mostLikelyIsInference":false},{"segmentId":"segment-2","startMs":2180,"endMs":4020,"text":"我和朋友一起散步,","speakerLabel":"UNKNOWN","confidence":null,"alternatives":[],"mostLikelyText":null,"mostLikelyIsInference":false}],"finalBoundary":{"acceptedEndMs":4020,"droppedIncompleteTail":false}}""")
            .put("accountId", runtime.accountId)
        File(directory, "t1.transcript.json").writeText(evidence.toString())
        val transport = UrlConnectionSessionTransport("http://10.0.2.2:3001", true)
        fun repository() = HttpRecordingRepositories(directory, transport, runtime) { RecordingPlaybackResult.Stopped }
        val first = repository()
        val command = UUID.randomUUID().toString()
        assertEquals("Unchanged complete oral wording must not be polished or extended", TranscriptRepairResult.NoChange,
            first.repair("t1", original, false, command))
        assertEquals("Same command must replay its saved result", TranscriptRepairResult.NoChange,
            repository().repair("t1", original, false, command))
        val confirmedText = "今天我去了公园，我和朋友一起散步。"
        assertTrue(first.confirm("t1", confirmedText, UUID.randomUUID().toString()) is TranscriptConfirmResult.Confirmed)
        val reread = repository().load("t1") as TranscriptLoadResult.Ready
        assertEquals(confirmedText, reread.transcript.fullText)
        assertEquals(original, reread.transcript.evidence?.originalText)
        assertEquals(4020, reread.transcript.evidence?.acceptedEndMs)
        assertTrue(File(directory, "r1.m4a").isFile)
    }
}
