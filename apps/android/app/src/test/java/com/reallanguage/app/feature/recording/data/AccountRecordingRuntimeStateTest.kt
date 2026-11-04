package com.reallanguage.app.feature.recording.data

import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.ActiveRecording
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRecordingRuntimeStateTest {
    private val active = RecordingRuntimeState.Recording(ActiveRecording("isolated-recording", 3))

    @Test fun currentOwnerSeesOriginalRecordingAndOtherOwnerSeesNoIdentity() {
        val source = MutableStateFlow(OwnedRecordingRuntimeState(accountRecordingStorageKey("a"), active))
        val a = AccountRecordingRuntimeState(source, SessionRuntime().apply { update("isolated", "a") })
        val b = AccountRecordingRuntimeState(source, SessionRuntime().apply { update("isolated", "b") })
        assertEquals(active, a.value)
        assertEquals(RecordingRuntimeState.Failed, b.value)
        source.value = source.value.copy(state = RecordingRuntimeState.Completed("isolated-recording", 3))
        assertEquals(RecordingRuntimeState.Starting, b.value)
    }

    @Test fun logoutImmediatelyNotifiesCollectorsWithoutWaitingForTheRecorderTick() = runTest {
        val source = MutableStateFlow(OwnedRecordingRuntimeState(accountRecordingStorageKey("a"), active))
        val runtime = SessionRuntime().apply { update("isolated", "a") }
        val flow = AccountRecordingRuntimeState(source, runtime)
        val values = mutableListOf<RecordingRuntimeState>()
        val collection = launch { flow.take(2).toList(values) }
        runCurrent()
        runtime.clear()
        runCurrent()
        collection.join()
        assertEquals(listOf(active, RecordingRuntimeState.Failed), values)
    }

    @Test fun accountSwitchInvalidatesExistingCollectorAndCannotExposeNewOwnersCompletion() = runTest {
        val source = MutableStateFlow(OwnedRecordingRuntimeState(accountRecordingStorageKey("a"), active))
        val runtime = SessionRuntime().apply { update("isolated", "a") }
        val flow = AccountRecordingRuntimeState(source, runtime)
        val values = mutableListOf<RecordingRuntimeState>()
        val collection = launch { flow.take(2).toList(values) }
        runCurrent()
        runtime.update("other-isolated", "b")
        runCurrent()
        collection.join()
        source.value = OwnedRecordingRuntimeState(accountRecordingStorageKey("b"), RecordingRuntimeState.Completed("b-recording", 8))
        assertEquals(listOf(active, RecordingRuntimeState.Failed), values)
        assertEquals(RecordingRuntimeState.Failed, flow.value)
    }

    @Test fun unauthenticatedRepositoryCannotAcquireOwnershipLater() {
        val source = MutableStateFlow(OwnedRecordingRuntimeState(accountRecordingStorageKey("a"), active))
        val runtime = SessionRuntime()
        val flow = AccountRecordingRuntimeState(source, runtime)
        runtime.update("isolated", "a")
        assertEquals(RecordingRuntimeState.Failed, flow.value)
    }

    @Test fun tokenRefreshPreservesOwnership() {
        val source = MutableStateFlow(OwnedRecordingRuntimeState(accountRecordingStorageKey("a"), active))
        val runtime = SessionRuntime().apply { update("isolated", "a") }
        val flow = AccountRecordingRuntimeState(source, runtime)
        runtime.update("refreshed-isolated")
        assertEquals(active, flow.value)
    }
}
