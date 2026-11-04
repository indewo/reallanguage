package com.reallanguage.app.feature.recording.data

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.RecordingLoadResult
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import java.io.File
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Emulator microphone/encoder lifecycle only; silence is not human-speech, AI, or OEM evidence. */
class RecordingServiceSessionRevocationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sameAccountNewSessionStopsTheServiceKeepsOriginalAndRejectsOldRepository() = withRecording { fixture ->
        val ownerEpoch = fixture.runtime.accountEpoch
        fixture.runtime.update("isolated-new-session-only", fixture.account, newSession = true)

        assertTrue(fixture.runtime.accountEpoch > ownerEpoch)
        fixture.assertRevokedAndOriginalRetained()
        val result = runBlocking {
            AndroidRecordingConfirmationRepository(fixture.context, fixture.runtime).load(fixture.recordingId)
        }
        assertTrue("A fresh same-account entry may load the retained original", result is RecordingLoadResult.Ready)
        assertNull("Session interruption must not invent a user-stop or cutoff boundary", (result as RecordingLoadResult.Ready).recording.endReason)
        fixture.assertOldRepositoryCannotControlRecording()
    }

    @Test fun clearingSessionStopsTheServiceKeepsOriginalAndRejectsOldRepository() = withRecording { fixture ->
        fixture.runtime.clear()

        fixture.assertRevokedAndOriginalRetained()
        fixture.assertOldRepositoryCannotControlRecording()
    }

    @Test fun unfinishedEvidenceExistsBeforeCaptureAndOnlyExplicitStopCommitsIt() = withRecording { fixture ->
        val evidence = File(fixture.directory, "${fixture.recordingId}.recording.json")
        assertTrue("Persist unknown completion before publishing active capture", evidence.isFile)
        assertEquals("INCOMPLETE", JSONObject(evidence.readText()).getString("endReason"))
        runBlocking { fixture.repository.stop(fixture.recordingId) }
        compose.waitUntil(5_000) { fixture.active() == null }
        assertTrue(fixture.ownedState().state is RecordingRuntimeState.Completed)
        assertEquals("USER_STOPPED", JSONObject(evidence.readText()).getString("endReason"))
        fixture.assertReadableOriginal()
    }

    @Test fun ordinaryTokenRefreshAndBackgroundKeepTheSameRecordingUntilExplicitStop() = withRecording { fixture ->
        val ownerEpoch = fixture.runtime.accountEpoch
        fixture.runtime.update("isolated-refresh-only", fixture.account)
        runBlocking { fixture.repository.start() }
        assertEquals(ownerEpoch, fixture.runtime.accountEpoch)
        assertEquals(fixture.recordingId, fixture.active()?.active?.recordingId)
        val beforeBackground = requireNotNull(fixture.active()).active.elapsedSeconds

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        try {
            compose.waitUntil(5_000) {
                fixture.active()?.active?.let {
                    it.recordingId == fixture.recordingId && it.elapsedSeconds > beforeBackground
                } == true
            }
            assertEquals(fixture.recordingId, (fixture.repository.state.value as RecordingRuntimeState.Recording).active.recordingId)
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        }

        runBlocking { fixture.repository.stop(fixture.recordingId) }
        compose.waitUntil(5_000) { fixture.active() == null }
        assertEquals(fixture.recordingId, (fixture.ownedState().state as RecordingRuntimeState.Completed).recordingId)
        fixture.assertReadableOriginal()
    }

    private fun withRecording(block: (Fixture) -> Unit) {
        // Check the unfiltered service state before creating any work or entering cleanup.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFalse("An existing recording must be left untouched", RecordingForegroundService.ownedRuntimeState.value.state is RecordingRuntimeState.Recording)
        val fixture = Fixture()
        assertFalse("The UUID recording directory must be new", fixture.directory.exists())
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(fixture.context, Manifest.permission.RECORD_AUDIO))
        compose.setContent { Text("隔离静音录音会话测试；无AI调用") }
        try {
            runBlocking { fixture.repository.start() }
            compose.waitUntil(10_000) { fixture.active()?.active?.elapsedSeconds?.let { it >= 2 } == true }
            fixture.recordingId = requireNotNull(fixture.active()).active.recordingId
            block(fixture)
        } finally {
            fixture.cleanup()
        }
    }

    private inner class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "service-epoch-${UUID.randomUUID()}"
        private val ownerKey = accountRecordingStorageKey(account)
        val directory = accountRecordingsDirectory(context.filesDir, account)
        val runtime = SessionRuntime().apply { update("isolated-initial-only", account) }
        val repository = AndroidRecordingRuntimeRepository(context, runtime)
        lateinit var recordingId: String

        fun ownedState() = RecordingForegroundService.ownedRuntimeState.value.also {
            assertEquals("Only this fixture's service state may be inspected", ownerKey, it.accountStorageKey)
        }

        fun active(): RecordingRuntimeState.Recording? {
            val current = RecordingForegroundService.ownedRuntimeState.value
            return if (current.accountStorageKey == ownerKey) current.state as? RecordingRuntimeState.Recording else null
        }

        fun assertRevokedAndOriginalRetained() {
            assertEquals(RecordingRuntimeState.Failed, repository.state.value)
            // Old repository state is already filtered; only the service can prove capture stopped.
            compose.waitUntil(5_000) {
                val current = RecordingForegroundService.ownedRuntimeState.value
                current.accountStorageKey == ownerKey && current.state !is RecordingRuntimeState.Recording
            }
            assertEquals("Session revocation must not report successful completion", RecordingRuntimeState.Failed, ownedState().state)
            assertReadableOriginal()
        }

        fun assertOldRepositoryCannotControlRecording() {
            // Evaluate stop before any accidentally accepted start can replace the completed ID.
            val stopFailure = runCatching { runBlocking { repository.stop(recordingId) } }.exceptionOrNull()
            val startFailure = runCatching { runBlocking { repository.start() } }.exceptionOrNull()
            if (startFailure == null) {
                // A RED start can enqueue a second service command. Observe its exact UUID owner
                // before failing, so finally also owns and stops that accidentally created capture.
                compose.waitUntil(5_000) {
                    active()?.active?.recordingId?.let { it != recordingId } == true
                }
            }
            assertTrue("A revoked repository must reject stop", stopFailure is IllegalStateException)
            assertTrue("A revoked repository must reject start before service dispatch", startFailure is IllegalStateException)
        }

        fun assertReadableOriginal() {
            val original = File(directory, "$recordingId.m4a")
            assertTrue("Revocation must preserve this fixture's original audio", original.isFile && original.length() > 0)
            val metadata = MediaMetadataRetriever()
            val duration = try {
                metadata.setDataSource(original.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                metadata.release()
            }
            assertTrue("The retained original must be a finalized, readable recording", duration != null && duration >= 1_000)
        }

        fun cleanup() {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val current = RecordingForegroundService.ownedRuntimeState.value
            if (current.accountStorageKey == ownerKey && current.state is RecordingRuntimeState.Recording) {
                // Never issue a stop for another owner, even if an unrelated test failed concurrently.
                context.stopService(Intent(context, RecordingForegroundService::class.java))
                compose.waitUntil(5_000) { active() == null }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
            assertTrue("Only the fixture's UUID directory is disposable", directory.name == ownerKey && directory.parentFile?.name == "recordings")
            assertTrue("Fixture recording cleanup must succeed", !directory.exists() || directory.deleteRecursively())
        }
    }
}
