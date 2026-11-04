package com.reallanguage.app.feature.safety.presentation

import com.reallanguage.app.feature.safety.domain.SafetyHandlingDetailsResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SafetyHandlingLoaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()
    @Test fun opensHandlingOnlyAfterOwnedDetailsLoad() = runTest(dispatcher) {
        val details = SafetyHandlingViewModelTest.testDetails()
        val viewModel = SafetyHandlingLoaderViewModel("case-1") { SafetyHandlingDetailsResult.Ready(details) }
        val effect = async { viewModel.effects.first() }
        advanceUntilIdle()
        assertEquals(SafetyHandlingLoaderEffect.OpenHandling(details), effect.await())
    }
    @Test fun failureIsRetryable() = runTest(dispatcher) {
        var calls = 0
        val viewModel = SafetyHandlingLoaderViewModel("case-1") { calls += 1; SafetyHandlingDetailsResult.Failed }
        advanceUntilIdle(); assertTrue(viewModel.uiState.value.failed)
        viewModel.retry(); advanceUntilIdle(); assertEquals(2, calls)
    }
}
