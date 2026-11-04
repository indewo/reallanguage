package com.reallanguage.app.feature.settings.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsCompletionResult
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.InitialSettingsRepository
import com.reallanguage.app.feature.settings.domain.PurposeVocabulary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InitialSettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `starts at step one with no invented defaults`() {
        val viewModel = viewModel()

        assertEquals(InitialSettingsStep.EXPRESSION_BASICS, viewModel.uiState.value.step)
        assertEquals(InitialSettingsDraft(), viewModel.uiState.value.draft)
        assertFalse(viewModel.uiState.value.continueEnabled)
    }

    @Test
    fun `step one requires explicit confirmed selections`() {
        val viewModel = viewModel()

        viewModel.onAction(InitialSettingsAction.FormalityChanged(3))
        viewModel.onAction(InitialSettingsAction.SlangChanged(true))
        assertFalse(viewModel.uiState.value.continueEnabled)

        viewModel.onAction(
            InitialSettingsAction.BasicVocabularyChanged(BasicVocabulary.CET_6),
        )

        assertTrue(viewModel.uiState.value.continueEnabled)
    }

    @Test
    fun `back navigation preserves all values in the current process`() {
        val viewModel = viewModel()
        fillStepOne(viewModel)
        viewModel.onAction(InitialSettingsAction.ContinueRequested)
        viewModel.onAction(
            InitialSettingsAction.PurposeVocabularyChanged(
                PurposeVocabulary.BUSINESS_ENGLISH,
                selected = true,
            ),
        )
        viewModel.onAction(InitialSettingsAction.StyleReferenceChanged("Clear and direct"))
        viewModel.onAction(InitialSettingsAction.ContinueRequested)

        viewModel.onAction(InitialSettingsAction.BackRequested)
        viewModel.onAction(InitialSettingsAction.BackRequested)

        assertEquals(InitialSettingsStep.EXPRESSION_BASICS, viewModel.uiState.value.step)
        assertEquals(3, viewModel.uiState.value.draft.formalityLevel)
        assertEquals(BasicVocabulary.CET_6, viewModel.uiState.value.draft.basicVocabulary)
        assertEquals(
            setOf(PurposeVocabulary.BUSINESS_ENGLISH),
            viewModel.uiState.value.draft.purposeVocabularies,
        )
        assertEquals("Clear and direct", viewModel.uiState.value.draft.styleReference)
    }

    @Test
    fun `does not persist any partial step`() = runTest {
        val repository = FakeInitialSettingsRepository()
        val viewModel = viewModel(repository)
        fillStepOne(viewModel)
        viewModel.onAction(InitialSettingsAction.ContinueRequested)
        viewModel.onAction(InitialSettingsAction.ContinueRequested)
        advanceUntilIdle()

        assertTrue(repository.savedDrafts.isEmpty())
    }

    @Test
    fun `voice off keeps auto play ineffective`() {
        val viewModel = viewModel()
        viewModel.onAction(InitialSettingsAction.VoiceChanged(false))
        viewModel.onAction(InitialSettingsAction.AutoPlayChanged(true))

        assertFalse(viewModel.uiState.value.effectiveAutoPlay)
    }

    @Test
    fun `complete saves the entire draft once and opens home`() = runTest {
        val repository = FakeInitialSettingsRepository()
        val viewModel = viewModel(repository)
        fillAllSteps(viewModel)
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(InitialSettingsAction.CompleteRequested)
        advanceUntilIdle()

        assertEquals(1, repository.savedDrafts.size)
        assertEquals(4, repository.savedDrafts.single().speechSpeedLevel)
        assertEquals(InitialSettingsEffect.OpenHome, effect.await())
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `submitting prevents duplicate completion`() = runTest {
        val pending = CompletableDeferred<InitialSettingsCompletionResult>()
        val repository = FakeInitialSettingsRepository(pending = pending)
        val viewModel = viewModel(repository)
        fillAllSteps(viewModel)

        viewModel.onAction(InitialSettingsAction.CompleteRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.submitting)
        viewModel.onAction(InitialSettingsAction.CompleteRequested)
        advanceUntilIdle()
        assertEquals(1, repository.savedDrafts.size)

        pending.complete(InitialSettingsCompletionResult.Completed)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `failed completion preserves draft and exposes retryable error`() = runTest {
        val repository = FakeInitialSettingsRepository(
            result = InitialSettingsCompletionResult.Failed(retryable = true),
        )
        val viewModel = viewModel(repository)
        fillAllSteps(viewModel)

        viewModel.onAction(InitialSettingsAction.CompleteRequested)
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.draft.formalityLevel)
        assertEquals(InitialSettingsError.SAVE_FAILED, viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.continueEnabled)
        assertFalse(viewModel.uiState.value.submitting)
    }

    @Test
    fun `a new setup session does not restore an unfinished draft`() {
        val first = viewModel()
        fillStepOne(first)
        val restarted = viewModel()

        assertEquals(InitialSettingsDraft(), restarted.uiState.value.draft)
        assertNull(restarted.uiState.value.draft.formalityLevel)
    }

    private fun viewModel(
        repository: InitialSettingsRepository = FakeInitialSettingsRepository(),
    ) = InitialSettingsViewModel(
        validator = ConfirmedInitialSettingsValidator,
        repository = repository,
    )

    private fun fillStepOne(viewModel: InitialSettingsViewModel) {
        viewModel.onAction(InitialSettingsAction.FormalityChanged(3))
        viewModel.onAction(InitialSettingsAction.SlangChanged(true))
        viewModel.onAction(
            InitialSettingsAction.BasicVocabularyChanged(BasicVocabulary.CET_6),
        )
    }

    private fun fillAllSteps(viewModel: InitialSettingsViewModel) {
        fillStepOne(viewModel)
        viewModel.onAction(InitialSettingsAction.ContinueRequested)
        viewModel.onAction(InitialSettingsAction.ContinueRequested)
        viewModel.onAction(InitialSettingsAction.VoiceChanged(true))
        viewModel.onAction(InitialSettingsAction.AutoPlayChanged(true))
        viewModel.onAction(InitialSettingsAction.SpeechSpeedChanged(4))
    }
}

private class FakeInitialSettingsRepository(
    private val result: InitialSettingsCompletionResult =
        InitialSettingsCompletionResult.Completed,
    private val pending: CompletableDeferred<InitialSettingsCompletionResult>? = null,
) : InitialSettingsRepository {
    val savedDrafts = mutableListOf<InitialSettingsDraft>()

    override suspend fun complete(
        settings: InitialSettingsDraft,
    ): InitialSettingsCompletionResult {
        savedDrafts += settings
        return pending?.await() ?: result
    }
}
