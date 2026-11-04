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
import com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionAction
import com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionEffect
import com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionScreen
import com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionUiState
import com.reallanguage.app.feature.learning.presentation.ReadWriteCorrectionViewModel

@Composable
internal fun ReadWriteCorrectionEntry(
    factory: ViewModelProvider.Factory,
    entryKey: Any,
    onReturn: () -> Unit,
    onSpeaking: () -> Unit,
    content: @Composable (ReadWriteCorrectionUiState, (ReadWriteCorrectionAction) -> Unit) -> Unit = { state, onAction ->
        ReadWriteCorrectionScreen(state, onAction)
    },
) {
    key(entryKey) {
        val owner = rememberEntryViewModelStoreOwner(entryKey)
        val correction: ReadWriteCorrectionViewModel = viewModel(viewModelStoreOwner = owner, key = "read-write-correction", factory = factory)
        val state by correction.uiState.collectAsState()
        val currentReturn by rememberUpdatedState(onReturn)
        val currentSpeaking by rememberUpdatedState(onSpeaking)
        BackHandler { correction.onAction(ReadWriteCorrectionAction.BackRequested) }
        LaunchedEffect(correction) {
            var navigated = false
            correction.effects.collect { effect ->
                if (!navigated) {
                    navigated = true
                    when (effect) {
                        ReadWriteCorrectionEffect.ReturnToDictation -> currentReturn()
                        ReadWriteCorrectionEffect.OpenFullSentenceSpeaking -> currentSpeaking()
                    }
                }
            }
        }
        content(state, correction::onAction)
    }
}
