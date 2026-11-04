package com.reallanguage.app.feature.safety.presentation

import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SafetyCheckRepository
import com.reallanguage.app.feature.safety.domain.SafetyCheckResult
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SafetyCheckViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val source = ConfirmedSource(
        text = "今天很开心。",
        sourceType = SourceType.DIARY,
        contentShape = SourceContentShape.ARTICLE,
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun checksImmediatelyWithoutExposingUnrelatedAccountData() = runTest(dispatcher) {
        val repository = RecordingRepository(SafetyCheckResult.Passed)
        SafetyCheckViewModel(source, repository)
        advanceUntilIdle()

        assertEquals(listOf(source), repository.requests)
    }

    @Test
    fun passOpensFullSentenceSplitWithConfirmedSource() = runTest(dispatcher) {
        val viewModel = SafetyCheckViewModel(
            source,
            RecordingRepository(SafetyCheckResult.Passed),
        )
        val effect = async { viewModel.effects.first() }
        advanceUntilIdle()

        assertEquals(SafetyCheckEffect.OpenFullSentenceSplit(source), effect.await())
    }

    @Test
    fun blockOpensSafetyHandlingForOnlyThisSafetyCase() = runTest(dispatcher) {
        val viewModel = SafetyCheckViewModel(
            source,
            RecordingRepository(SafetyCheckResult.Blocked("case-1")),
        )
        val effect = async { viewModel.effects.first() }
        advanceUntilIdle()

        assertEquals(
            SafetyCheckEffect.OpenSafetyHandling("case-1", source),
            effect.await(),
        )
    }

    @Test
    fun serviceFailureKeepsSourceAndAllowsOneRetryAtATime() = runTest(dispatcher) {
        val repository = RecordingRepository(SafetyCheckResult.Failed)
        val viewModel = SafetyCheckViewModel(source, repository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.failed)
        assertFalse(viewModel.uiState.value.processing)
        assertEquals(source, viewModel.uiState.value.source)

        viewModel.onAction(SafetyCheckAction.RetryRequested)
        viewModel.onAction(SafetyCheckAction.RetryRequested)
        advanceUntilIdle()
        assertEquals(2, repository.requests.size)
    }

    private class RecordingRepository(
        private val result: SafetyCheckResult,
    ) : SafetyCheckRepository {
        val requests = mutableListOf<ConfirmedSource>()

        override suspend fun check(source: ConfirmedSource): SafetyCheckResult {
            requests += source
            return result
        }
    }
}
