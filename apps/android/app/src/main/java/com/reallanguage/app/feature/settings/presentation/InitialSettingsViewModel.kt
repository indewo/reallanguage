package com.reallanguage.app.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.settings.domain.InitialSettingsCompletionResult
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.InitialSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class InitialSettingsViewModel(
    private val validator: InitialSettingsValidator,
    private val repository: InitialSettingsRepository,
    private val isOwnerActive: () -> Boolean = { true },
) : ViewModel() {
    private var departed = false
    private val mutableUiState = MutableStateFlow(InitialSettingsUiState())
    val uiState: StateFlow<InitialSettingsUiState> = mutableUiState.asStateFlow()

    private val effectChannel = Channel<InitialSettingsEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()

    fun onAction(action: InitialSettingsAction) {
        if (departed || !isOwnerActive()) return
        when (action) {
            is InitialSettingsAction.FormalityChanged -> {
                require(action.level in 1..5)
                updateDraft { copy(formalityLevel = action.level) }
            }
            is InitialSettingsAction.SlangChanged ->
                updateDraft { copy(slangEnabled = action.enabled) }
            is InitialSettingsAction.BasicVocabularyChanged ->
                updateDraft { copy(basicVocabulary = action.vocabulary, purposeVocabularies = emptySet()) }
            is InitialSettingsAction.PurposeVocabularyChanged -> updateDraft {
                copy(
                    purposeVocabularies = if (action.selected) {
                        purposeVocabularies + action.vocabulary
                    } else {
                        purposeVocabularies - action.vocabulary
                    },
                )
            }
            is InitialSettingsAction.StyleReferenceChanged ->
                updateDraft { copy(styleReference = action.value) }
            is InitialSettingsAction.VoiceChanged ->
                updateDraft { copy(voiceEnabled = action.enabled) }
            is InitialSettingsAction.AutoPlayChanged ->
                updateDraft { copy(autoPlayEnabled = action.enabled) }
            is InitialSettingsAction.SpeechSpeedChanged -> {
                require(action.level in 1..5)
                updateDraft { copy(speechSpeedLevel = action.level) }
            }
            InitialSettingsAction.ContinueRequested -> continueToNextStep()
            InitialSettingsAction.BackRequested -> returnToPreviousStep()
            InitialSettingsAction.CompleteRequested -> complete()
        }
    }

    private fun updateDraft(change: InitialSettingsDraft.() -> InitialSettingsDraft) {
        mutableUiState.update { current ->
            val draft = current.draft.change()
            current.copy(
                draft = draft,
                continueEnabled = validator.isComplete(current.step, draft),
                error = null,
            )
        }
    }

    private fun continueToNextStep() {
        mutableUiState.update { current ->
            if (!validator.isComplete(current.step, current.draft)) return@update current
            val next = when (current.step) {
                InitialSettingsStep.EXPRESSION_BASICS -> InitialSettingsStep.USAGE_PREFERENCES
                InitialSettingsStep.USAGE_PREFERENCES -> InitialSettingsStep.VOICE_SETTINGS
                InitialSettingsStep.VOICE_SETTINGS -> return@update current
            }
            current.copy(
                step = next,
                continueEnabled = validator.isComplete(next, current.draft),
                error = null,
            )
        }
    }

    private fun returnToPreviousStep() {
        mutableUiState.update { current ->
            val previous = when (current.step) {
                InitialSettingsStep.EXPRESSION_BASICS -> return@update current
                InitialSettingsStep.USAGE_PREFERENCES -> InitialSettingsStep.EXPRESSION_BASICS
                InitialSettingsStep.VOICE_SETTINGS -> InitialSettingsStep.USAGE_PREFERENCES
            }
            current.copy(
                step = previous,
                continueEnabled = validator.isComplete(previous, current.draft),
                error = null,
            )
        }
    }

    private fun complete() {
        val current = mutableUiState.value
        if (
            current.step != InitialSettingsStep.VOICE_SETTINGS ||
            current.submitting ||
            !validator.isComplete(current.step, current.draft)
        ) return

        val settings = current.draft.copy(
            autoPlayEnabled = current.effectiveAutoPlay,
        )
        mutableUiState.update { it.copy(submitting = true, continueEnabled = false, error = null) }
        viewModelScope.launch {
            if (departed || !isOwnerActive()) return@launch
            val result = try {
                repository.complete(settings)
            } catch (cancellation: CancellationException) {
                if (!departed && isOwnerActive()) mutableUiState.update {
                    it.copy(submitting = false, continueEnabled = validator.isComplete(it.step, it.draft))
                }
                throw cancellation
            } catch (_: Exception) {
                InitialSettingsCompletionResult.Failed(retryable = false)
            }
            currentCoroutineContext().ensureActive()
            if (departed || !isOwnerActive()) return@launch
            when (result) {
                InitialSettingsCompletionResult.Completed -> {
                    mutableUiState.update { it.copy(submitting = false) }
                    effectChannel.send(InitialSettingsEffect.OpenHome)
                }
                is InitialSettingsCompletionResult.Failed -> mutableUiState.update {
                    it.copy(
                        submitting = false,
                        continueEnabled = validator.isComplete(it.step, it.draft),
                        error = InitialSettingsError.SAVE_FAILED,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        departed = true
        super.onCleared()
    }
}
