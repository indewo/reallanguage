package com.reallanguage.app.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.SubsequentSettingsLoadResult
import com.reallanguage.app.feature.settings.domain.SubsequentSettingsRepository
import com.reallanguage.app.feature.settings.domain.SubsequentSettingsSaveResult
import com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft
import com.reallanguage.app.feature.settings.domain.ReminderSettingsRepository
import com.reallanguage.app.feature.settings.domain.SettingsSaveCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SubsequentSettingsViewModel(
    private val repository: SubsequentSettingsRepository,
    private val saveCommandId: String,
    private val reminderRepository: ReminderSettingsRepository? = null,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SubsequentSettingsUiState())
    val uiState: StateFlow<SubsequentSettingsUiState> = mutableUiState.asStateFlow()
    private val effectChannel = Channel<SubsequentSettingsEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var loadInFlight = false
    private var effectScopeOpened = false
    private var saveSequence = 0
    private val remoteCommands = mutableMapOf<Pair<InitialSettingsDraft, InitialSettingsDraft>, String>()
    private var pendingSave: SettingsSaveCoordinator? = null
    private var versionConflict = false

    init { load() }

    fun onAction(action: SubsequentSettingsAction) {
        if (mutableUiState.value.error == SubsequentSettingsError.VERSION_CONFLICT &&
            action != SubsequentSettingsAction.ReturnRequested) return
        when (action) {
            is SubsequentSettingsAction.ReminderEnabledChanged -> updateReminder { copy(enabled = action.enabled) }
            is SubsequentSettingsAction.ReminderTimeChanged -> updateReminder { copy(time = action.time) }
            is SubsequentSettingsAction.FormalityChanged -> {
                require(action.level in 1..5)
                update { copy(formalityLevel = action.level) }
            }
            is SubsequentSettingsAction.SlangChanged -> update { copy(slangEnabled = action.enabled) }
            is SubsequentSettingsAction.BasicVocabularyChanged -> update {
                copy(basicVocabulary = action.value, purposeVocabularies = emptySet())
            }
            is SubsequentSettingsAction.PurposeVocabularyChanged -> update {
                copy(purposeVocabularies = if (action.selected) purposeVocabularies + action.value else purposeVocabularies - action.value)
            }
            is SubsequentSettingsAction.StyleReferenceChanged -> update { copy(styleReference = action.value) }
            is SubsequentSettingsAction.VoiceChanged -> update {
                copy(voiceEnabled = action.enabled, autoPlayEnabled = autoPlayEnabled.takeIf { action.enabled } ?: false)
            }
            is SubsequentSettingsAction.AutoPlayChanged -> update {
                copy(autoPlayEnabled = voiceEnabled == true && action.enabled)
            }
            is SubsequentSettingsAction.SpeechSpeedChanged -> {
                require(action.level in 1..5)
                update { copy(speechSpeedLevel = action.level) }
            }
            SubsequentSettingsAction.SaveRequested -> save()
            SubsequentSettingsAction.ReturnRequested -> if (!mutableUiState.value.saving) {
                effectChannel.trySend(SubsequentSettingsEffect.OpenHome)
            }
            SubsequentSettingsAction.RetryRequested -> if (!mutableUiState.value.saving) {
                if (mutableUiState.value.error == SubsequentSettingsError.SAVE_FAILED) save() else load()
            }
        }
    }

    private fun update(change: InitialSettingsDraft.() -> InitialSettingsDraft) {
        mutableUiState.update { state ->
            if (state.saving || state.loading || state.draft == null || effectScopeOpened) state
            else {
                val next = state.draft.change()
                if (next == state.draft) state else {
                    pendingSave = null
                    state.copy(draft = next, error = null, saveProgress = null)
                }
            }
        }
    }

    private fun updateReminder(change: ReminderSettingsDraft.() -> ReminderSettingsDraft) {
        mutableUiState.update { state ->
            if (state.saving || state.loading || state.reminderDraft == null || effectScopeOpened) state
            else {
                val next = state.reminderDraft.change()
                if (next == state.reminderDraft) state else {
                    pendingSave = null
                    state.copy(reminderDraft = next, error = null, saveProgress = null)
                }
            }
        }
    }

    private fun load() {
        if (loadInFlight || mutableUiState.value.saving) return
        loadInFlight = true
        mutableUiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            var reminder: ReminderSettingsDraft? = null
            val result = try {
                val settings = repository.load()
                reminder = reminderRepository?.load()
                if (reminderRepository != null && reminder == null) SubsequentSettingsLoadResult.Failed else settings
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                SubsequentSettingsLoadResult.Failed
            }
            loadInFlight = false
            when (result) {
                is SubsequentSettingsLoadResult.Ready -> mutableUiState.value = SubsequentSettingsUiState(
                    original = result.settings,
                    draft = result.settings,
                    loading = false,
                    originalReminder = reminder,
                    reminderDraft = reminder,
                )
                SubsequentSettingsLoadResult.Failed -> mutableUiState.update {
                    it.copy(loading = false, error = SubsequentSettingsError.LOAD_FAILED)
                }
            }
        }
    }

    private fun save() {
        val state = mutableUiState.value
        val original = state.original ?: return
        val proposed = state.draft ?: return
        if (state.loading || state.saving || !state.changed || effectScopeOpened) return
        if (original.generationFields() != proposed.generationFields()) {
            effectScopeOpened = true
            effectChannel.trySend(SubsequentSettingsEffect.OpenEffectScope(original, proposed,
                state.originalReminder, state.reminderDraft))
            return
        }
        val normalized = proposed.copy(autoPlayEnabled = proposed.voiceEnabled == true && proposed.autoPlayEnabled == true)
        val originalReminder = state.originalReminder
        val proposedReminder = state.reminderDraft
        val coordinator = pendingSave ?: run {
            // Local reminder edits do not change the remote payload or authorize another remote command.
            // Once acknowledged, `original` changes, so a later edit back is a new transition, not a replay.
            val commandId = remoteCommands.getOrPut(original to normalized) {
                if (saveSequence++ == 0) saveCommandId else "$saveCommandId:$saveSequence"
            }
            SettingsSaveCoordinator(
                settingsRequired = normalized != original,
                reminderRequired = proposedReminder != originalReminder,
                saveSettings = {
                    val result = repository.save(normalized, commandId)
                    versionConflict = result == SubsequentSettingsSaveResult.Conflict
                    result == SubsequentSettingsSaveResult.Saved
                },
                saveReminder = {
                    originalReminder != null && proposedReminder != null &&
                        reminderRepository?.save(originalReminder, proposedReminder) == true
                },
            ).also { pendingSave = it }
        }
        mutableUiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val progress = coordinator.save()
            mutableUiState.update {
                it.copy(saving = false, saveProgress = progress,
                    original = if (progress.settingsSaved) normalized else it.original,
                    originalReminder = if (progress.reminderSaved) proposedReminder else it.originalReminder,
                    error = when {
                        progress.completed -> null
                        versionConflict -> SubsequentSettingsError.VERSION_CONFLICT
                        else -> SubsequentSettingsError.SAVE_FAILED
                    })
            }
            if (progress.completed) effectChannel.send(SubsequentSettingsEffect.OpenHome)
        }
    }

    private fun InitialSettingsDraft.generationFields() = listOf(
        formalityLevel,
        slangEnabled,
        basicVocabulary,
        purposeVocabularies,
        styleReference,
    )
}
