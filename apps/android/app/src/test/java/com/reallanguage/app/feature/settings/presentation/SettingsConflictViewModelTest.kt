package com.reallanguage.app.feature.settings.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.settings.domain.*
import java.time.LocalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsConflictViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val original = InitialSettingsDraft(3, false, BasicVocabulary.CET_6, setOf(PurposeVocabulary.GRE), "Clear", true, false, 3)
    private val reminder = ReminderSettingsDraft(false, LocalTime.of(20, 0))
    private var localSaves = 0
    private val local = object : ReminderSettingsRepository {
        override suspend fun load() = reminder
        override suspend fun save(expected: ReminderSettingsDraft, proposed: ReminderSettingsDraft): Boolean { localSaves++; return true }
    }
    @Test fun immediateConflictKeepsBothDraftsAndDoesNotRetryRebaseOrSubmitLocalSettings() = runTest {
        var loads = 0; var saves = 0
        val repo = object : SubsequentSettingsRepository {
            override suspend fun load(): SubsequentSettingsLoadResult { loads++; return SubsequentSettingsLoadResult.Ready(original) }
            override suspend fun save(settings: InitialSettingsDraft, commandId: String): SubsequentSettingsSaveResult { saves++; return SubsequentSettingsSaveResult.Conflict }
        }
        val vm = SubsequentSettingsViewModel(repo, "conflict", local)
        val effects = mutableListOf<SubsequentSettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.onAction(SubsequentSettingsAction.SpeechSpeedChanged(4))
        vm.onAction(SubsequentSettingsAction.ReminderTimeChanged(LocalTime.of(21, 0)))
        vm.onAction(SubsequentSettingsAction.SaveRequested); advanceUntilIdle()
        assertEquals(SubsequentSettingsError.VERSION_CONFLICT, vm.uiState.value.error)
        vm.onAction(SubsequentSettingsAction.RetryRequested)
        vm.onAction(SubsequentSettingsAction.SaveRequested)
        vm.onAction(SubsequentSettingsAction.SpeechSpeedChanged(5))
        vm.onAction(SubsequentSettingsAction.ReminderTimeChanged(LocalTime.of(22, 0)))
        advanceUntilIdle()
        assertEquals(1, loads); assertEquals(1, saves); assertEquals(0, localSaves)
        assertEquals(original.copy(speechSpeedLevel = 4), vm.uiState.value.draft)
        assertEquals(reminder.copy(time = LocalTime.of(21, 0)), vm.uiState.value.reminderDraft)
        assertTrue(effects.isEmpty())
        vm.onAction(SubsequentSettingsAction.ReturnRequested); advanceUntilIdle()
        assertEquals(listOf(SubsequentSettingsEffect.OpenHome), effects)
    }
    @Test fun scopeConflictDoesNotGenerateOrApplyRemindersAndRetainsExitWithoutRebasing() = runTest {
        var saves = 0
        val vm = SettingsEffectScopeViewModel(original, original.copy(formalityLevel = 4),
            SettingsSourceContext.FixedContent("source", "中性测试内容"),
            SettingsEffectRepository { saves++; SettingsEffectApplyResult.Conflict }, "conflict", local, reminder, reminder.copy(enabled = true))
        val effects = mutableListOf<SettingsEffectScopeEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        vm.onAction(SettingsEffectScopeAction.ReprocessRequested); advanceUntilIdle()
        assertEquals(SettingsEffectScopeError.VERSION_CONFLICT, vm.uiState.value.error)
        vm.onAction(SettingsEffectScopeAction.RetryRequested)
        vm.onAction(SettingsEffectScopeAction.NextContentRequested)
        advanceUntilIdle()
        assertEquals(1, saves); assertEquals(0, localSaves); assertTrue(effects.isEmpty())
        assertEquals(original.copy(formalityLevel = 4), vm.uiState.value.proposedSettings)
        vm.onAction(SettingsEffectScopeAction.CancelRequested); advanceUntilIdle()
        assertEquals(listOf(SettingsEffectScopeEffect.ReturnToSettings), effects)
    }
}
