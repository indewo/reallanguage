package com.reallanguage.app.feature.home.domain
sealed interface AccountDeletionResult { data object Deleted : AccountDeletionResult; data object Failed : AccountDeletionResult }
fun interface AccountDeletionRepository { suspend fun delete(commandId: String): AccountDeletionResult }
