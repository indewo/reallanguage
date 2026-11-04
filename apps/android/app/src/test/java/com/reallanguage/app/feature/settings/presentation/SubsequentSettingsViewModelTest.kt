package com.reallanguage.app.feature.settings.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.PurposeVocabulary
import com.reallanguage.app.feature.settings.domain.SubsequentSettingsLoadResult
import com.reallanguage.app.feature.settings.domain.SubsequentSettingsRepository
import com.reallanguage.app.feature.settings.domain.SubsequentSettingsSaveResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubsequentSettingsViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun loadsCurrentEffectiveSettingsAsEditingStart() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(current, viewModel.uiState.value.original)
        assertEquals(current, viewModel.uiState.value.draft)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test fun returnDiscardsChangesWithoutSaving() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SubsequentSettingsAction.SpeechSpeedChanged(5))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SubsequentSettingsAction.ReturnRequested)
        assertEquals(SubsequentSettingsEffect.OpenHome, effect.await())
        assertTrue(repository.saved.isEmpty())
    }

    @Test fun generationChangeOpensEffectScopeWithoutSaving() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SubsequentSettingsAction.BasicVocabularyChanged(BasicVocabulary.TEM_8))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SubsequentSettingsAction.SaveRequested)
        advanceUntilIdle()
        assertEquals(
            SubsequentSettingsEffect.OpenEffectScope(current, current.copy(basicVocabulary = BasicVocabulary.TEM_8)),
            effect.await(),
        )
        assertTrue(repository.saved.isEmpty())
    }

    @Test fun immediateOnlyChangeSavesAndReturnsHome() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SubsequentSettingsAction.SpeechSpeedChanged(5))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SubsequentSettingsAction.SaveRequested)
        advanceUntilIdle()
        assertEquals(listOf(current.copy(speechSpeedLevel = 5)), repository.saved)
        assertEquals("save-command", repository.commandIds.single())
        assertEquals(SubsequentSettingsEffect.OpenHome, effect.await())
    }

    @Test fun failedLoadAndSaveStayRetryableWithoutReplacingOriginal() = runTest {
        val repository = FakeRepository(loadResult = SubsequentSettingsLoadResult.Failed)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        assertEquals(SubsequentSettingsError.LOAD_FAILED, viewModel.uiState.value.error)
        repository.loadResult = SubsequentSettingsLoadResult.Ready(current)
        viewModel.onAction(SubsequentSettingsAction.RetryRequested)
        advanceUntilIdle()
        repository.saveResult = SubsequentSettingsSaveResult.Failed
        viewModel.onAction(SubsequentSettingsAction.SpeechSpeedChanged(4))
        viewModel.onAction(SubsequentSettingsAction.SaveRequested)
        advanceUntilIdle()
        assertEquals(SubsequentSettingsError.SAVE_FAILED, viewModel.uiState.value.error)
        assertEquals(current, viewModel.uiState.value.original)
        assertEquals(4, viewModel.uiState.value.draft?.speechSpeedLevel)
    }

    @Test fun everySettingsGroupEditsTheLoadedDraftWithoutPersistingEarly() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        viewModel.onAction(SubsequentSettingsAction.FormalityChanged(4))
        viewModel.onAction(SubsequentSettingsAction.SlangChanged(false))
        viewModel.onAction(SubsequentSettingsAction.PurposeVocabularyChanged(PurposeVocabulary.GRE, true))
        viewModel.onAction(SubsequentSettingsAction.StyleReferenceChanged("简洁直接"))
        viewModel.onAction(SubsequentSettingsAction.VoiceChanged(false))
        viewModel.onAction(SubsequentSettingsAction.AutoPlayChanged(true))
        val draft = viewModel.uiState.value.draft!!
        assertEquals(4, draft.formalityLevel)
        assertFalse(draft.slangEnabled!!)
        assertTrue(PurposeVocabulary.GRE in draft.purposeVocabularies)
        assertEquals("简洁直接", draft.styleReference)
        assertFalse(draft.voiceEnabled!!)
        assertFalse(draft.autoPlayEnabled!!)
        assertTrue(repository.saved.isEmpty())
    }

    private fun viewModel(repository: FakeRepository = FakeRepository()) = SubsequentSettingsViewModel(
        repository = repository,
        saveCommandId = "save-command",
    )

    private class FakeRepository(
        var loadResult: SubsequentSettingsLoadResult = SubsequentSettingsLoadResult.Ready(current),
        var saveResult: SubsequentSettingsSaveResult = SubsequentSettingsSaveResult.Saved,
    ) : SubsequentSettingsRepository {
        val saved = mutableListOf<InitialSettingsDraft>()
        val commandIds = mutableListOf<String>()
        override suspend fun load(): SubsequentSettingsLoadResult = loadResult
        override suspend fun save(settings: InitialSettingsDraft, commandId: String): SubsequentSettingsSaveResult {
            saved += settings
            commandIds += commandId
            return saveResult
        }
    }

    companion object {
        private val current = InitialSettingsDraft(
            formalityLevel = 3,
            slangEnabled = true,
            basicVocabulary = BasicVocabulary.CET_6,
            voiceEnabled = true,
            autoPlayEnabled = false,
            speechSpeedLevel = 3,
        )
    }
}
