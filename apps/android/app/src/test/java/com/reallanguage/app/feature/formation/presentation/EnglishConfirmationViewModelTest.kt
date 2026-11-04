package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
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
class EnglishConfirmationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chapters = listOf(
        EnglishChapterDraft(
            "c1",
            listOf(
                EnglishSentenceDraft(
                    "u1", "我今天很开心。", true,
                    listOf(EnglishCandidate("e1", "I was very happy today.")),
                ),
                EnglishSentenceDraft(
                    "u2", "我和朋友聊了很久。", false,
                    listOf(
                        EnglishCandidate("e2", "I talked with my friend for a long time."),
                        EnglishCandidate("e3", "My friend and I talked for a long time."),
                    ),
                ),
            ),
        ),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun uniqueCandidateIsAdoptedWithoutMeaninglessSelection() {
        val state = EnglishConfirmationViewModel(chapters).uiState.value
        assertEquals("e1", state.selectedCandidateIds["u1"])
        assertFalse(state.continueEnabled)
    }

    @Test
    fun allSentencesIncludingContextRequireAConfirmedExpression() {
        val viewModel = EnglishConfirmationViewModel(chapters)
        viewModel.onAction(EnglishConfirmationAction.CandidateSelected("u2", "e3"))
        assertEquals("e3", viewModel.uiState.value.selectedCandidateIds["u2"])
        assertTrue(viewModel.uiState.value.continueEnabled)
    }

    @Test
    fun invalidCandidateReferenceIsIgnored() {
        val viewModel = EnglishConfirmationViewModel(chapters)
        viewModel.onAction(EnglishConfirmationAction.CandidateSelected("u2", "missing"))
        assertFalse(viewModel.uiState.value.continueEnabled)
    }

    @Test
    fun continueEmitsConfirmedChoicesOnceAndPreventsDuplicates() = runTest(dispatcher) {
        val viewModel = EnglishConfirmationViewModel(chapters)
        viewModel.onAction(EnglishConfirmationAction.CandidateSelected("u2", "e2"))
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(EnglishConfirmationAction.ContinueRequested)
        viewModel.onAction(EnglishConfirmationAction.ContinueRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.submitting)
        assertEquals(
            EnglishConfirmationEffect.OpenLearningTargets(
                chapters,
                mapOf("u1" to "e1", "u2" to "e2"),
            ),
            effect.await(),
        )
    }
}
