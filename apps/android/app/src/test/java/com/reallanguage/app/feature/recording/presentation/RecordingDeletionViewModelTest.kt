package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.recording.domain.RecordingDeletionRepository
import com.reallanguage.app.feature.recording.domain.RecordingDeletionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingDeletionViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun cancelReturnsToConfirmationWithoutDeleting() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(RecordingDeletionAction.CancelRequested)
        assertEquals(RecordingDeletionEffect.ReturnToConfirmation("r1"), effect.await())
        assertEquals(0, repository.calls)
    }

    @Test fun confirmDeletesLocalFileAndIndexThenReturnsHome() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(RecordingDeletionAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(listOf("r1" to "delete-command"), repository.commands)
        assertEquals(RecordingDeletionEffect.OpenHome, effect.await())
    }

    @Test fun replayedCommandIsIdempotentlySuccessful() = runTest {
        val repository = FakeRepository(RecordingDeletionResult.AlreadyDeleted)
        val viewModel = viewModel(repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(RecordingDeletionAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(RecordingDeletionEffect.OpenHome, effect.await())
    }

    @Test fun failureKeepsDialogOpenForSameCommandRetry() = runTest {
        val repository = FakeRepository(RecordingDeletionResult.Failed)
        val viewModel = viewModel(repository)
        viewModel.onAction(RecordingDeletionAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(RecordingDeletionError.DELETE_FAILED, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.deleting)
        repository.result = RecordingDeletionResult.Deleted
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(RecordingDeletionAction.ConfirmRequested)
        advanceUntilIdle()
        assertEquals(listOf("delete-command", "delete-command"), repository.commands.map { it.second })
        assertEquals(RecordingDeletionEffect.OpenHome, effect.await())
    }

    private fun viewModel(repository: FakeRepository) = RecordingDeletionViewModel(
        recordingId = "r1", commandId = "delete-command", repository = repository,
    )

    private class FakeRepository(
        var result: RecordingDeletionResult = RecordingDeletionResult.Deleted,
    ) : RecordingDeletionRepository {
        val commands = mutableListOf<Pair<String, String>>()
        val calls get() = commands.size
        override suspend fun delete(recordingId: String, commandId: String): RecordingDeletionResult {
            commands += recordingId to commandId
            return result
        }
    }
}
