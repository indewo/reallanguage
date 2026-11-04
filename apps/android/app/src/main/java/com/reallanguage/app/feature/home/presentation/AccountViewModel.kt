package com.reallanguage.app.feature.home.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.home.domain.AccountRepository
import com.reallanguage.app.feature.home.domain.AccountSnapshotResult
import com.reallanguage.app.feature.home.domain.LogoutResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AccountViewModel(
    private val repository: AccountRepository,
    private val logoutCommandId: String,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AccountUiState())
    val uiState: StateFlow<AccountUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<AccountEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init { load() }

    fun onAction(action: AccountAction) {
        when (action) {
            AccountAction.RetryLoadRequested -> load()
            AccountAction.LocalRecordingsRequested -> effectChannel.trySend(AccountEffect.OpenLocalRecordings)
            AccountAction.BillingRequested -> effectChannel.trySend(AccountEffect.OpenBilling)
            AccountAction.DeleteAccountRequested -> effectChannel.trySend(AccountEffect.OpenDeletionConfirmation)
            AccountAction.LogoutRequested -> logout()
            AccountAction.ReturnHomeRequested -> effectChannel.trySend(AccountEffect.OpenHome)
        }
    }

    private fun load() {
        mutableUiState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            val result = try { repository.load() }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { AccountSnapshotResult.Failed }
            mutableUiState.value = when (result) {
                is AccountSnapshotResult.Ready -> AccountUiState(account = result.snapshot, loading = false)
                AccountSnapshotResult.Failed -> AccountUiState(loading = false, loadFailed = true)
            }
        }
    }

    private fun logout() {
        if (mutableUiState.value.loggingOut) return
        mutableUiState.update { it.copy(loggingOut = true, error = null) }
        viewModelScope.launch {
            val result = try { repository.logout(logoutCommandId) }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { LogoutResult.Failed }
            when (result) {
                LogoutResult.LoggedOut -> {
                    mutableUiState.update { it.copy(loggingOut = false) }
                    effectChannel.send(AccountEffect.OpenLogin)
                }
                LogoutResult.Failed -> mutableUiState.update {
                    it.copy(loggingOut = false, error = AccountError.LOGOUT_FAILED)
                }
            }
        }
    }
}
