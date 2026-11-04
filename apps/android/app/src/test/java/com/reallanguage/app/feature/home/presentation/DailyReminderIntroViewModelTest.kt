package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.home.domain.DailyReminderChoice
import com.reallanguage.app.feature.home.domain.DailyReminderIntroRepository
import com.reallanguage.app.feature.home.domain.DailyReminderIntroResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DailyReminderIntroViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun openingNoticeNeverRequestsPermissionAutomatically() {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        assertTrue(viewModel.uiState.value.visible)
        assertTrue(repository.choices.isEmpty())
    }

    @Test fun enableRequestsAndroidPermissionBeforeWritingPreference() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(DailyReminderIntroAction.EnableRequested)
        assertEquals(DailyReminderIntroEffect.RequestNotificationPermission, effect.await())
        assertTrue(repository.choices.isEmpty())
    }

    @Test fun grantedPermissionRecordsEnabledAndClosesNotice() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.onAction(DailyReminderIntroAction.EnableRequested)
        assertEquals(DailyReminderIntroEffect.RequestNotificationPermission, viewModel.effects.first())
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(DailyReminderIntroAction.PermissionResult(granted = true))
        advanceUntilIdle()
        assertEquals(listOf(DailyReminderChoice.ENABLED), repository.choices)
        assertEquals(DailyReminderIntroEffect.CloseNotice, effect.await())
    }

    @Test fun deniedPermissionRecordsDismissedAndDoesNotBlockHome() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.onAction(DailyReminderIntroAction.EnableRequested)
        assertEquals(DailyReminderIntroEffect.RequestNotificationPermission, viewModel.effects.first())
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(DailyReminderIntroAction.PermissionResult(granted = false))
        advanceUntilIdle()
        assertEquals(listOf(DailyReminderChoice.DISMISSED), repository.choices)
        assertEquals(DailyReminderIntroEffect.CloseNotice, effect.await())
    }

    @Test fun notNowClosesEvenWhenSavingFailsWithoutClaimingThePreferenceWasSaved() = runTest {
        val repository = FakeRepository(DailyReminderIntroResult.Failed)
        val viewModel = viewModel(repository)
        viewModel.onAction(DailyReminderIntroAction.NotNowRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.visible)
        assertEquals(DailyReminderIntroError.SAVE_FAILED, viewModel.uiState.value.error)
        assertEquals(listOf(DailyReminderChoice.DISMISSED), repository.choices)
        assertEquals(DailyReminderIntroEffect.CloseNotice, viewModel.effects.first())
        viewModel.onAction(DailyReminderIntroAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(listOf(DailyReminderChoice.DISMISSED), repository.choices)
    }

    @Test fun repeatedEnableOnlyRequestsPermissionOnceAndLateGrantAfterDismissDoesNothing() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        val effects = mutableListOf<DailyReminderIntroEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.collect(effects::add) }
        repeat(3) { viewModel.onAction(DailyReminderIntroAction.EnableRequested) }
        assertEquals(listOf(DailyReminderIntroEffect.RequestNotificationPermission), effects)
        viewModel.onAction(DailyReminderIntroAction.NotNowRequested)
        advanceUntilIdle()
        viewModel.onAction(DailyReminderIntroAction.PermissionResult(true))
        advanceUntilIdle()
        assertEquals(listOf(DailyReminderChoice.DISMISSED), repository.choices)
        assertEquals(listOf(DailyReminderIntroEffect.RequestNotificationPermission, DailyReminderIntroEffect.CloseNotice), effects)
    }

    @Test fun enableSaveFailureKeepsRetryAndTheSameCommandButDismissStillCloses() = runTest {
        val repository = FakeRepository(DailyReminderIntroResult.Failed)
        val viewModel = viewModel(repository)
        viewModel.onAction(DailyReminderIntroAction.EnableRequested)
        viewModel.effects.first()
        viewModel.onAction(DailyReminderIntroAction.PermissionResult(true))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.visible)
        viewModel.onAction(DailyReminderIntroAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(listOf("reminder-command", "reminder-command"), repository.commandIds)
        viewModel.onAction(DailyReminderIntroAction.NotNowRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.visible)
        assertEquals(DailyReminderIntroEffect.CloseNotice, viewModel.effects.first())
    }

    private fun viewModel(repository: FakeRepository) = DailyReminderIntroViewModel(
        repository = repository,
        commandId = "reminder-command",
    )

    private class FakeRepository(
        var result: DailyReminderIntroResult = DailyReminderIntroResult.Saved,
    ) : DailyReminderIntroRepository {
        val choices = mutableListOf<DailyReminderChoice>()
        val commandIds = mutableListOf<String>()
        override suspend fun recordChoice(choice: DailyReminderChoice, commandId: String): DailyReminderIntroResult {
            choices += choice
            commandIds += commandId
            return result
        }
    }
}
