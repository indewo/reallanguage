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
import com.reallanguage.app.feature.learning.presentation.AudioDictationAction
import com.reallanguage.app.feature.learning.presentation.AudioDictationEffect
import com.reallanguage.app.feature.learning.presentation.AudioDictationScreen
import com.reallanguage.app.feature.learning.presentation.AudioDictationUiState
import com.reallanguage.app.feature.learning.presentation.AudioDictationViewModel

@Composable
internal fun AudioDictationEntry(
    factory: ViewModelProvider.Factory,
    entryKey: Any,
    onReturn: () -> Unit,
    onSpeaking: () -> Unit,
    onCorrection: (String) -> Unit,
    content: @Composable (AudioDictationUiState, (AudioDictationAction) -> Unit) -> Unit = { state, onAction ->
        AudioDictationScreen(state, onAction)
    },
) {
    key(entryKey) {
        val owner = rememberEntryViewModelStoreOwner(entryKey)
        val dictation: AudioDictationViewModel = viewModel(viewModelStoreOwner = owner, key = "audio-dictation", factory = factory)
        val state by dictation.uiState.collectAsState()
        val currentReturn by rememberUpdatedState(onReturn)
        val currentSpeaking by rememberUpdatedState(onSpeaking)
        val currentCorrection by rememberUpdatedState(onCorrection)
        BackHandler { dictation.onAction(AudioDictationAction.BackRequested) }
        LaunchedEffect(dictation) {
            var navigated = false
            dictation.effects.collect { effect ->
                if (!navigated) {
                    navigated = true
                    when (effect) {
                        AudioDictationEffect.ReturnToHandwriting -> currentReturn()
                        AudioDictationEffect.OpenFullSentenceSpeaking -> currentSpeaking()
                        is AudioDictationEffect.OpenReadWriteCorrection -> currentCorrection(effect.input)
                    }
                }
            }
        }
        content(state, dictation::onAction)
    }
}
