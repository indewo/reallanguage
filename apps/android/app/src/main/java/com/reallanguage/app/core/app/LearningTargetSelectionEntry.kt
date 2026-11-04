package com.reallanguage.app.core.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.BackHandler
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.presentation.LearningTargetEffect
import com.reallanguage.app.feature.formation.presentation.LearningTargetAction
import com.reallanguage.app.feature.formation.presentation.LearningTargetScreen
import com.reallanguage.app.feature.formation.presentation.LearningTargetUiState
import com.reallanguage.app.feature.formation.presentation.LearningTargetViewModel
import com.reallanguage.app.feature.learning.presentation.ExplanationPreparationAction
import com.reallanguage.app.feature.learning.presentation.ExplanationPreparationFailureDisplay
import com.reallanguage.app.feature.learning.presentation.ExplanationPreparationScreen
import com.reallanguage.app.feature.learning.presentation.ExplanationPreparationUiState

@Composable
internal fun LearningTargetSelectionEntry(
    factory: ViewModelProvider.Factory,
    entryId: String,
    onContinue: (Set<String>, List<LearningTarget>) -> Unit,
    onReturn: () -> Unit,
    onStateChanged: (LearningTargetUiState) -> Unit = {},
) {
    val owner = rememberEntryViewModelStoreOwner("learning-target-selection:$entryId")
    val targets: LearningTargetViewModel = viewModel(viewModelStoreOwner = owner, key = "learning-target-selection:$entryId", factory = factory)
    val state by targets.uiState.collectAsState()
    val currentContinue by rememberUpdatedState(onContinue)
    val currentReturn by rememberUpdatedState(onReturn)
    val currentStateChanged by rememberUpdatedState(onStateChanged)
    BackHandler { targets.onAction(LearningTargetAction.BackRequested) }
    LaunchedEffect(targets) {
        targets.uiState.collect { value ->
            if (!value.recoveryUnavailable) currentStateChanged(value)
        }
    }
    LaunchedEffect(targets) {
        var navigated = false
        targets.effects.collect { effect ->
            if (!navigated) {
                // The observer must see newly generated targets, exact choices and
                // current position before navigation. Persistence remains the caller's responsibility.
                val latest = targets.uiState.value
                if (!latest.recoveryUnavailable) currentStateChanged(latest)
                navigated = true
                when (effect) {
                    is LearningTargetEffect.OpenFinalConfirmation -> currentContinue(effect.selectedTargetIds, effect.targets)
                    LearningTargetEffect.ReturnToEnglishConfirmation -> currentReturn()
                }
            }
        }
    }
    if (state.recoveryUnavailable) {
        ExplanationPreparationScreen(
            ExplanationPreparationUiState(failed = true, failureMessage = "学习目标位置恢复失败"),
            ExplanationPreparationFailureDisplay(
                detail = null, backLabel = "返回英语表达", showRetry = false,
                title = "恢复学习目标",
                supporting = "暂时无法恢复上次选择的位置，请返回英语表达后重新进入。",
                stageLabels = listOf("读取已保存内容", "恢复选择位置", "返回英语表达继续"),
            ),
        ) { action ->
            if (action == ExplanationPreparationAction.BackRequested) targets.onAction(LearningTargetAction.BackRequested)
        }
    } else {
        LearningTargetScreen(state, targets::onAction)
    }
}
