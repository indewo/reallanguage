package com.reallanguage.app.feature.settings.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.InitialSettingsDraft
import com.reallanguage.app.feature.settings.domain.SettingsEffectApplyResult
import com.reallanguage.app.feature.settings.domain.SettingsEffectCommand
import com.reallanguage.app.feature.settings.domain.SettingsEffectRepository
import com.reallanguage.app.feature.settings.domain.SettingsEffectScope
import com.reallanguage.app.feature.settings.domain.SettingsSourceContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsEffectScopeViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun reprocessUnfixedContentKeepsSourceAndRebuildsOnlyDownstream() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository, SettingsSourceContext.UnfixedContent("flow-1", "通勤录音"))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SettingsEffectScopeAction.ReprocessRequested)
        advanceUntilIdle()
        val command = repository.commands.single()
        assertEquals(SettingsEffectScope.ReprocessUnfixed("flow-1"), command.scope)
        assertEquals(proposed, command.proposedSettings)
        assertEquals(SettingsEffectScopeEffect.OpenReprocessing(command.scope), effect.await())
    }

    @Test fun reprocessFixedContentStartsNewFlowWithoutOverwritingOldFixedContent() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository, SettingsSourceContext.FixedContent("source-1", "昨晚的日记"))
        viewModel.onAction(SettingsEffectScopeAction.ReprocessRequested)
        advanceUntilIdle()
        assertEquals(SettingsEffectScope.StartNewFromFixedSource("source-1"), repository.commands.single().scope)
        assertTrue(repository.commands.single().preserveExistingFixedContent)
    }

    @Test fun nextContentAppliesSettingsAndReturnsHome() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository, SettingsSourceContext.NoCurrentContent)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SettingsEffectScopeAction.NextContentRequested)
        advanceUntilIdle()
        assertEquals(SettingsEffectScope.NextContent, repository.commands.single().scope)
        assertEquals(SettingsEffectScopeEffect.OpenHome, effect.await())
    }

    @Test fun cancelDiscardsModificationWithoutRepositoryWrite() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository, SettingsSourceContext.FixedContent("source-1", "日记"))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(SettingsEffectScopeAction.CancelRequested)
        assertEquals(SettingsEffectScopeEffect.ReturnToSettings, effect.await())
        assertTrue(repository.commands.isEmpty())
    }

    @Test fun failedApplicationStaysVisibleAndRetriesSameIdempotentCommand() = runTest {
        val repository = FakeRepository(SettingsEffectApplyResult.Failed)
        val viewModel = viewModel(repository, SettingsSourceContext.UnfixedContent("flow-1", "录音"))
        viewModel.onAction(SettingsEffectScopeAction.ReprocessRequested)
        advanceUntilIdle()
        assertEquals(SettingsEffectScopeError.APPLY_FAILED, viewModel.uiState.value.error)
        repository.result = SettingsEffectApplyResult.Applied
        viewModel.onAction(SettingsEffectScopeAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(2, repository.commands.size)
        assertEquals(repository.commands.first(), repository.commands.last())
    }

    @Test fun noCurrentContentCannotInventAReprocessingTarget() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository, SettingsSourceContext.NoCurrentContent)
        viewModel.onAction(SettingsEffectScopeAction.ReprocessRequested)
        advanceUntilIdle()
        assertTrue(repository.commands.isEmpty())
    }

    private fun viewModel(repository: FakeRepository, context: SettingsSourceContext) =
        SettingsEffectScopeViewModel(
            originalSettings = original,
            proposedSettings = proposed,
            sourceContext = context,
            repository = repository,
            commandId = "effect-command",
        )

    private class FakeRepository(
        var result: SettingsEffectApplyResult = SettingsEffectApplyResult.Applied,
    ) : SettingsEffectRepository {
        val commands = mutableListOf<SettingsEffectCommand>()
        override suspend fun apply(command: SettingsEffectCommand): SettingsEffectApplyResult {
            commands += command
            return result
        }
    }

    companion object {
        private val original = InitialSettingsDraft(formalityLevel = 3, basicVocabulary = BasicVocabulary.CET_6)
        private val proposed = original.copy(formalityLevel = 4)
    }
}
