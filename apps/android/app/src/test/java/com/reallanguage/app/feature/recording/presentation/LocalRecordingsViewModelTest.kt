package com.reallanguage.app.feature.recording.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.recording.domain.LocalRecordingEntry
import com.reallanguage.app.feature.recording.domain.LocalRecordingsLoadResult
import com.reallanguage.app.feature.recording.domain.LocalRecordingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalRecordingsViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @Test fun listsNewestLocalRecordingsFirst() = runTest {
        val viewModel = LocalRecordingsViewModel(FakeRepository(LocalRecordingsLoadResult.Ready(listOf(older, newer))))
        advanceUntilIdle(); assertEquals(listOf(newer, older), viewModel.uiState.value.recordings)
    }
    @Test fun opensOnlyAnExistingRecordingAndReturnsToAccount() = runTest {
        val viewModel = LocalRecordingsViewModel(FakeRepository(LocalRecordingsLoadResult.Ready(listOf(newer))))
        advanceUntilIdle(); var effect = async { viewModel.effects.first() }
        viewModel.onAction(LocalRecordingsAction.RecordingRequested("new")); assertEquals(LocalRecordingsEffect.OpenRecording("new"), effect.await())
        effect = async { viewModel.effects.first() }; viewModel.onAction(LocalRecordingsAction.ReturnAccountRequested)
        assertEquals(LocalRecordingsEffect.ReturnAccount, effect.await())
    }
    @Test fun failedLoadCanRetry() = runTest {
        val repository = FakeRepository(LocalRecordingsLoadResult.Failed); val viewModel = LocalRecordingsViewModel(repository)
        advanceUntilIdle(); assertTrue(viewModel.uiState.value.loadFailed)
        repository.result = LocalRecordingsLoadResult.Ready(emptyList()); viewModel.onAction(LocalRecordingsAction.RetryRequested); advanceUntilIdle()
        assertTrue(viewModel.uiState.value.recordings.isEmpty()); assertTrue(!viewModel.uiState.value.loadFailed)
    }
    private class FakeRepository(var result: LocalRecordingsLoadResult) : LocalRecordingsRepository { override suspend fun load() = result }
    companion object {
        private val older = LocalRecordingEntry("old", 100)
        private val newer = LocalRecordingEntry("new", 200)
    }
}
