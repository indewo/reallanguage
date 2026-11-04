package com.reallanguage.app.feature.home.presentation
data class AccountDeletionUiState(val deleting: Boolean = false, val failed: Boolean = false)
sealed interface AccountDeletionAction { data object CancelRequested : AccountDeletionAction; data object ConfirmRequested : AccountDeletionAction }
sealed interface AccountDeletionEffect { data object Close : AccountDeletionEffect; data object OpenLogin : AccountDeletionEffect }
