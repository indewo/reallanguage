package com.reallanguage.app.feature.recording.data

import com.reallanguage.app.feature.recording.domain.RecordingEndReason
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingStopEvidenceTest {
    @Test fun manuallyStoppedNearLimitIsNotRelabelledAsAutomaticCutoff() {
        val directory = Files.createTempDirectory("recording-stop-evidence").toFile()
        try {
            save(directory, "USER_STOPPED")
            val summary = readRecordingSummary(directory, "recording-1", 119_999)
            assertEquals(120, summary.durationSeconds)
            assertEquals(RecordingEndReason.USER_STOPPED, summary.endReason)
        } finally { directory.deleteRecursively() }
    }

    @Test fun nativeCutoffAcceptsContainerPaddingWithoutSending121Seconds() {
        val directory = Files.createTempDirectory("recording-stop-evidence").toFile()
        try {
            save(directory, "MAX_DURATION")
            val summary = readRecordingSummary(directory, "recording-1", 120_024)
            assertEquals(120, summary.durationSeconds)
            assertEquals(RecordingEndReason.MAX_DURATION, summary.endReason)
        } finally { directory.deleteRecursively() }
    }

    @Test fun oldLongRecordingDoesNotInventMissingStopEvidence() {
        val directory = Files.createTempDirectory("recording-stop-evidence").toFile()
        try { assertNull(readRecordingSummary(directory, "recording-1", 119_999).endReason) }
        finally { directory.deleteRecursively() }
    }

    @Test fun incompleteCaptureNeverInventsAnExplicitStopFromItsShortDuration() {
        val directory = Files.createTempDirectory("recording-stop-evidence").toFile()
        try {
            save(directory, "INCOMPLETE")
            assertNull(readRecordingSummary(directory, "recording-1", 3_200).endReason)
        } finally { directory.deleteRecursively() }
    }

    @Test fun oldShortRecordingStillLoadsWithoutRewritingItsOriginal() {
        val directory = Files.createTempDirectory("recording-stop-evidence").toFile()
        try {
            assertEquals(RecordingEndReason.USER_STOPPED, readRecordingSummary(directory, "recording-1", 3_200).endReason)
            assertEquals(0, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }

    private fun save(directory: File, reason: String) {
        File(directory, "recording-1.recording.json").writeText(JSONObject()
            .put("formatVersion", 1).put("recordingId", "recording-1").put("endReason", reason).toString())
    }
}
