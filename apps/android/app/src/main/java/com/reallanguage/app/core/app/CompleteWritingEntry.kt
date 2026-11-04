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
import com.reallanguage.app.feature.learning.presentation.CompleteWritingAction
import com.reallanguage.app.feature.learning.presentation.CompleteWritingEffect
import com.reallanguage.app.feature.learning.presentation.CompleteWritingScreen
import com.reallanguage.app.feature.learning.presentation.CompleteWritingUiState
import com.reallanguage.app.feature.learning.presentation.CompleteWritingViewModel

@Composable
internal fun CompleteWritingEntry(
    factory: ViewModelProvider.Factory,
    entryKey: Any,
    onReturn: () -> Unit,
    onSceneResponse: () -> Unit,
    content: @Composable (CompleteWritingUiState, (CompleteWritingAction) -> Unit) -> Unit = { state, onAction ->
        CompleteWritingScreen(state, onAction)
    },
) {
    key(entryKey) {
        val owner = rememberEntryViewModelStoreOwner(entryKey)
        val writing: CompleteWritingViewModel = viewModel(viewModelStoreOwner = owner, key = "complete-writing", factory = factory)
        val state by writing.uiState.collectAsState()
        val currentReturn by rememberUpdatedState(onReturn)
        val currentSceneResponse by rememberUpdatedState(onSceneResponse)
        BackHandler { writing.onAction(CompleteWritingAction.BackRequested) }
        LaunchedEffect(writing) {
            var navigated = false
            writing.effects.collect { effect ->
                if (!navigated) {
                    navigated = true
                    when (effect) {
                        CompleteWritingEffect.ReturnToPatternSpeaking -> currentReturn()
                        CompleteWritingEffect.OpenSceneResponse -> currentSceneResponse()
                    }
                }
            }
        }
        content(state, writing::onAction)
    }
}
