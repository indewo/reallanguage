package com.reallanguage.app.core.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.BackHandler
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.presentation.*

@Composable
internal fun EnglishConfirmationProgressEntry(
    factory: ViewModelProvider.Factory,
    entryKey: Any,
    onNext: (List<EnglishChapterDraft>, Map<String, String>) -> Unit,
    onReturn: () -> Unit,
    onStateChanged: (EnglishConfirmationUiState) -> Unit = {},
) {
    val owner = rememberEntryViewModelStoreOwner(entryKey)
    val confirmation: EnglishConfirmationViewModel = viewModel(viewModelStoreOwner = owner, key = "english-confirmation", factory = factory)
    val state by confirmation.uiState.collectAsState()
    val currentNext by rememberUpdatedState(onNext)
    val currentReturn by rememberUpdatedState(onReturn)
    val currentStateChanged by rememberUpdatedState(onStateChanged)
    BackHandler { confirmation.onAction(EnglishConfirmationAction.BackRequested) }
    LaunchedEffect(confirmation) { confirmation.uiState.collect { currentStateChanged(it) } }
    LaunchedEffect(confirmation) {
        var navigated = false
        confirmation.effects.collect { effect ->
            if (!navigated) {
                currentStateChanged(confirmation.uiState.value)
                navigated = true
                when (effect) {
                    is EnglishConfirmationEffect.OpenLearningTargets -> currentNext(effect.chapters, effect.selectedCandidateIds)
                    EnglishConfirmationEffect.ReturnToFocusSelection -> currentReturn()
                }
            }
        }
    }
    EnglishConfirmationScreen(state, confirmation::onAction)
}
