package com.reallanguage.app.core.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.BackHandler
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.scene.domain.*
import com.reallanguage.app.feature.scene.presentation.*

@Composable
internal fun SceneChineseReviewEntry(
    factory: ViewModelProvider.Factory,
    entryKey: Any,
    onSafety: (ConfirmedSource, SceneGeneratedDraft) -> Unit,
    onQuestions: (SceneQuestionSession) -> Unit,
    onGeneration: (SceneQuestionSession, List<SceneDraftUnit>) -> Unit,
    retainedOwner: androidx.lifecycle.ViewModelStoreOwner? = null,
) {
    val owner = retainedOwner ?: rememberEntryViewModelStoreOwner(entryKey)
    // The same delivered draft keeps its edited VM when the user walks back through
    // questions, while an explicit regeneration must never reuse the old draft VM.
    val review: SceneChineseReviewViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "scene-review:$entryKey",
        factory = factory,
    )
    val state by review.uiState.collectAsState()
    val currentSafety by rememberUpdatedState(onSafety)
    val currentQuestions by rememberUpdatedState(onQuestions)
    val currentGeneration by rememberUpdatedState(onGeneration)
    BackHandler { review.onAction(SceneChineseReviewAction.ReturnToQuestionsRequested) }
    LaunchedEffect(review) {
        var navigated = false
        review.effects.collect { effect ->
            if (!navigated) {
                navigated = true
                when (effect) {
                    is SceneChineseReviewEffect.OpenSafetyCheck -> currentSafety(effect.source, effect.confirmedDraft)
                    is SceneChineseReviewEffect.ReturnToQuestions -> currentQuestions(effect.session)
                    is SceneChineseReviewEffect.OpenGeneration -> currentGeneration(effect.session, effect.previousUnits)
                }
            }
        }
    }
    SceneChineseReviewScreen(state, review::onAction)
}
