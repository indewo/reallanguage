package com.reallanguage.app.feature.home.presentation

import com.reallanguage.app.MainDispatcherRule
import com.reallanguage.app.feature.home.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesConcurrencyTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val message = AppMessage("m1", MessageKind.MANUAL_REVIEW_RESULT, "复核结果", "隔离测试内容", 10, false,
        AppMessageTarget("manual-review", "isolated-review"), MessageTargetState.AVAILABLE)

    @Test fun repeatedRetryWhileLoadingCannotStartCompetingLoads() = runTest {
        val response = CompletableDeferred<MessagesLoadResult>()
        var calls = 0
        val model = MessagesViewModel(repository(load = { calls++; response.await() }))
        runCurrent()
        repeat(3) { model.onAction(MessagesAction.RetryRequested) }
        runCurrent()
        try { assertEquals(1, calls) }
        finally { response.complete(MessagesLoadResult.Ready(listOf(message))) }
        advanceUntilIdle()
        assertEquals(listOf(message), model.uiState.value.messages)
    }

    @Test fun repeatedMarkAndRetryCannotRaceAnUnreadLoadAgainstTheAcceptedReadState() = runTest {
        val response = CompletableDeferred<Boolean>()
        var loads = 0
        var marks = 0
        val model = MessagesViewModel(repository(load = { loads++; MessagesLoadResult.Ready(listOf(message)) },
            mark = { marks++; response.await() }))
        advanceUntilIdle()
        model.onAction(MessagesAction.MarkAllReadRequested)
        runCurrent()
        model.onAction(MessagesAction.MarkAllReadRequested)
        model.onAction(MessagesAction.RetryRequested)
        runCurrent()
        try {
            assertEquals(1, marks)
            assertEquals(1, loads)
            assertTrue(model.uiState.value.markingAllRead)
        } finally { response.complete(true) }
        advanceUntilIdle()
        assertTrue(model.uiState.value.messages.single().read)
        assertFalse(model.uiState.value.markingAllRead)
    }

    @Test fun returnMakesALateLoadAndAnyFurtherNavigationUnableToChangeTheDepartedEntry() = runTest {
        val response = CompletableDeferred<MessagesLoadResult>()
        var calls = 0
        val model = MessagesViewModel(repository(load = { calls++; withContext(NonCancellable) { response.await() } }))
        val effects = mutableListOf<MessagesEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
        runCurrent()
        model.onAction(MessagesAction.ReturnHomeRequested)
        val departed = model.uiState.value
        model.onAction(MessagesAction.ReturnHomeRequested)
        model.onAction(MessagesAction.RetryRequested)
        response.complete(MessagesLoadResult.Ready(listOf(message)))
        advanceUntilIdle()
        model.onAction(MessagesAction.MessageRequested(message.messageId))
        assertEquals(listOf(MessagesEffect.OpenHome), effects)
        assertEquals(departed, model.uiState.value)
        assertEquals(1, calls)
    }

    @Test fun returnMakesALateMarkReadUnableToChangeTheEntryOrNavigateFromItAgain() = runTest {
        val response = CompletableDeferred<Boolean>()
        val model = MessagesViewModel(repository(mark = { withContext(NonCancellable) { response.await() } }))
        val effects = mutableListOf<MessagesEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
        advanceUntilIdle()
        model.onAction(MessagesAction.MarkAllReadRequested)
        runCurrent()
        model.onAction(MessagesAction.ReturnHomeRequested)
        val departed = model.uiState.value
        response.complete(true)
        advanceUntilIdle()
        model.onAction(MessagesAction.MessageRequested(message.messageId))
        assertEquals(departed, model.uiState.value)
        assertEquals(listOf(MessagesEffect.OpenHome), effects)
    }

    @Test fun rejectedMarkReadRetainsUnreadMessagesAndExposesFailureUntilASuccessfulRetry() = runTest {
        var accepted = false
        val model = MessagesViewModel(repository(mark = { accepted }))
        advanceUntilIdle()
        model.onAction(MessagesAction.MarkAllReadRequested)
        advanceUntilIdle()
        assertTrue(model.uiState.value.markAllReadFailed)
        assertFalse(model.uiState.value.markingAllRead)
        assertEquals(listOf(message), model.uiState.value.messages)
        assertFalse(model.uiState.value.loadFailed)
        accepted = true
        model.onAction(MessagesAction.MarkAllReadRequested)
        advanceUntilIdle()
        assertFalse(model.uiState.value.markAllReadFailed)
        assertTrue(model.uiState.value.messages.single().read)
    }

    @Test fun markReadExceptionIsVisibleWithoutLosingTheLoadedMessages() = runTest {
        val model = MessagesViewModel(repository(mark = { error("isolated mark-read failure") }))
        advanceUntilIdle()
        model.onAction(MessagesAction.MarkAllReadRequested)
        advanceUntilIdle()
        assertTrue(model.uiState.value.markAllReadFailed)
        assertFalse(model.uiState.value.markingAllRead)
        assertEquals(listOf(message), model.uiState.value.messages)
    }

    @Test fun returnCancelsBothKindOfRequestsWithoutTurningCancellationIntoAReadFailure() = runTest {
        var cancelledLoad = 0
        val loading = MessagesViewModel(repository(load = {
            try { awaitCancellation() } finally { cancelledLoad++ }
        }))
        runCurrent()
        loading.onAction(MessagesAction.ReturnHomeRequested)
        runCurrent()
        assertEquals(1, cancelledLoad)
        assertFalse(loading.uiState.value.loadFailed)
        var cancelledMark = 0
        val marking = MessagesViewModel(repository(mark = {
            try { awaitCancellation() } finally { cancelledMark++ }
        }))
        advanceUntilIdle()
        marking.onAction(MessagesAction.MarkAllReadRequested)
        runCurrent()
        marking.onAction(MessagesAction.ReturnHomeRequested)
        runCurrent()
        assertEquals(1, cancelledMark)
        assertFalse(marking.uiState.value.markAllReadFailed)
        assertFalse(marking.uiState.value.messages.single().read)
    }

    @Test fun anAvailableTargetCanLeaveOnlyOnceEvenWhenTheRowAndReturnAreClickedAgain() = runTest {
        val model = MessagesViewModel(repository())
        val effects = mutableListOf<MessagesEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.toList(effects) }
        advanceUntilIdle()
        model.onAction(MessagesAction.MessageRequested(message.messageId))
        model.onAction(MessagesAction.MessageRequested(message.messageId))
        model.onAction(MessagesAction.ReturnHomeRequested)
        assertEquals(listOf(MessagesEffect.OpenTarget(requireNotNull(message.target))), effects)
    }

    private fun repository(
        load: suspend () -> MessagesLoadResult = { MessagesLoadResult.Ready(listOf(message)) },
        mark: suspend () -> Boolean = { true },
    ) = object : MessagesRepository {
        override suspend fun load() = load.invoke()
        override suspend fun markAllRead() = mark.invoke()
    }
}
