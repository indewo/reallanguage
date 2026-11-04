package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.home.domain.AccountDeletionRepository
import com.reallanguage.app.feature.home.domain.AccountDeletionResult
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
class AccountDeletionViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    @Test fun cancelClosesWithoutDeleting() = runTest {
        val repository = FakeRepository(); val viewModel = AccountDeletionViewModel(repository, "delete-1")
        val effect = async { viewModel.effects.first() }; viewModel.onAction(AccountDeletionAction.CancelRequested)
        assertEquals(AccountDeletionEffect.Close, effect.await()); assertEquals(0, repository.calls)
    }
    @Test fun explicitConfirmationDeletesAndOpensLogin() = runTest {
        val repository = FakeRepository(); val viewModel = AccountDeletionViewModel(repository, "delete-1")
        val effect = async { viewModel.effects.first() }; viewModel.onAction(AccountDeletionAction.ConfirmRequested); advanceUntilIdle()
        assertEquals(AccountDeletionEffect.OpenLogin, effect.await()); assertEquals(1, repository.calls)
    }
    @Test fun failureStaysRetryableWithTheSameCommand() = runTest {
        val repository = FakeRepository(AccountDeletionResult.Failed); val viewModel = AccountDeletionViewModel(repository, "delete-1")
        viewModel.onAction(AccountDeletionAction.ConfirmRequested); advanceUntilIdle(); assertTrue(viewModel.uiState.value.failed)
        repository.result = AccountDeletionResult.Deleted; val effect = async { viewModel.effects.first() }
        viewModel.onAction(AccountDeletionAction.ConfirmRequested); advanceUntilIdle(); assertEquals(AccountDeletionEffect.OpenLogin, effect.await())
        assertEquals(listOf("delete-1", "delete-1"), repository.commands)
    }
    private class FakeRepository(var result: AccountDeletionResult = AccountDeletionResult.Deleted) : AccountDeletionRepository {
        var calls = 0; val commands = mutableListOf<String>()
        override suspend fun delete(commandId: String): AccountDeletionResult { calls++; commands += commandId; return result }
    }
}
