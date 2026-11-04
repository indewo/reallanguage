package com.reallanguage.app.feature.formation.presentation

import com.reallanguage.app.feature.formation.domain.ContentFixRepository
import com.reallanguage.app.feature.formation.domain.ContentFixRequest
import com.reallanguage.app.feature.formation.domain.ContentFixResult
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
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
class FinalFixViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val chapters = listOf(
        EnglishChapterDraft(
            "c1",
            listOf(
                EnglishSentenceDraft(
                    "u1", "我想表达得更清楚。", true,
                    listOf(EnglishCandidate("e1", "I want to express myself more clearly.")),
                ),
                EnglishSentenceDraft(
                    "u2", "这让我很开心。", false,
                    listOf(EnglishCandidate("e2", "That made me happy.")),
                ),
            ),
        ),
    )
    private val targets = listOf(
        LearningTarget("t1", "u1", "express oneself", "表达自己", LearningTargetType.PATTERN),
        LearningTarget("t2", "u1", "clearly", "清楚地", LearningTargetType.WORD),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun stateContainsOnlySelectedTargetsButKeepsEveryFixedSentence() {
        val state = createViewModel().uiState.value
        assertEquals(2, state.chapters.single().sentences.size)
        assertEquals(listOf("t1"), state.selectedTargets.map { it.targetId })
    }

    @Test
    fun confirmSubmitsOneIdempotentFixAndOpensFirstChapter() = runTest(dispatcher) {
        val requests = mutableListOf<ContentFixRequest>()
        val viewModel = createViewModel(
            repository = ContentFixRepository { request ->
                requests += request
                ContentFixResult.Fixed("c1")
            },
        )
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(FinalFixAction.ConfirmRequested)
        viewModel.onAction(FinalFixAction.ConfirmRequested)
        assertTrue(viewModel.uiState.value.submitting)
        advanceUntilIdle()
        assertEquals(1, requests.size)
        assertEquals("fix-command-1", requests.single().commandId)
        assertEquals(setOf("t1"), requests.single().selectedTargetIds)
        assertEquals(FinalFixEffect.OpenFirstChapter("c1"), effect.await())
    }

    @Test
    fun failedFixUnlocksButtonAndDoesNotCreateLearningNavigation() = runTest(dispatcher) {
        val viewModel = createViewModel(
            repository = ContentFixRepository { ContentFixResult.Failed },
        )
        viewModel.onAction(FinalFixAction.ConfirmRequested)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.fixFailed)
        assertFalse(viewModel.uiState.value.submitting)
        assertTrue(viewModel.uiState.value.confirmEnabled)
    }

    @Test fun thrownSaveFailureUnlocksRetryWithoutChangingTheCommandOrContent() = runTest(dispatcher) {
        val requests = mutableListOf<ContentFixRequest>()
        val model = createViewModel(ContentFixRepository {
            requests += it
            if (requests.size == 1) throw IllegalStateException("isolated storage failure")
            ContentFixResult.Fixed("c1")
        })
        model.onAction(FinalFixAction.ConfirmRequested)
        advanceUntilIdle()
        assertTrue(model.uiState.value.fixFailed)
        assertFalse(model.uiState.value.submitting)
        model.onAction(FinalFixAction.ConfirmRequested)
        val effect = async { model.effects.first() }
        advanceUntilIdle()
        assertEquals(requests.first(), requests.last())
        assertEquals(FinalFixEffect.OpenFirstChapter("c1"), effect.await())
    }

    @Test
    fun backReturnsToLearningTargetsWhenNoFixIsRunning() = runTest(dispatcher) {
        val viewModel = createViewModel()
        val effect = async { viewModel.effects.first() }

        viewModel.onAction(FinalFixAction.BackRequested)
        advanceUntilIdle()

        assertEquals(FinalFixEffect.ReturnToLearningTargets, effect.await())
    }

    private fun createViewModel(
        repository: ContentFixRepository = ContentFixRepository {
            ContentFixResult.Fixed("c1")
        },
    ) = FinalFixViewModel(
        formationFlowId = "flow-1",
        commandId = "fix-command-1",
        chapters = chapters,
        selectedCandidateIds = mapOf("u1" to "e1", "u2" to "e2"),
        targets = targets,
        selectedTargetIds = setOf("t1"),
        repository = repository,
    )
}
