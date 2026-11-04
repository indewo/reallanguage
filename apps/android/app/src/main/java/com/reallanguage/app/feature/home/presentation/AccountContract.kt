package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.feature.home.domain.AccountSnapshot

data class AccountUiState(
    val account: AccountSnapshot? = null,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val loggingOut: Boolean = false,
    val error: AccountError? = null,
)

enum class AccountError { LOGOUT_FAILED }

sealed interface AccountAction {
    data object RetryLoadRequested : AccountAction
    data object LocalRecordingsRequested : AccountAction
    data object BillingRequested : AccountAction
    data object DeleteAccountRequested : AccountAction
    data object LogoutRequested : AccountAction
    data object ReturnHomeRequested : AccountAction
}

sealed interface AccountEffect {
    data object OpenLocalRecordings : AccountEffect
    data object OpenBilling : AccountEffect
    data object OpenDeletionConfirmation : AccountEffect
    data object OpenLogin : AccountEffect
    data object OpenHome : AccountEffect
}
