package com.reallanguage.app.feature.settings.presentation

import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.PurposeVocabulary
import com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft
import com.reallanguage.app.feature.settings.domain.SettingsSaveProgress
import java.time.LocalTime

enum class SubsequentSettingsError { LOAD_FAILED, SAVE_FAILED, VERSION_CONFLICT }

data class SubsequentSettingsUiState(
    val original: InitialSettingsDraft? = null,
    val draft: InitialSettingsDraft? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: SubsequentSettingsError? = null,
    val originalReminder: ReminderSettingsDraft? = null,
    val reminderDraft: ReminderSettingsDraft? = null,
    val saveProgress: SettingsSaveProgress? = null,
) {
    val changed: Boolean get() = original != null && (draft != original || reminderDraft != originalReminder)
}

sealed interface SubsequentSettingsAction {
    data class FormalityChanged(val level: Int) : SubsequentSettingsAction
    data class SlangChanged(val enabled: Boolean) : SubsequentSettingsAction
    data class BasicVocabularyChanged(val value: BasicVocabulary) : SubsequentSettingsAction
    data class PurposeVocabularyChanged(val value: PurposeVocabulary, val selected: Boolean) : SubsequentSettingsAction
    data class StyleReferenceChanged(val value: String) : SubsequentSettingsAction
    data class VoiceChanged(val enabled: Boolean) : SubsequentSettingsAction
    data class AutoPlayChanged(val enabled: Boolean) : SubsequentSettingsAction
    data class SpeechSpeedChanged(val level: Int) : SubsequentSettingsAction
    data class ReminderEnabledChanged(val enabled: Boolean) : SubsequentSettingsAction
    data class ReminderTimeChanged(val time: LocalTime) : SubsequentSettingsAction
    data object SaveRequested : SubsequentSettingsAction
    data object ReturnRequested : SubsequentSettingsAction
    data object RetryRequested : SubsequentSettingsAction
}

sealed interface SubsequentSettingsEffect {
    data object OpenHome : SubsequentSettingsEffect
    data class OpenEffectScope(
        val original: InitialSettingsDraft,
        val proposed: InitialSettingsDraft,
        val originalReminder: ReminderSettingsDraft? = null,
        val proposedReminder: ReminderSettingsDraft? = null,
    ) : SubsequentSettingsEffect
}
