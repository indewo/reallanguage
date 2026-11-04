package com.reallanguage.app.feature.recording.data

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.RecordingLoadResult
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Native microphone/encoder fixture only: silence is not human-speech, AI, or OEM evidence. */
class RecordingInterruptionEvidenceFailureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedSessionEndedEvidenceCannotInventUserStoppedForRetainedOriginal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        assertFalse("Leave any preexisting recording untouched", serviceState().state is RecordingRuntimeState.Recording)
        val context = instrumentation.targetContext
        val account = "interrupted-evidence-${UUID.randomUUID()}"
        val ownerKey = accountRecordingStorageKey(account)
        val directory = accountRecordingsDirectory(context.filesDir, account)
        assertFalse("The fixture owns a new UUID account directory", directory.exists())
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))
        val runtime = SessionRuntime().apply { update("isolated-capture-only", account) }
        val repository = AndroidRecordingRuntimeRepository(context, runtime)
        fun active(): RecordingRuntimeState.Recording? = serviceState().let {
            if (it.accountStorageKey == ownerKey) it.state as? RecordingRuntimeState.Recording else null
        }
        compose.setContent { Text("隔离静音录音标记失败测试；无AI调用") }
        try {
            runBlocking { repository.start() }
            compose.waitUntil(10_000) { active()?.active?.elapsedSeconds?.let { it >= 2 } == true }
            val recordingId = requireNotNull(active()).active.recordingId
            val evidence = File(directory, "$recordingId.recording.json")
            assertEquals(directory.canonicalFile, evidence.canonicalFile.parentFile)
            // A future initial INCOMPLETE marker is ours too; never remove another path.
            assertTrue("Remove only this recording's own ordinary marker", !evidence.exists() || (evidence.isFile && evidence.delete()))
            assertTrue("A directory must block the final marker replacement", evidence.mkdir())

            runtime.update("isolated-relogin-only", account, newSession = true)
            compose.waitUntil(5_000) {
                val current = serviceState()
                current.accountStorageKey == ownerKey && current.state !is RecordingRuntimeState.Recording
            }
            assertEquals(ownerKey, serviceState().accountStorageKey)
            assertEquals("Interrupted capture is not a successful completion", RecordingRuntimeState.Failed, serviceState().state)
            assertTrue("The test must exercise the unavailable evidence path", evidence.isDirectory)
            val original = File(directory, "$recordingId.m4a")
            assertTrue("The original must survive the evidence failure", original.isFile && original.length() > 0)
            val metadata = MediaMetadataRetriever()
            val duration = try {
                metadata.setDataSource(original.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally { metadata.release() }
            assertTrue("Native audio must be finalized and readable", duration != null && duration >= 1_000)
            val loaded = runBlocking { AndroidRecordingConfirmationRepository(context, runtime).load(recordingId) }
            assertTrue("A fresh same-account entry may inspect the retained original", loaded is RecordingLoadResult.Ready)
            assertNull("Missing interruption proof must not infer a transcribable user-stop boundary", (loaded as RecordingLoadResult.Ready).recording.endReason)
        } finally {
            instrumentation.waitForIdleSync()
            val current = serviceState()
            if (current.accountStorageKey == ownerKey && current.state is RecordingRuntimeState.Recording) {
                context.stopService(Intent(context, RecordingForegroundService::class.java))
                compose.waitUntil(5_000) { active() == null }
                instrumentation.waitForIdleSync()
            }
            assertTrue(directory.name == ownerKey && directory.parentFile?.name == "recordings")
            assertTrue("Delete only this fixture's UUID recording directory", !directory.exists() || directory.deleteRecursively())
        }
    }

    private fun serviceState() = RecordingForegroundService.ownedRuntimeState.value
}
