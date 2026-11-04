package com.reallanguage.app.core.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.BackHandler
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reallanguage.app.feature.formation.presentation.*

@Composable
internal fun FocusSelectionProgressEntry(
    factory: ViewModelProvider.Factory,
    entryKey: Any,
    onConfirm: (List<String>) -> Unit,
    onReturn: () -> Unit,
    onStateChanged: (FocusSelectionUiState) -> Unit = {},
) {
    val owner = rememberEntryViewModelStoreOwner(entryKey)
    val focus: FocusSelectionViewModel = viewModel(viewModelStoreOwner = owner, key = "focus-selection", factory = factory)
    val state by focus.uiState.collectAsState()
    val currentConfirm by rememberUpdatedState(onConfirm)
    val currentReturn by rememberUpdatedState(onReturn)
    val currentStateChanged by rememberUpdatedState(onStateChanged)
    BackHandler { focus.onAction(FocusSelectionAction.BackRequested) }
    LaunchedEffect(focus) { focus.uiState.collect { currentStateChanged(it) } }
    LaunchedEffect(focus) {
        var navigated = false
        focus.effects.collect { effect ->
            if (!navigated) {
                currentStateChanged(focus.uiState.value)
                navigated = true
                when (effect) {
                    is FocusSelectionEffect.OpenChapterFormation -> currentConfirm(effect.selectedUnitIds)
                    FocusSelectionEffect.ReturnUpstream -> currentReturn()
                }
            }
        }
    }
    FocusSelectionScreen(state, focus::onAction)
}
