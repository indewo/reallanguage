package com.reallanguage.app.feature.recording.data

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecordingRuntimeIsolationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun anotherAccountCannotObserveAnActiveRecording() = withIsolatedRecording { _, _, other ->
        assertFalse("Another account must not see the original recording identity/timer", other.state.value is RecordingRuntimeState.Recording)
    }

    @Test fun anotherAccountCannotStopTheOriginalRecording() = withIsolatedRecording { original, id, other ->
        val attempt = runCatching { runBlocking { other.stop(id) } }
        assertTrue("An unauthorized stop must be rejected before dispatch", attempt.isFailure)
        assertTrue(original.state.value is RecordingRuntimeState.Recording)
    }

    @Test fun interruptedServiceCannotRemainReportedAsRecording() = withIsolatedRecording { original, _, _ ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.stopService(android.content.Intent(context, RecordingForegroundService::class.java))
        compose.waitUntil(5_000) { original.state.value !is RecordingRuntimeState.Recording }
        assertTrue("Unexpected interruption must be explicit, not successful completion", original.state.value is RecordingRuntimeState.Failed)
    }

    @Test fun serviceRejectsAStopIntentWithTheWrongAccountEvenIfTheRecordingIdMatches() = withIsolatedRecording { original, id, _ ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = (original.state.value as RecordingRuntimeState.Recording).active.elapsedSeconds
        context.startService(android.content.Intent(context, RecordingForegroundService::class.java)
            .setAction(RecordingForegroundService.ACTION_STOP)
            .putExtra(RecordingForegroundService.EXTRA_RECORDING_ID, id)
            .putExtra(RecordingForegroundService.EXTRA_ACCOUNT_STORAGE_KEY, accountRecordingStorageKey("not-the-owner")))
        compose.waitUntil(5_000) { (original.state.value as? RecordingRuntimeState.Recording)?.active?.elapsedSeconds?.let { it > before } == true }
        assertTrue(original.state.value is RecordingRuntimeState.Recording)
    }

    private fun withIsolatedRecording(block: (AndroidRecordingRuntimeRepository, String, AndroidRecordingRuntimeRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "runtime-isolation-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-local-only", account) }
        val repository = AndroidRecordingRuntimeRepository(context, runtime)
        val other = AndroidRecordingRuntimeRepository(context, SessionRuntime().apply { update("other-isolated-local-only", "other-$account") })
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        compose.setContent { Text("隔离录音账号测试；无AI调用") }
        var id: String? = null
        try {
            assertFalse("Do not interrupt an existing recording", RecordingForegroundService.runtimeState.value is RecordingRuntimeState.Recording)
            runBlocking { repository.start() }
            compose.waitUntil(10_000) { (repository.state.value as? RecordingRuntimeState.Recording)?.active?.elapsedSeconds?.let { it >= 1 } == true }
            id = (repository.state.value as RecordingRuntimeState.Recording).active.recordingId
            block(repository, id, other)
        } finally {
            if (id != null) {
                runCatching { runBlocking { repository.stop(id) } }
                // This helper only owns the fresh recording it created; no pre-existing service is interrupted.
                context.stopService(android.content.Intent(context, RecordingForegroundService::class.java))
                compose.waitForIdle()
                accountRecordingsDirectory(context.filesDir, account).deleteRecursively()
            }
        }
    }
}
