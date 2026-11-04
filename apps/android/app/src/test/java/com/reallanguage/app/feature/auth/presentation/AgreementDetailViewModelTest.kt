package com.reallanguage.app.feature.auth.presentation

import com.reallanguage.app.feature.auth.domain.AgreementDocument
import com.reallanguage.app.feature.auth.domain.AgreementDocumentRepository
import com.reallanguage.app.feature.auth.domain.AgreementDocumentResult
import com.reallanguage.app.feature.auth.domain.AgreementKind
import com.reallanguage.app.feature.auth.domain.RegistrationDraftSnapshot
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
class AgreementDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsRequestedVersionedAgreementFromAuthoritativeRepository() = runTest(dispatcher) {
        val repository = FakeRepository(AgreementDocumentResult.Ready(privacyDocument))
        val viewModel = AgreementDetailViewModel(AgreementKind.PRIVACY_POLICY, draft, repository)
        advanceUntilIdle()
        assertEquals(AgreementKind.PRIVACY_POLICY, repository.requestedKind)
        assertEquals("privacy-v3", viewModel.uiState.value.document?.version)
        assertEquals(listOf("第一节", "第二节"), viewModel.uiState.value.document?.sections)
    }

    @Test fun returningToRegistrationPreservesEveryFieldAndAcceptanceState() = runTest(dispatcher) {
        val viewModel = AgreementDetailViewModel(AgreementKind.USER_AGREEMENT, draft, FakeRepository())
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(AgreementDetailAction.ReturnRequested)
        advanceUntilIdle()
        assertEquals(AgreementDetailEffect.ReturnToRegistration(draft), effect.await())
    }

    @Test fun failedLoadCanRetryWithoutReplacingDocumentWithInventedCopy() = runTest(dispatcher) {
        val repository = FakeRepository(AgreementDocumentResult.Failed)
        val viewModel = AgreementDetailViewModel(AgreementKind.USER_AGREEMENT, draft, repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)
        assertEquals(null, viewModel.uiState.value.document)
        repository.result = AgreementDocumentResult.Ready(userDocument)
        viewModel.onAction(AgreementDetailAction.RetryRequested)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loadFailed)
        assertEquals("terms-v2", viewModel.uiState.value.document?.version)
    }

    private class FakeRepository(
        var result: AgreementDocumentResult = AgreementDocumentResult.Ready(userDocument),
    ) : AgreementDocumentRepository {
        var requestedKind: AgreementKind? = null
        override suspend fun load(kind: AgreementKind): AgreementDocumentResult {
            requestedKind = kind
            return result
        }
    }

    companion object {
        private val draft = RegistrationDraftSnapshot(
            email = "learner@example.com",
            password = "secret-value",
            birthYearMonth = "2000-08",
            acceptedTerms = true,
            acceptedPrivacyPolicy = false,
        )
        private val privacyDocument = AgreementDocument(AgreementKind.PRIVACY_POLICY, "隐私政策", "privacy-v3", listOf("第一节", "第二节"))
        private val userDocument = AgreementDocument(AgreementKind.USER_AGREEMENT, "用户协议", "terms-v2", listOf("条款正文"))
    }
}
