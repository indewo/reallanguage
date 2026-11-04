package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.home.domain.AppMessage
import com.reallanguage.app.feature.home.domain.AppMessageTarget
import com.reallanguage.app.feature.home.domain.MessageKind
import com.reallanguage.app.feature.home.domain.MessageTargetState
import com.reallanguage.app.feature.home.domain.MessagesLoadResult
import com.reallanguage.app.feature.home.domain.MessagesRepository
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
class MessagesViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test fun messagesAreSortedNewestFirstAndReviewResultIdentifiesContent() = runTest {
        val viewModel = viewModel(FakeRepository(MessagesLoadResult.Ready(listOf(older, review))))
        advanceUntilIdle()
        assertEquals(listOf(review, older), viewModel.uiState.value.messages)
        assertEquals("昨晚的日记", viewModel.uiState.value.messages.first().relatedContentLabelZh)
    }

    @Test fun availableTargetOpensExactDestination() = runTest {
        val viewModel = viewModel(FakeRepository(MessagesLoadResult.Ready(listOf(review))))
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(MessagesAction.MessageRequested("m2"))
        assertEquals(MessagesEffect.OpenTarget(review.target!!), effect.await())
    }

    @Test fun expiredSessionOpensLoginWhilePreservingTarget() = runTest {
        val pending = review.copy(targetState = MessageTargetState.REQUIRES_LOGIN)
        val viewModel = viewModel(FakeRepository(MessagesLoadResult.Ready(listOf(pending))))
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(MessagesAction.MessageRequested("m2"))
        assertEquals(MessagesEffect.OpenLoginForTarget(review.target!!), effect.await())
    }

    @Test fun unavailableAndUnknownTargetsStayOnMessagesPage() = runTest {
        val unavailable = review.copy(targetState = MessageTargetState.UNAVAILABLE)
        val viewModel = viewModel(FakeRepository(MessagesLoadResult.Ready(listOf(unavailable))))
        advanceUntilIdle()
        viewModel.onAction(MessagesAction.MessageRequested("m2"))
        assertEquals("m2", viewModel.uiState.value.unavailableMessageId)
        viewModel.onAction(MessagesAction.MessageRequested("missing"))
        assertEquals("m2", viewModel.uiState.value.unavailableMessageId)
    }

    @Test fun markAllReadUpdatesEveryVisibleMessage() = runTest {
        val viewModel = viewModel(FakeRepository(MessagesLoadResult.Ready(listOf(older, review))))
        advanceUntilIdle()

        viewModel.onAction(MessagesAction.MarkAllReadRequested)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.messages.all { it.read })
    }

    @Test fun loadFailureCanRetryAndReturnOpensHome() = runTest {
        val repository = FakeRepository(MessagesLoadResult.Failed)
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)
        repository.result = MessagesLoadResult.Ready(emptyList())
        viewModel.onAction(MessagesAction.RetryRequested)
        advanceUntilIdle()
        val effect = async { viewModel.effects.first() }
        viewModel.onAction(MessagesAction.ReturnHomeRequested)
        assertEquals(MessagesEffect.OpenHome, effect.await())
    }

    private fun viewModel(repository: FakeRepository) = MessagesViewModel(repository)
    private class FakeRepository(var result: MessagesLoadResult) : MessagesRepository {
        override suspend fun load(): MessagesLoadResult = result
        override suspend fun markAllRead(): Boolean = true
    }

    companion object {
        private val older = AppMessage("m1", MessageKind.SYSTEM, "学习提醒", null, 100, false, null, MessageTargetState.UNAVAILABLE)
        private val review = AppMessage(
            "m2", MessageKind.MANUAL_REVIEW_RESULT, "人工复核已完成", "昨晚的日记", 200, true,
            AppMessageTarget("manual-review", "review-2"), MessageTargetState.AVAILABLE,
        )
    }
}
