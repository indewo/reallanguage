package com.reallanguage.app.feature.recording.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.RecordingLoadResult
import com.reallanguage.app.feature.recording.domain.RecordingPlaybackResult
import com.reallanguage.app.feature.recording.domain.LocalRecordingsLoadResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingConfirmationIsolationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun actualAudioCompletionReachesTheTranscriptRepositoryWithoutNetwork() = runBlocking {
        val owner = "playback-events-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-local-only", owner) }
        val repository = HttpRecordingRepositories(context, object : com.reallanguage.app.core.network.HttpTransport {
            override suspend fun execute(request: com.reallanguage.app.core.network.HttpRequest): com.reallanguage.app.core.network.HttpResponse = error("Local playback must never send a network request")
        }, runtime)
        val directory = accountRecordingsDirectory(context.filesDir, owner).apply { mkdirs() }
        try {
            silentWave(File(directory, "recording-1.m4a"))
            assertEquals(RecordingPlaybackResult.Playing, repository.togglePlayback("recording-1"))
            withTimeout(5_000) { requireNotNull(repository.playbackState).first { it == RecordingPlaybackResult.Stopped } }
            assertEquals(RecordingPlaybackResult.Playing, repository.togglePlayback("recording-1"))
            repository.stopPlayback()
            assertEquals(RecordingPlaybackResult.Stopped, repository.playbackState?.value)
        } finally { repository.stopPlayback(); directory.deleteRecursively() }
    }

    @Test fun unreadableOriginalReportsPlaybackFailureAndAValidRetryCanPlay() = runBlocking {
        val owner = "playback-retry-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-local-only", owner) }
        val repository = AndroidRecordingConfirmationRepository(context, runtime)
        val directory = accountRecordingsDirectory(context.filesDir, owner).apply { mkdirs() }
        try {
            val audio = File(directory, "recording-1.m4a")
            audio.writeText("invalid isolated audio fixture")
            assertEquals(RecordingPlaybackResult.Failed, repository.togglePlayback("recording-1"))
            assertEquals(RecordingPlaybackResult.Failed, repository.playbackState.value)
            silentWave(audio)
            assertEquals(RecordingPlaybackResult.Playing, repository.togglePlayback("recording-1"))
        } finally { repository.stopPlayback(); directory.deleteRecursively() }
    }

    @Test fun staleLocalListCannotDisplayTheNewAccountsFiles() = runBlocking {
        val owner = "local-list-owner-${UUID.randomUUID()}"
        val other = "local-list-other-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("local-test-only", owner) }
        val repository = AndroidLocalRecordingsRepository(context, runtime)
        val directory = accountRecordingsDirectory(context.filesDir, other).apply { mkdirs() }
        try {
            File(directory, "private-other.m4a").writeText("isolated disposable fixture")
            runtime.update("other-local-test-only", other)
            assertEquals(LocalRecordingsLoadResult.Failed, repository.load())
        } finally { directory.deleteRecursively() }
    }

    @Test fun existingRepositoryCannotReadTheNewAccountsRecording() = runBlocking {
        val owner = "confirmation-owner-${UUID.randomUUID()}"
        val other = "confirmation-other-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("local-test-only", owner) }
        val repository = AndroidRecordingConfirmationRepository(context, runtime)
        val directory = accountRecordingsDirectory(context.filesDir, other).apply { mkdirs() }
        try {
            silentWave(File(directory, "recording-1.m4a"))
            runtime.update("other-local-test-only", other)
            assertEquals(RecordingLoadResult.Failed, repository.load("recording-1"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun traversalCannotPlayAnotherAccountsFile() = runBlocking {
        val owner = "confirmation-owner-${UUID.randomUUID()}"
        val other = "confirmation-other-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("local-test-only", owner) }
        val repository = AndroidRecordingConfirmationRepository(context, runtime)
        val ownerDirectory = accountRecordingsDirectory(context.filesDir, owner).apply { mkdirs() }
        val otherDirectory = accountRecordingsDirectory(context.filesDir, other).apply { mkdirs() }
        try {
            silentWave(File(otherDirectory, "recording-1.m4a"))
            val path = "../${accountRecordingStorageKey(other)}/recording-1"
            assertEquals(RecordingPlaybackResult.Failed, repository.togglePlayback(path))
        } finally {
            ownerDirectory.deleteRecursively()
            otherDirectory.deleteRecursively()
        }
    }

    @Test fun ownerCanLoadAndPlayAnActualLocalAudioFile() = runBlocking {
        val owner = "confirmation-positive-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("local-test-only", owner) }
        val repository = AndroidRecordingConfirmationRepository(context, runtime)
        val directory = accountRecordingsDirectory(context.filesDir, owner).apply { mkdirs() }
        try {
            silentWave(File(directory, "recording-1.m4a"))
            assertTrue(repository.load("recording-1") is RecordingLoadResult.Ready)
            assertEquals(RecordingPlaybackResult.Playing, repository.togglePlayback("recording-1"))
            repository.stopPlayback()
            assertEquals(RecordingPlaybackResult.Playing, repository.togglePlayback("recording-1"))
            assertEquals(RecordingPlaybackResult.Stopped, repository.togglePlayback("recording-1"))
        } finally { directory.deleteRecursively() }
    }

    // Native decoder fixture, intentionally silent; not speech/AI quality evidence.
    private fun silentWave(file: File) {
        val size = 16_000 * 2 * 3
        val buffer = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
        buffer.putShort(2).putShort(16).put("data".toByteArray()).putInt(size)
        file.writeBytes(buffer.array())
    }
}
