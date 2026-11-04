package com.reallanguage.app.feature.error.presentation

import com.reallanguage.app.feature.error.domain.ConnectionRecoveryRepository
import com.reallanguage.app.feature.error.domain.ConnectionRecoveryResult
import com.reallanguage.app.feature.error.domain.ConnectionResumePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
class ConnectionErrorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun unfinishedInitialSettingsNavigateToSetupInsteadOfReturningToLoginOrRevealingTheOldPage() = runTest(dispatcher) {
        val repository = FakeRepository(ConnectionRecoveryResult.InitialSettingsRequired)
        val viewModel = ConnectionErrorViewModel(ConnectionResumePoint("billing", "position"), repository)
        val effects = mutableListOf<ConnectionErrorEffect>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            viewModel.effects.collect { effects += it }
        }
        viewModel.onAction(ConnectionErrorAction.ReconnectRequested)
        advanceUntilIdle()
        assertEquals(listOf(ConnectionErrorEffect.InitialSettingsRequired), effects)
        assertTrue(viewModel.uiState.value.overlayVisible)
        assertFalse(viewModel.uiState.value.reconnecting)
        viewModel.onAction(ConnectionErrorAction.ReconnectRequested)
        advanceUntilIdle()
        assertEquals(1, repository.checks)
    }

    @Test fun overlayKeepsExactPagePositionAndTemporaryInputWithoutMarkingSubmissionSuccessful() {
        val resumePoint = ConnectionResumePoint("diary-input", "field-2-cursor-18", "尚未提交的文字")
        val viewModel = ConnectionErrorViewModel(resumePoint, FakeRepository())
        assertEquals(resumePoint, viewModel.uiState.value.resumePoint)
        assertFalse(viewModel.uiState.value.operationSubmitted)
        assertTrue(viewModel.uiState.value.overlayVisible)
    }

    @Test fun reconnectRequiresNetworkAndAccountCheckThenRestoresExactPosition() = runTest(dispatcher) {
        val resumePoint = ConnectionResumePoint("learning-7", "step-3-line-2")
        val repository = FakeRepository(ConnectionRecoveryResult.Restored)
        val viewModel = ConnectionErrorViewModel(resumePoint, repository)
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ConnectionErrorAction.ReconnectRequested)
        advanceUntilIdle()
        assertEquals(1, repository.checks)
        assertEquals(ConnectionErrorEffect.Restore(resumePoint), effect.await())
    }

    @Test fun unavailableConnectionStaysBlockingAndAllowsRetry() = runTest(dispatcher) {
        val repository = FakeRepository(ConnectionRecoveryResult.Unavailable)
        val viewModel = ConnectionErrorViewModel(ConnectionResumePoint("home", "top"), repository)
        viewModel.onAction(ConnectionErrorAction.ReconnectRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.reconnectFailed)
        assertTrue(viewModel.uiState.value.overlayVisible)
        repository.result = ConnectionRecoveryResult.Restored
        viewModel.onAction(ConnectionErrorAction.ReconnectRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.reconnectFailed)
        assertEquals(2, repository.checks)
    }

    @Test fun returnDelegatesToCurrentFlowsExistingSaveOrAbandonRule() = runTest(dispatcher) {
        val resumePoint = ConnectionResumePoint("review-2", "chapter-start")
        val viewModel = ConnectionErrorViewModel(resumePoint, FakeRepository())
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(ConnectionErrorAction.ReturnRequested)
        advanceUntilIdle()
        assertEquals(ConnectionErrorEffect.ReturnUsingCurrentFlowRule("review-2"), effect.await())
    }

    private class FakeRepository(var result: ConnectionRecoveryResult = ConnectionRecoveryResult.Restored) : ConnectionRecoveryRepository {
        var checks = 0
        override suspend fun verifyNetworkAndAccount(): ConnectionRecoveryResult {
            checks += 1
            return result
        }
    }
}
