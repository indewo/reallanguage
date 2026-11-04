package com.reallanguage.app.core.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reallanguage.app.feature.learning.presentation.LearningExitAction
import com.reallanguage.app.feature.learning.presentation.LearningExitDialog
import com.reallanguage.app.feature.learning.presentation.LearningExitEffect
import com.reallanguage.app.feature.learning.presentation.LearningExitViewModel

/** Closing the dialog destroys its work; reopening receives a new page-owned model. */
@Composable
internal fun LearningExitEntry(
    factory: ViewModelProvider.Factory,
    entryKey: Any,
    onClose: () -> Unit,
    onHome: () -> Unit,
    onAbandon: () -> Unit,
) {
    val owner = rememberEntryViewModelStoreOwner(entryKey)
    val exit: LearningExitViewModel = viewModel(viewModelStoreOwner = owner, key = "learning-exit", factory = factory)
    val state by exit.uiState.collectAsState()
    val currentClose by rememberUpdatedState(onClose)
    val currentHome by rememberUpdatedState(onHome)
    val currentAbandon by rememberUpdatedState(onAbandon)
    BackHandler { if (!state.saving) exit.onAction(LearningExitAction.ContinueLearningRequested) }
    LaunchedEffect(exit) {
        var navigated = false
        exit.effects.collect { effect ->
            if (!navigated) {
                navigated = true
                when (effect) {
                    LearningExitEffect.CloseDialog -> currentClose()
                    LearningExitEffect.ReturnHome -> currentHome()
                    LearningExitEffect.OpenAbandonConfirmation -> currentAbandon()
                }
            }
        }
    }
    LearningExitDialog(state, exit::onAction)
}
