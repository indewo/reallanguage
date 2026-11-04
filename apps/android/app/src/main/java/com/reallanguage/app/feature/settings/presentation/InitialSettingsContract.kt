package com.reallanguage.app.feature.settings.presentation

import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.PurposeVocabulary

enum class InitialSettingsStep {
    EXPRESSION_BASICS,
    USAGE_PREFERENCES,
    VOICE_SETTINGS,
}

enum class InitialSettingsError {
    SAVE_FAILED,
}

data class InitialSettingsUiState(
    val step: InitialSettingsStep = InitialSettingsStep.EXPRESSION_BASICS,
    val draft: InitialSettingsDraft = InitialSettingsDraft(),
    val continueEnabled: Boolean = false,
    val submitting: Boolean = false,
    val error: InitialSettingsError? = null,
) {
    val effectiveAutoPlay: Boolean
        get() = draft.voiceEnabled == true && draft.autoPlayEnabled == true
}

sealed interface InitialSettingsAction {
    data class FormalityChanged(val level: Int) : InitialSettingsAction
    data class SlangChanged(val enabled: Boolean) : InitialSettingsAction
    data class BasicVocabularyChanged(val vocabulary: BasicVocabulary) : InitialSettingsAction
    data class PurposeVocabularyChanged(
        val vocabulary: PurposeVocabulary,
        val selected: Boolean,
    ) : InitialSettingsAction
    data class StyleReferenceChanged(val value: String) : InitialSettingsAction
    data class VoiceChanged(val enabled: Boolean) : InitialSettingsAction
    data class AutoPlayChanged(val enabled: Boolean) : InitialSettingsAction
    data class SpeechSpeedChanged(val level: Int) : InitialSettingsAction
    data object ContinueRequested : InitialSettingsAction
    data object BackRequested : InitialSettingsAction
    data object CompleteRequested : InitialSettingsAction
}

sealed interface InitialSettingsEffect {
    data object OpenHome : InitialSettingsEffect
}

fun interface InitialSettingsValidator {
    fun isComplete(step: InitialSettingsStep, draft: InitialSettingsDraft): Boolean
}

object ConfirmedInitialSettingsValidator : InitialSettingsValidator {
    override fun isComplete(
        step: InitialSettingsStep,
        draft: InitialSettingsDraft,
    ): Boolean = when (step) {
        InitialSettingsStep.EXPRESSION_BASICS ->
            draft.formalityLevel in 1..5 &&
                draft.slangEnabled != null &&
                draft.basicVocabulary != null
        InitialSettingsStep.USAGE_PREFERENCES -> true
        InitialSettingsStep.VOICE_SETTINGS ->
            draft.voiceEnabled != null &&
                draft.speechSpeedLevel in 1..5 &&
                (draft.voiceEnabled == false || draft.autoPlayEnabled != null)
    }
}
