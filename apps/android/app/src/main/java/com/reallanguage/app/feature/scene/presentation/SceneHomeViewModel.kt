package com.reallanguage.app.feature.scene.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.scene.domain.SceneHomeRepository
import com.reallanguage.app.feature.scene.domain.SceneRecommendationResult
import com.reallanguage.app.feature.scene.domain.SceneStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SceneHomeViewModel(
    private val repository: SceneHomeRepository,
    initialStart: SceneStart? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        SceneHomeUiState(
            directDescription = (initialStart as? SceneStart.DirectDescription)?.description.orEmpty(),
        ),
    )
    val uiState: StateFlow<SceneHomeUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<SceneHomeEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    init { loadRecommendations() }

    fun onAction(action: SceneHomeAction) {
        when (action) {
            is SceneHomeAction.RecommendationSelected -> {
                mutableUiState.value.recommendations
                    .firstOrNull { it.recommendationId == action.recommendationId }
                    ?.let { effectChannel.trySend(SceneHomeEffect.OpenTypeSelection(SceneStart.Recommendation(it))) }
            }
            is SceneHomeAction.TemplateSelected -> {
                mutableUiState.value.templates
                    .firstOrNull { it.templateId == action.templateId }
                    ?.let { effectChannel.trySend(SceneHomeEffect.OpenTypeSelection(SceneStart.Template(it))) }
            }
            SceneHomeAction.DirectDescriptionRequested -> effectChannel.trySend(
                SceneHomeEffect.OpenDirectDescription(mutableUiState.value.directDescription),
            )
            is SceneHomeAction.DescriptionChanged -> mutableUiState.update {
                it.copy(directDescription = action.description)
            }
            SceneHomeAction.DirectDescriptionSubmitted -> {
                val description = mutableUiState.value.directDescription.trim()
                if (description.isNotEmpty()) {
                    effectChannel.trySend(
                        SceneHomeEffect.OpenTypeSelection(SceneStart.DirectDescription(description)),
                    )
                }
            }
        }
    }

    private fun loadRecommendations() {
        viewModelScope.launch {
            val cached = try { repository.loadRecommendations() }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { SceneRecommendationResult.Unavailable }
            mutableUiState.update {
                it.copy(
                    // A non-empty local snapshot is immediately usable. For an empty snapshot,
                    // keep the neutral loading state until the cheap fingerprint check finishes,
                    // avoiding a false "none" flash before a newly eligible item appears.
                    loadingRecommendations = cached !is SceneRecommendationResult.Ready || cached.recommendations.isEmpty(),
                    recommendations = when (cached) {
                        is SceneRecommendationResult.Ready -> cached.recommendations.take(4)
                        SceneRecommendationResult.Unavailable -> emptyList()
                    },
                )
            }
            val refreshed = try { repository.refreshRecommendationsIfNeeded() }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { null }
            mutableUiState.update { state ->
                state.copy(
                    loadingRecommendations = false,
                    recommendations = when (refreshed) {
                        is SceneRecommendationResult.Ready -> refreshed.recommendations.take(4)
                        else -> state.recommendations
                    },
                )
            }
        }
    }
}
