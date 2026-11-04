package com.reallanguage.app.core.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.network.HttpResponse
import com.reallanguage.app.core.network.HttpTransport
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.data.AndroidRecordingConfirmationRepository
import com.reallanguage.app.feature.recording.data.accountRecordingsDirectory
import com.reallanguage.app.feature.recording.domain.RecordingLoadResult
import com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual MediaPlayer + shared production lifecycle, silent native decoder fixture, no AI/audio-quality claim. */
class RecordingConnectionPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun liveRevocationBeforeRecompositionRejectsAnAlreadyQueuedPlaybackRequest() {
        withAudio { runtime, _ ->
            val allowed = java.util.concurrent.atomic.AtomicBoolean(true)
            val repository = AndroidRecordingConfirmationRepository(context, runtime, allowed::get)
            try {
                assertTrue(runBlocking { repository.load(ID) } is RecordingLoadResult.Ready)
                allowed.set(false)
                assertEquals(RecordingPlaybackResult.Failed, runBlocking { repository.togglePlayback(ID) })
                assertEquals(RecordingLoadResult.Failed, runBlocking { repository.load(ID) })
                allowed.set(true)
                assertEquals(RecordingPlaybackResult.Failed, repository.playbackState.value)
                assertEquals(RecordingPlaybackResult.Playing, runBlocking { repository.togglePlayback(ID) })
            } finally { repository.stopPlayback() }
        }
    }

    @Test fun connectionCoverStopsOriginalPlaybackWithoutRemovingTheEntryAndRecoveryDoesNotReplay() {
        withAudio { runtime, repository ->
            val graph = ProductionAppGraph(context, runtime, HttpTransport { HttpResponse(200, "{}") })
            val allowed = graph.playbackEligibility()
            compose.setContent {
                val incident by graph.connectionGate.incident.collectAsState()
                CompositionLocalProvider(LocalConnectionPlaybackPolicy provides ConnectionPlaybackPolicy(allowed, incident != null)) {
                    RecordingPlaybackLifecycle(repository, repository::stopPlayback)
                }
            }
            compose.waitForIdle()
            assertEquals(RecordingPlaybackResult.Playing, runBlocking { repository.togglePlayback(ID) })
            compose.runOnIdle { graph.connectionGate.networkLost() }
            compose.waitForIdle()
            assertEquals("A live but covered entry must stop its local original audio", RecordingPlaybackResult.Stopped, repository.playbackState.value)
            val incident = checkNotNull(graph.connectionGate.incident.value)
            compose.runOnIdle { graph.connectionGate.restoreVerified(incident) }
            compose.waitForIdle()
            assertEquals("Recovery is not another playback action", RecordingPlaybackResult.Stopped, repository.playbackState.value)
            assertEquals(RecordingPlaybackResult.Playing, runBlocking { repository.togglePlayback(ID) })
        }
    }

    @Test fun sameAccountNewSessionStopsOriginalPlaybackAndTheOldRepositoryCannotRestartIt() {
        withAudio { runtime, repository ->
            val graph = ProductionAppGraph(context, runtime, HttpTransport { HttpResponse(200, "{}") })
            val allowed = graph.playbackEligibility()
            compose.setContent {
                val revision by graph.connectionSessionRevision.collectAsState()
                val interrupted = androidx.compose.runtime.remember(revision) { !allowed() }
                CompositionLocalProvider(LocalConnectionPlaybackPolicy provides ConnectionPlaybackPolicy(allowed, interrupted)) {
                    RecordingPlaybackLifecycle(repository, repository::stopPlayback)
                }
            }
            compose.waitForIdle()
            assertEquals(RecordingPlaybackResult.Playing, runBlocking { repository.togglePlayback(ID) })
            compose.runOnIdle { runtime.update("isolated-reauthentication", runtime.accountId, newSession = true) }
            compose.waitForIdle()
            assertEquals(RecordingPlaybackResult.Stopped, repository.playbackState.value)
            assertEquals(RecordingLoadResult.Failed, runBlocking { repository.load(ID) })
            assertEquals(RecordingPlaybackResult.Failed, runBlocking { repository.togglePlayback(ID) })
            val fresh = AndroidRecordingConfirmationRepository(context, runtime)
            try { assertTrue(runBlocking { fresh.load(ID) } is RecordingLoadResult.Ready) } finally { fresh.stopPlayback() }
        }
    }

    @Test fun ordinaryTokenRefreshKeepsTheOriginalPlaybackAvailable() {
        withAudio { runtime, repository ->
            val graph = ProductionAppGraph(context, runtime, HttpTransport { HttpResponse(200, "{}") })
            val allowed = graph.playbackEligibility()
            compose.setContent {
                val revision by graph.connectionSessionRevision.collectAsState()
                val interrupted = androidx.compose.runtime.remember(revision) { !allowed() }
                CompositionLocalProvider(LocalConnectionPlaybackPolicy provides ConnectionPlaybackPolicy(allowed, interrupted)) {
                    RecordingPlaybackLifecycle(repository, repository::stopPlayback)
                }
            }
            compose.waitForIdle()
            assertEquals(RecordingPlaybackResult.Playing, runBlocking { repository.togglePlayback(ID) })
            compose.runOnIdle { runtime.update("isolated-refresh", runtime.accountId) }
            compose.waitForIdle()
            assertEquals(RecordingPlaybackResult.Playing, repository.playbackState.value)
        }
    }

    private fun withAudio(check: (SessionRuntime, AndroidRecordingConfirmationRepository) -> Unit) {
        val account = "recording-connection-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-test", account) }
        val repository = AndroidRecordingConfirmationRepository(context, runtime)
        val directory = accountRecordingsDirectory(context.filesDir, account).apply { mkdirs() }
        val original = File(directory, "$ID.m4a")
        // 30 seconds prevents natural audio completion from satisfying an interruption assertion.
        val size = 16_000 * 2 * 30
        val buffer = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
        buffer.putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
        original.writeBytes(buffer.array())
        try { check(runtime, repository); assertTrue("Interruption must preserve the original", original.isFile) }
        finally {
            repository.stopPlayback()
            require(directory.canonicalFile.parentFile == File(context.filesDir, "recordings").canonicalFile)
            directory.deleteRecursively()
        }
    }
    private companion object { const val ID = "isolated-original" }
}
