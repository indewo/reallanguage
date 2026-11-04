package com.reallanguage.app.feature.recording.data

import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.RecordingDeletionResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDeletionIsolationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun staleRepositoryCannotDeleteNewOwnersAudio() = runBlocking {
        val owner = "delete-owner-${UUID.randomUUID()}"
        val other = "delete-other-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-local-only", owner) }
        val repository = AndroidRecordingDeletionRepository(context, runtime)
        val directory = accountRecordingsDirectory(context.filesDir, other).apply { mkdirs() }
        val audio = File(directory, "recording-1.m4a").apply { writeText("isolated disposable data") }
        try {
            runtime.update("other-isolated-local-only", other)
            assertEquals(RecordingDeletionResult.Failed, repository.delete("recording-1", "command-1"))
            assertTrue(audio.isFile)
        } finally { directory.deleteRecursively() }
    }

    @Test fun traversalCannotDeleteAnotherAccountsAudio() = runBlocking {
        val owner = "delete-owner-${UUID.randomUUID()}"
        val other = "delete-other-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-local-only", owner) }
        val repository = AndroidRecordingDeletionRepository(context, runtime)
        val ownerDirectory = accountRecordingsDirectory(context.filesDir, owner).apply { mkdirs() }
        val otherDirectory = accountRecordingsDirectory(context.filesDir, other).apply { mkdirs() }
        val audio = File(otherDirectory, "recording-1.m4a").apply { writeText("isolated disposable data") }
        try {
            assertEquals(RecordingDeletionResult.Failed, repository.delete("../${accountRecordingStorageKey(other)}/recording-1", "command-1"))
            assertTrue(audio.isFile)
        } finally { ownerDirectory.deleteRecursively(); otherDirectory.deleteRecursively() }
    }

    @Test fun validDeletionRemovesOnlyTheRequestedOriginalAndStopEvidence() = runBlocking {
        val owner = "delete-positive-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-local-only", owner) }
        val repository = AndroidRecordingDeletionRepository(context, runtime)
        val directory = accountRecordingsDirectory(context.filesDir, owner).apply { mkdirs() }
        try {
            listOf("recording-1.m4a", "recording-1.index", "recording-1.recording.json", "another.m4a", "transcript.json")
                .forEach { File(directory, it).writeText("isolated disposable data") }
            assertEquals(RecordingDeletionResult.Deleted, repository.delete("recording-1", "command-1"))
            assertEquals(setOf("another.m4a", "transcript.json"), directory.list()!!.toSet())
            assertEquals(RecordingDeletionResult.AlreadyDeleted, repository.delete("recording-1", "command-1"))
        } finally { directory.deleteRecursively() }
    }
}
