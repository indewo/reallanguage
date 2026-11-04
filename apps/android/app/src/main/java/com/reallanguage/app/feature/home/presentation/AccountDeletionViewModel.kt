package com.reallanguage.app.feature.home.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.home.domain.AccountDeletionRepository
import com.reallanguage.app.feature.home.domain.AccountDeletionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AccountDeletionViewModel(private val repository: AccountDeletionRepository, private val commandId: String) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AccountDeletionUiState()); val uiState = mutableUiState.asStateFlow()
    private val effectChannel = Channel<AccountDeletionEffect>(Channel.BUFFERED); val effects = effectChannel.receiveAsFlow()
    fun onAction(action: AccountDeletionAction) {
        when (action) {
            AccountDeletionAction.CancelRequested -> if (!mutableUiState.value.deleting) effectChannel.trySend(AccountDeletionEffect.Close)
            AccountDeletionAction.ConfirmRequested -> delete()
        }
    }
    private fun delete() {
        if (mutableUiState.value.deleting) return
        mutableUiState.value = AccountDeletionUiState(deleting = true)
        viewModelScope.launch {
            val result = try { repository.delete(commandId) } catch (c: CancellationException) { throw c } catch (_: Exception) { AccountDeletionResult.Failed }
            when (result) {
                AccountDeletionResult.Deleted -> effectChannel.send(AccountDeletionEffect.OpenLogin)
                AccountDeletionResult.Failed -> mutableUiState.value = AccountDeletionUiState(failed = true)
            }
        }
    }
}
