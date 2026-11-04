package com.reallanguage.app.feature.settings.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.settings.domain.*
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReminderSettingsViewModelTest {
    @get:Rule val dispatcher = MainDispatcherRule()
    private val initial = InitialSettingsDraft(formalityLevel = 3, basicVocabulary = BasicVocabulary.CET_6,
        voiceEnabled = true, autoPlayEnabled = false, speechSpeedLevel = 3)
    private val reminder = ReminderSettingsDraft(false, LocalTime.of(20, 0))

    @Test fun reminderOnlyLoadsLocalDraftAndSavesWithoutRemoteMutationOrEffectScope() = runTest {
        val remote = Remote(initial); val local = Local(reminder)
        val vm = SubsequentSettingsViewModel(remote, "save", local)
        val effects = mutableListOf<SubsequentSettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        assertEquals(reminder, vm.uiState.value.reminderDraft)
        vm.onAction(SubsequentSettingsAction.ReminderTimeChanged(LocalTime.of(21, 15)))
        vm.onAction(SubsequentSettingsAction.ReminderEnabledChanged(true))
        assertTrue(vm.uiState.value.changed)
        vm.onAction(SubsequentSettingsAction.SaveRequested); advanceUntilIdle()
        assertEquals(ReminderSettingsDraft(true, LocalTime.of(21, 15)), local.current)
        assertTrue(remote.saved.isEmpty()); assertEquals(1, remote.loads)
        assertEquals(listOf(SubsequentSettingsEffect.OpenHome), effects)
    }

    @Test fun generationAndReminderDraftsReachEffectPageWithoutEitherBeingSavedAndCancelDiscardsBoth() = runTest {
        val remote = Remote(initial); val local = Local(reminder)
        val vm = SubsequentSettingsViewModel(remote, "save", local)
        val effects = mutableListOf<SubsequentSettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.onAction(SubsequentSettingsAction.FormalityChanged(4))
        vm.onAction(SubsequentSettingsAction.ReminderEnabledChanged(true))
        vm.onAction(SubsequentSettingsAction.SaveRequested); advanceUntilIdle()
        val next = effects.single() as SubsequentSettingsEffect.OpenEffectScope
        assertEquals(reminder, next.originalReminder)
        assertEquals(reminder.copy(enabled = true), next.proposedReminder)
        assertEquals(0, local.saves); assertTrue(remote.saved.isEmpty())
        val scope = EffectRemote()
        val effectVm = SettingsEffectScopeViewModel(next.original, next.proposed, SettingsSourceContext.NoCurrentContent,
            scope, "scope", local, next.originalReminder, next.proposedReminder)
        effectVm.onAction(SettingsEffectScopeAction.CancelRequested); advanceUntilIdle()
        assertEquals(0, local.saves); assertTrue(scope.commands.isEmpty())
    }

    @Test fun mixedImmediateSaveRetainsDraftAndRetriesOnlyLocalFailure() = runTest {
        val remote = Remote(initial); val local = Local(reminder).apply { success = false }
        val vm = SubsequentSettingsViewModel(remote, "save", local)
        val effects = mutableListOf<SubsequentSettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.onAction(SubsequentSettingsAction.SpeechSpeedChanged(4))
        vm.onAction(SubsequentSettingsAction.ReminderTimeChanged(LocalTime.of(21, 0)))
        vm.onAction(SubsequentSettingsAction.SaveRequested); advanceUntilIdle()
        assertEquals(SettingsSaveProgress(true, false), vm.uiState.value.saveProgress)
        assertEquals(SubsequentSettingsError.SAVE_FAILED, vm.uiState.value.error)
        assertTrue(effects.isEmpty()); assertEquals(4, vm.uiState.value.draft?.speechSpeedLevel)
        local.success = true
        vm.onAction(SubsequentSettingsAction.RetryRequested); advanceUntilIdle()
        assertEquals(1, remote.saved.size); assertEquals(2, local.saves)
        assertEquals(listOf(SubsequentSettingsEffect.OpenHome), effects)
        assertFalse(vm.uiState.value.changed)
    }

    @Test fun savePendingIgnoresReturnRetryDuplicateTapAndDraftEdits() = runTest {
        val remote = Remote(initial); val local = Local(reminder).apply { gate = CompletableDeferred() }
        val vm = SubsequentSettingsViewModel(remote, "save", local)
        val effects = mutableListOf<SubsequentSettingsEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        advanceUntilIdle()
        vm.onAction(SubsequentSettingsAction.ReminderEnabledChanged(true))
        vm.onAction(SubsequentSettingsAction.SaveRequested); runCurrent()
        assertTrue(vm.uiState.value.saving)
        vm.onAction(SubsequentSettingsAction.ReturnRequested)
        vm.onAction(SubsequentSettingsAction.RetryRequested)
        vm.onAction(SubsequentSettingsAction.SaveRequested)
        vm.onAction(SubsequentSettingsAction.ReminderTimeChanged(LocalTime.of(22, 0)))
        assertTrue(effects.isEmpty()); assertEquals(1, local.saves)
        local.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(SubsequentSettingsEffect.OpenHome), effects)
        assertEquals(LocalTime.of(20, 0), local.current.time)
    }

    @Test fun effectSaveKeepsRemoteAcknowledgedAndCannotCancelWhileApplying() = runTest {
        val local = Local(reminder).apply { gate = CompletableDeferred(); success = false }
        val remote = EffectRemote()
        val vm = SettingsEffectScopeViewModel(initial, initial.copy(formalityLevel = 4), SettingsSourceContext.NoCurrentContent,
            remote, "scope", local, reminder, reminder.copy(enabled = true))
        val effects = mutableListOf<SettingsEffectScopeEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        vm.onAction(SettingsEffectScopeAction.NextContentRequested); runCurrent()
        vm.onAction(SettingsEffectScopeAction.CancelRequested)
        vm.onAction(SettingsEffectScopeAction.NextContentRequested)
        assertTrue(effects.isEmpty()); assertTrue(vm.uiState.value.applying)
        local.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(SettingsSaveProgress(true, false), vm.uiState.value.saveProgress)
        assertEquals(SettingsEffectScopeError.APPLY_FAILED, vm.uiState.value.error)
        local.success = true
        vm.onAction(SettingsEffectScopeAction.RetryRequested); advanceUntilIdle()
        assertEquals(1, remote.commands.size); assertEquals(2, local.saves)
        assertEquals(listOf(SettingsEffectScopeEffect.OpenHome), effects)
    }

    @Test fun failedLocalLoadDoesNotExposeInventedDefaultsOrOverwritePreferences() = runTest {
        val remote = Remote(initial)
        val local = Local(reminder).apply { loadFails = true }
        val vm = SubsequentSettingsViewModel(remote, "save", local)
        advanceUntilIdle()
        assertEquals(SubsequentSettingsError.LOAD_FAILED, vm.uiState.value.error)
        assertNull(vm.uiState.value.reminderDraft)
        vm.onAction(SubsequentSettingsAction.ReminderEnabledChanged(true))
        vm.onAction(SubsequentSettingsAction.SaveRequested); advanceUntilIdle()
        assertEquals(0, local.saves)
    }

    @Test fun failedRemoteRetryDoesNotReloadAndEraseEitherDraft() = runTest {
        val remote = Remote(initial).apply { success = false }; val local = Local(reminder)
        val vm = SubsequentSettingsViewModel(remote, "save", local)
        advanceUntilIdle()
        vm.onAction(SubsequentSettingsAction.SpeechSpeedChanged(4))
        vm.onAction(SubsequentSettingsAction.ReminderTimeChanged(LocalTime.of(21, 0)))
        vm.onAction(SubsequentSettingsAction.SaveRequested); advanceUntilIdle()
        remote.success = true
        vm.onAction(SubsequentSettingsAction.RetryRequested); advanceUntilIdle()
        assertEquals(listOf("save", "save"), remote.ids)
        assertEquals(1, remote.loads); assertEquals(LocalTime.of(21, 0), local.current.time)
        assertEquals(4, remote.current.speechSpeedLevel)
    }

    @Test fun editingFailedPayloadUsesNewCommandWhileSamePayloadRetryKeepsIdentity() = runTest {
        val remote = Remote(initial).apply { success = false }; val local = Local(reminder)
        val vm = SubsequentSettingsViewModel(remote, "save", local)
        advanceUntilIdle()
        vm.onAction(SubsequentSettingsAction.SpeechSpeedChanged(4))
        vm.onAction(SubsequentSettingsAction.SaveRequested); advanceUntilIdle()
        vm.onAction(SubsequentSettingsAction.SpeechSpeedChanged(5))
        vm.onAction(SubsequentSettingsAction.SaveRequested); advanceUntilIdle()
        assertNotEquals(remote.ids[0], remote.ids[1])
        vm.onAction(SubsequentSettingsAction.RetryRequested); advanceUntilIdle()
        assertEquals(remote.ids[1], remote.ids[2])
        assertEquals(listOf(4, 5, 5), remote.saved.map { it.speechSpeedLevel })
    }

    @Test fun subsequentReturnWithoutSaveDiscardsLocalDraftAndMalformedEffectPlanDoesNotApplyRemote() = runTest {
        val remote = Remote(initial); val local = Local(reminder)
        val vm = SubsequentSettingsViewModel(remote, "save", local)
        advanceUntilIdle()
        vm.onAction(SubsequentSettingsAction.ReminderEnabledChanged(true))
        vm.onAction(SubsequentSettingsAction.ReturnRequested); advanceUntilIdle()
        assertEquals(0, local.saves); assertTrue(remote.saved.isEmpty())
        val effectRemote = EffectRemote()
        val scope = SettingsEffectScopeViewModel(initial, initial.copy(formalityLevel = 4),
            SettingsSourceContext.NoCurrentContent, effectRemote, "effect", local, reminder, null)
        scope.onAction(SettingsEffectScopeAction.NextContentRequested); advanceUntilIdle()
        assertEquals(SettingsEffectScopeError.APPLY_FAILED, scope.uiState.value.error)
        assertTrue(effectRemote.commands.isEmpty()); assertEquals(0, local.saves)
    }

    @Test fun editingOnlyReminderAfterRemoteFailureKeepsTheSameRemoteCommand() = runTest {
        val remote = Remote(initial).apply { success = false }; val local = Local(reminder)
        val vm = SubsequentSettingsViewModel(remote, "save", local)
        advanceUntilIdle()
        vm.onAction(SubsequentSettingsAction.SpeechSpeedChanged(4))
        vm.onAction(SubsequentSettingsAction.ReminderTimeChanged(LocalTime.of(21, 0)))
        vm.onAction(SubsequentSettingsAction.SaveRequested); advanceUntilIdle()
        vm.onAction(SubsequentSettingsAction.ReminderTimeChanged(LocalTime.of(22, 0)))
        vm.onAction(SubsequentSettingsAction.SaveRequested); advanceUntilIdle()
        assertEquals(2, remote.saved.size)
        assertEquals(remote.saved[0], remote.saved[1])
        assertEquals(remote.ids[0], remote.ids[1])
    }

    private class Remote(var current: InitialSettingsDraft) : SubsequentSettingsRepository {
        var loads = 0; var success = true
        val saved = mutableListOf<InitialSettingsDraft>(); val ids = mutableListOf<String>()
        override suspend fun load(): SubsequentSettingsLoadResult { loads++; return SubsequentSettingsLoadResult.Ready(current) }
        override suspend fun save(settings: InitialSettingsDraft, commandId: String): SubsequentSettingsSaveResult {
            saved += settings; ids += commandId
            if (!success) return SubsequentSettingsSaveResult.Failed
            current = settings; return SubsequentSettingsSaveResult.Saved
        }
    }
    private class Local(var current: ReminderSettingsDraft) : ReminderSettingsRepository {
        var saves = 0; var success = true; var loadFails = false; var gate: CompletableDeferred<Unit>? = null
        override suspend fun load(): ReminderSettingsDraft? = current.takeUnless { loadFails }
        override suspend fun save(expected: ReminderSettingsDraft, proposed: ReminderSettingsDraft): Boolean {
            saves++; gate?.await()
            if (!success || current != expected && current != proposed) return false
            current = proposed; return true
        }
    }
    private class EffectRemote : SettingsEffectRepository {
        val commands = mutableListOf<SettingsEffectCommand>()
        override suspend fun apply(command: SettingsEffectCommand): SettingsEffectApplyResult {
            commands += command; return SettingsEffectApplyResult.Applied
        }
    }
}
