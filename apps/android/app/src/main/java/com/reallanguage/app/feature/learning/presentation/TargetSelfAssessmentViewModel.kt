package com.reallanguage.app.feature.learning.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.learning.domain.SelfAssessmentAbility
import com.reallanguage.app.feature.learning.domain.SelfAssessmentTarget
import com.reallanguage.app.feature.learning.domain.TargetSelfAssessmentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class TargetSelfAssessmentUiState(
    val targets: List<SelfAssessmentTarget> = emptyList(),
    val busy: Boolean = false,
    val failed: Boolean = false,
)
sealed interface TargetSelfAssessmentAction {
    data class Mark(val targetId: String, val ability: SelfAssessmentAbility) : TargetSelfAssessmentAction
    data object Retry : TargetSelfAssessmentAction
}
class TargetSelfAssessmentViewModel(private val repository: TargetSelfAssessmentRepository) : ViewModel() {
    private val state = MutableStateFlow(TargetSelfAssessmentUiState())
    val uiState = state.asStateFlow()
    private var pendingMark: TargetSelfAssessmentAction.Mark? = null

    init { reload() }

    fun onAction(action: TargetSelfAssessmentAction) {
        if (state.value.busy) return
        when (action) {
            TargetSelfAssessmentAction.Retry -> pendingMark?.let(::mark) ?: reload()
            is TargetSelfAssessmentAction.Mark -> {
                if (state.value.failed) return
                val target = state.value.targets.singleOrNull { it.targetId == action.targetId } ?: return
                if (action.ability !in target.abilities || action.ability in target.assessedAbilities) return
                pendingMark = action
                mark(action)
            }
        }
    }

    private fun reload() {
        state.value = state.value.copy(busy = true, failed = false)
        viewModelScope.launch {
            try {
                state.value = TargetSelfAssessmentUiState(targets = repository.load())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(busy = false, failed = true) }
        }
    }

    private fun mark(action: TargetSelfAssessmentAction.Mark) {
        state.value = state.value.copy(busy = true, failed = false)
        viewModelScope.launch {
            try {
                check(repository.mark(action.targetId, action.ability))
                val saved = repository.load()
                check(saved.single { it.targetId == action.targetId }.assessedAbilities.contains(action.ability))
                pendingMark = null
                state.value = TargetSelfAssessmentUiState(targets = saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(busy = false, failed = true) }
        }
    }
}
