package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.SemanticSplitResult
import com.reallanguage.app.feature.formation.domain.SemanticUnit
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SemanticSplitViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = ConfirmedSource("今天很忙。", SourceType.DIARY, SourceContentShape.ARTICLE)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun opensFocusSelectionOnlyAfterCompleteRepositoryResult() = runTest {
        val units = listOf(SemanticUnit("u1", 0, "今天很忙。"))
        val viewModel = SemanticSplitViewModel(source) { SemanticSplitResult.Ready(units) }
        val effect = async { viewModel.effects.first() }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(SemanticSplitEffect.OpenFocusSelection(units), effect.await())
    }

    @Test fun failedRemoteSplitIsCompletedLocallyWithoutAnErrorPage() = runTest {
        var calls = 0
        val viewModel = SemanticSplitViewModel(source) { calls += 1; SemanticSplitResult.Failed }
        val effect = async { viewModel.effects.first() }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.failed)
        assertEquals(
            SemanticSplitEffect.OpenFocusSelection(listOf(SemanticUnit("deterministic-1", 0, "今天很忙。"))),
            effect.await(),
        )
        assertEquals(1, calls)
    }

    @Test fun falselyCompleteCoarseResultIsRejectedAndSplitLocally() = runTest {
        val text = "今天不开心，因为手机没电了。然后我去充电。"
        val confirmed = source.copy(text = text)
        val coarse = listOf(SemanticUnit("stale", 0, text))
        val viewModel = SemanticSplitViewModel(confirmed) { SemanticSplitResult.Ready(coarse) }
        val effect = async { viewModel.effects.first() }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("今天不开心，", "因为手机没电了。", "然后我去充电。"),
            (effect.await() as SemanticSplitEffect.OpenFocusSelection).units.map { it.textZh },
        )
    }
}
