package com.reallanguage.app.core.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.BackHandler
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reallanguage.app.feature.formation.presentation.FinalFixEffect
import com.reallanguage.app.feature.formation.presentation.FinalFixAction
import com.reallanguage.app.feature.formation.presentation.FinalFixScreen
import com.reallanguage.app.feature.formation.presentation.FinalFixViewModel

@Composable
internal fun FinalFixEntry(
    factory: ViewModelProvider.Factory,
    entryId: String,
    onFixed: suspend (String) -> Unit,
    onReturn: () -> Unit,
) {
    val owner = rememberEntryViewModelStoreOwner("final-fix:$entryId")
    val fix: FinalFixViewModel = viewModel(viewModelStoreOwner = owner, key = "final-fix:$entryId", factory = factory)
    val state by fix.uiState.collectAsState()
    val currentFixed by rememberUpdatedState(onFixed)
    val currentReturn by rememberUpdatedState(onReturn)
    BackHandler { fix.onAction(FinalFixAction.BackRequested) }
    LaunchedEffect(fix) {
        var navigated = false
        fix.effects.collect { effect ->
            if (!navigated) {
                navigated = true
                when (effect) {
                    is FinalFixEffect.OpenFirstChapter -> currentFixed(effect.chapterId)
                    FinalFixEffect.ReturnToLearningTargets -> currentReturn()
                }
            }
        }
    }
    FinalFixScreen(state, fix::onAction)
}
