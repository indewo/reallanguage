package com.reallanguage.app.feature.recording.data

import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.recording.domain.ActiveRecording
import com.reallanguage.app.feature.recording.domain.RecordingRuntimeState
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory ownership only: no Android service, microphone, files, or real session. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountRecordingRuntimeEpochTest {
    @Test fun sameAccountNewSessionHidesOriginalValueAndReplayCache() {
        val fixture = Fixture()
        val ownerEpoch = fixture.runtime.accountEpoch

        fixture.reauthenticate()

        assertTrue(fixture.runtime.accountEpoch > ownerEpoch)
        assertEquals(RecordingRuntimeState.Failed, fixture.oldState.value)
        assertEquals(listOf(RecordingRuntimeState.Failed), fixture.oldState.replayCache)
        assertEquals(fixture.active, fixture.source.value.state)
    }

    @Test fun sameAccountNewSessionInvalidatesCollectorsWithoutAnotherRecorderTick() = runTest {
        val fixture = Fixture()
        val observed = mutableListOf<RecordingRuntimeState>()
        val collector = launch { fixture.oldState.collect { observed += it } }
        try {
            runCurrent()
            assertEquals(listOf(fixture.active), observed)

            fixture.reauthenticate()
            runCurrent()

            assertEquals(fixture.active, fixture.source.value.state)
            assertEquals(listOf(fixture.active, RecordingRuntimeState.Failed), observed)
        } finally {
            // A failing RED assertion must not leave a collector waiting for a second value.
            collector.cancelAndJoin()
        }
    }

    @Test fun oldEntryCannotReadCompletionFromSameAccountNewSessionButFreshEntryCan() {
        val fixture = Fixture()
        fixture.reauthenticate()
        val completed = RecordingRuntimeState.Completed(fixture.recordingId, 8)
        fixture.source.value = fixture.source.value.copy(state = completed)
        val freshState = AccountRecordingRuntimeState(fixture.source, fixture.runtime)

        assertEquals(completed, freshState.value)
        assertEquals(RecordingRuntimeState.Failed, fixture.oldState.value)
        assertEquals(listOf(RecordingRuntimeState.Failed), fixture.oldState.replayCache)
    }

    @Test fun logoutThenSameAccountLoginCannotReviveOriginalObservation() {
        val fixture = Fixture()
        fixture.runtime.clear()
        assertEquals(RecordingRuntimeState.Failed, fixture.oldState.value)

        fixture.reauthenticate()

        assertEquals(fixture.active, AccountRecordingRuntimeState(fixture.source, fixture.runtime).value)
        assertEquals(RecordingRuntimeState.Failed, fixture.oldState.value)
    }

    @Test fun ordinaryTokenRefreshKeepsExistingObservationAndSubsequentRecorderUpdates() = runTest {
        val fixture = Fixture()
        val ownerEpoch = fixture.runtime.accountEpoch
        val observed = mutableListOf<RecordingRuntimeState>()
        val collector = launch { fixture.oldState.collect { observed += it } }
        try {
            runCurrent()
            fixture.runtime.update("isolated-refresh-only", fixture.accountId)
            runCurrent()

            assertEquals(ownerEpoch, fixture.runtime.accountEpoch)
            assertEquals(fixture.active, fixture.oldState.value)
            assertEquals(listOf(fixture.active), fixture.oldState.replayCache)
            assertEquals(listOf(fixture.active), observed)

            val nextTick = RecordingRuntimeState.Recording(ActiveRecording(fixture.recordingId, 4))
            fixture.source.value = fixture.source.value.copy(state = nextTick)
            runCurrent()
            val completed = RecordingRuntimeState.Completed(fixture.recordingId, 8)
            fixture.source.value = fixture.source.value.copy(state = completed)
            runCurrent()

            assertEquals(listOf(fixture.active, nextTick, completed), observed)
            assertEquals(completed, fixture.oldState.value)
            assertEquals(listOf(completed), fixture.oldState.replayCache)
        } finally {
            collector.cancelAndJoin()
        }
    }

    private class Fixture {
        val accountId = "recording-epoch-${UUID.randomUUID()}"
        val recordingId = UUID.randomUUID().toString()
        val runtime = SessionRuntime().apply { update("isolated-initial-only", this@Fixture.accountId) }
        val active = RecordingRuntimeState.Recording(ActiveRecording(recordingId, 3))
        val source = MutableStateFlow(OwnedRecordingRuntimeState(accountRecordingStorageKey(accountId), active))
        val oldState = AccountRecordingRuntimeState(source, runtime)

        fun reauthenticate() {
            runtime.update("isolated-new-session-only", accountId, newSession = true)
        }
    }
}
