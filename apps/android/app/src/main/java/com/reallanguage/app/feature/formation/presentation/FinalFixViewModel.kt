package com.reallanguage.app.feature.formation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.formation.domain.ContentFixRepository
import com.reallanguage.app.feature.formation.domain.ContentFixRequest
import com.reallanguage.app.feature.formation.domain.ContentFixResult
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.FixedSourceProjection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class FinalFixViewModel(
    private val formationFlowId: String,
    private val commandId: String,
    chapters: List<EnglishChapterDraft>,
    selectedCandidateIds: Map<String, String>,
    targets: List<LearningTarget>,
    selectedTargetIds: Set<String>,
    private val repository: ContentFixRepository,
    sourceType: String = "DIARY",
    sourceTitle: String = "日记",
    sourceProjection: FixedSourceProjection? = null,
) : ViewModel() {
    private val request = ContentFixRequest(
        formationFlowId = formationFlowId,
        commandId = commandId,
        chapters = chapters,
        selectedCandidateIds = selectedCandidateIds,
        targets = targets,
        selectedTargetIds = selectedTargetIds,
        sourceType = sourceType,
        sourceTitle = sourceTitle,
        sourceProjection = sourceProjection,
    )
    private val mutableUiState = MutableStateFlow(
        FinalFixUiState(
            chapters = chapters,
            selectedCandidateIds = selectedCandidateIds,
            selectedTargets = targets.filter { it.targetId in selectedTargetIds },
        ),
    )
    val uiState: StateFlow<FinalFixUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<FinalFixEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: FinalFixAction) {
        when (action) {
            FinalFixAction.ConfirmRequested -> fix()
            FinalFixAction.BackRequested -> {
                if (mutableUiState.value.submitting) return
                viewModelScope.launch {
                    effectChannel.send(FinalFixEffect.ReturnToLearningTargets)
                }
            }
        }
    }

    private fun fix() {
        val current = mutableUiState.value
        if (current.submitting || !current.confirmEnabled) return
        mutableUiState.update {
            it.copy(submitting = true, fixFailed = false, confirmEnabled = false)
        }
        viewModelScope.launch {
            val result = try { repository.fix(request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ContentFixResult.Failed }
            when (result) {
                is ContentFixResult.Fixed -> {
                    effectChannel.send(FinalFixEffect.OpenFirstChapter(result.firstChapterId))
                }
                ContentFixResult.Failed -> mutableUiState.update {
                    it.copy(submitting = false, fixFailed = true, confirmEnabled = true)
                }
            }
        }
    }
}
