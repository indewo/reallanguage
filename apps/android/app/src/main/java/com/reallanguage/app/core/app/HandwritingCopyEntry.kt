package com.reallanguage.app.core.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.BackHandler
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reallanguage.app.feature.learning.presentation.HandwritingCopyAction
import com.reallanguage.app.feature.learning.presentation.HandwritingCopyEffect
import com.reallanguage.app.feature.learning.presentation.HandwritingCopyScreen
import com.reallanguage.app.feature.learning.presentation.HandwritingCopyUiState
import com.reallanguage.app.feature.learning.presentation.HandwritingCopyViewModel

@Composable
internal fun HandwritingCopyEntry(
    factory: ViewModelProvider.Factory,
    entryKey: Any,
    onReturn: () -> Unit,
    onDictation: () -> Unit,
    content: @Composable (HandwritingCopyUiState, (HandwritingCopyAction) -> Unit) -> Unit = { state, onAction ->
        HandwritingCopyScreen(state, onAction)
    },
) {
    key(entryKey) {
        val owner = rememberEntryViewModelStoreOwner(entryKey)
        val copy: HandwritingCopyViewModel = viewModel(viewModelStoreOwner = owner, key = "handwriting", factory = factory)
        val state by copy.uiState.collectAsState()
        val currentReturn by rememberUpdatedState(onReturn)
        val currentDictation by rememberUpdatedState(onDictation)
        BackHandler { copy.onAction(HandwritingCopyAction.BackRequested) }
        LaunchedEffect(copy) {
            var navigated = false
            copy.effects.collect { effect ->
                if (!navigated) {
                    navigated = true
                    when (effect) {
                        HandwritingCopyEffect.ReturnToReadSpeak -> currentReturn()
                        HandwritingCopyEffect.OpenAudioDictation -> currentDictation()
                    }
                }
            }
        }
        content(state, copy::onAction)
    }
}
