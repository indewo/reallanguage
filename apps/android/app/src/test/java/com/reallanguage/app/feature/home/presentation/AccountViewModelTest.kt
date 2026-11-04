package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.home.domain.AccountRepository
import com.reallanguage.app.feature.home.domain.AccountSnapshot
import com.reallanguage.app.feature.home.domain.AccountSnapshotResult
import com.reallanguage.app.feature.home.domain.LogoutResult
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
class AccountViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @Test fun loadsServerAccountInformation() = runTest {
        val viewModel = viewModel(FakeRepository())
        advanceUntilIdle()
        assertEquals(snapshot, viewModel.uiState.value.account)
    }
    @Test fun ordinaryEntriesRouteWithoutMutatingAccount() = runTest {
        val viewModel = viewModel(FakeRepository())
        advanceUntilIdle()
        val expected = listOf(AccountEffect.OpenLocalRecordings, AccountEffect.OpenBilling, AccountEffect.OpenHome)
        val actions = listOf(AccountAction.LocalRecordingsRequested, AccountAction.BillingRequested, AccountAction.ReturnHomeRequested)
        actions.zip(expected).forEach { (action, wanted) ->
            val effect = async { viewModel.effects.first() }
            viewModel.onAction(action)
            assertEquals(wanted, effect.await())
        }
    }
    @Test fun deletionOnlyOpensIndependentConfirmation() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(AccountAction.DeleteAccountRequested)
        assertEquals(AccountEffect.OpenDeletionConfirmation, effect.await())
        assertEquals(0, repository.logoutCount)
    }
    @Test fun logoutRevokesSessionAndOpensSharedLoginEntry() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(AccountAction.LogoutRequested)
        advanceUntilIdle()
        assertEquals(1, repository.logoutCount)
        assertEquals(AccountEffect.OpenLogin, effect.await())
    }
    @Test fun loadAndLogoutFailuresStayRetryable() = runTest {
        val repository = FakeRepository(loadResult = AccountSnapshotResult.Failed)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)
        repository.loadResult = AccountSnapshotResult.Ready(snapshot)
        viewModel.onAction(AccountAction.RetryLoadRequested)
        advanceUntilIdle()
        repository.logoutResult = LogoutResult.Failed
        viewModel.onAction(AccountAction.LogoutRequested)
        advanceUntilIdle()
        assertEquals(AccountError.LOGOUT_FAILED, viewModel.uiState.value.error)
    }
    private fun viewModel(repository: FakeRepository) = AccountViewModel(repository, "logout-command")
    private class FakeRepository(
        var loadResult: AccountSnapshotResult = AccountSnapshotResult.Ready(snapshot),
        var logoutResult: LogoutResult = LogoutResult.LoggedOut,
    ) : AccountRepository {
        var logoutCount = 0
        override suspend fun load(): AccountSnapshotResult = loadResult
        override suspend fun logout(commandId: String): LogoutResult { logoutCount++; return logoutResult }
    }
    companion object { private val snapshot = AccountSnapshot("learner@example.com") }
}
