package com.reallanguage.app.feature.recording.data

import android.Manifest
import android.media.MediaMetadataRetriever
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Native emulator microphone/encoder test only; it is not evidence of human speech or OEM background behavior. */
class RecordingForegroundServiceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun manualStopPersistsItsReasonBesideTheOriginalRecording() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "native-manual-test-${UUID.randomUUID()}"
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val runtime = SessionRuntime().apply { update("isolated-local-only", account) }
        val repository = AndroidRecordingRuntimeRepository(context, runtime)
        compose.setContent { Text("隔离手动停止证据测试，无AI调用") }
        var recordedId: String? = null
        try {
            assertTrue(repository.state.value !is RecordingRuntimeState.Recording)
            runBlocking { repository.start() }
            compose.waitUntil(10_000) { (repository.state.value as? RecordingRuntimeState.Recording)?.active?.elapsedSeconds?.let { it >= 2 } == true }
            recordedId = (repository.state.value as RecordingRuntimeState.Recording).active.recordingId
            val id = requireNotNull(recordedId)
            runBlocking { repository.setPaused(id, true) }
            compose.waitUntil(3_000) { (repository.state.value as? RecordingRuntimeState.Recording)?.active?.paused == true }
            val pausedAt = (repository.state.value as RecordingRuntimeState.Recording).active.elapsedSeconds
            android.os.SystemClock.sleep(2_200)
            org.junit.Assert.assertEquals(pausedAt, (repository.state.value as RecordingRuntimeState.Recording).active.elapsedSeconds)
            runBlocking { repository.setPaused(id, false) }
            compose.waitUntil(5_000) {
                (repository.state.value as? RecordingRuntimeState.Recording)?.active?.let { !it.paused && it.elapsedSeconds >= pausedAt + 1 } == true
            }
            runBlocking { repository.stop(id) }
            compose.waitUntil(5_000) { repository.state.value is RecordingRuntimeState.Completed || repository.state.value is RecordingRuntimeState.Failed }
            assertTrue(repository.state.value is RecordingRuntimeState.Completed)
            val evidence = File(accountRecordingsDirectory(context.filesDir, account), "$recordedId.recording.json")
            assertTrue("Stop reason must survive process/repository recreation", evidence.isFile)
            val saved = org.json.JSONObject(evidence.readText())
            org.junit.Assert.assertEquals(recordedId, saved.getString("recordingId"))
            org.junit.Assert.assertEquals("USER_STOPPED", saved.getString("endReason"))
        } finally {
            recordedId?.let { runBlocking { repository.stop(it) } }
            accountRecordingsDirectory(context.filesDir, account).deleteRecursively()
        }
    }

    @Test fun nativeRecorderStopsAtSixtyMinutesAndLeavesAReadableOriginalFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "native-recording-test-${UUID.randomUUID()}"
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val runtime = SessionRuntime().apply { update("isolated-local-only", account) }
        val repository = AndroidRecordingRuntimeRepository(context, runtime)
        compose.setContent { Text("隔离原生录音计时测试，无AI调用") }
        var recordedId: String? = null
        try {
            assertTrue("Do not interrupt an existing recording", repository.state.value !is RecordingRuntimeState.Recording)
            runBlocking { repository.start() }
            compose.waitUntil(10_000) { repository.state.value is RecordingRuntimeState.Recording }
            recordedId = (repository.state.value as RecordingRuntimeState.Recording).active.recordingId
            compose.waitUntil(3_615_000) {
                val state = repository.state.value
                (state is RecordingRuntimeState.Completed && state.recordingId == recordedId) || state is RecordingRuntimeState.Failed
            }
            assertTrue("Native recording must complete successfully", repository.state.value is RecordingRuntimeState.Completed)
            val file = File(accountRecordingsDirectory(context.filesDir, account), "$recordedId.m4a")
            assertTrue(file.isFile && file.length() > 0)
            val metadata = MediaMetadataRetriever()
            val actualDuration = try {
                metadata.setDataSource(file.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            } finally { metadata.release() }
            assertTrue("Measured original duration was $actualDuration ms", actualDuration in 3_599_000L..3_601_000L)
        } finally {
            recordedId?.let { runBlocking { repository.stop(it) } }
            // Only this test's fresh account directory is disposable.
            accountRecordingsDirectory(context.filesDir, account).deleteRecursively()
        }
    }
}
