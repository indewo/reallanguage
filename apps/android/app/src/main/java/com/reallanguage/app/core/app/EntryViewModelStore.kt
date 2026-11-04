package com.reallanguage.app.core.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** For transient entries only: leaving cancels work and reopening reloads persisted facts. */
@Composable
internal fun rememberEntryViewModelStoreOwner(key: Any): ViewModelStoreOwner {
    val store = remember(key) { ViewModelStore() }
    val owner = remember(store) {
        object : ViewModelStoreOwner { override val viewModelStore = store }
    }
    DisposableEffect(store) { onDispose { store.clear() } }
    return owner
}
