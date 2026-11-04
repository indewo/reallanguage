package com.reallanguage.app.core.app

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal inline fun <reified T : ViewModel> rememberSpeakingEntryViewModel(
    key: String,
    factory: ViewModelProvider.Factory,
    repositoryIdentity: Any,
): T {
    val owner = rememberEntryViewModelStoreOwner(listOf(key, repositoryIdentity))
    return viewModel(viewModelStoreOwner = owner, key = key, factory = factory)
}
