package com.reallanguage.app.feature.settings.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReminderSettingsSaveCoordinatorTest {
    @Test fun reminderOnlyNeverCallsRemoteAndDuplicateSubmissionIsNoOp() = runTest {
        var local = 0
        val coordinator = SettingsSaveCoordinator(false, true, { error("No remote request permitted") }, { local++; true })
        assertTrue(coordinator.save().completed)
        assertTrue(coordinator.save().completed)
        assertEquals(1, local)
    }

    @Test fun mixedFailureReportsPartialAndRetriesOnlyUnacknowledgedSide() = runTest {
        var remote = 0; var local = 0
        val coordinator = SettingsSaveCoordinator(true, true, { remote++; true }, { ++local > 1 })
        assertEquals(SettingsSaveProgress(true, false), coordinator.save())
        assertTrue(coordinator.save().completed)
        assertEquals(1, remote); assertEquals(2, local)
    }

    @Test fun failedRemoteDoesNotCommitReminderAndThrowingLocalKeepsRemoteAcknowledgement() = runTest {
        var remoteSuccess = false; var local = 0
        val coordinator = SettingsSaveCoordinator(true, true, { remoteSuccess }, { local++; throw IllegalStateException() })
        assertEquals(SettingsSaveProgress(false, false), coordinator.save())
        assertEquals(0, local)
        remoteSuccess = true
        assertEquals(SettingsSaveProgress(true, false), coordinator.save())
        assertEquals(1, local)
    }

    @Test fun concurrentTapsAndCancellationDoNotResubmitAlreadyAcknowledgedRemote() = runTest {
        var remote = 0; var local = 0
        val gate = CompletableDeferred<Unit>()
        val coordinator = SettingsSaveCoordinator(true, true, { remote++; true }, { local++; gate.await(); true })
        val first = async { coordinator.save() }
        testScheduler.runCurrent()
        assertEquals(SettingsSaveProgress(true, false), coordinator.progress)
        first.cancel()
        try { first.await(); fail() } catch (_: CancellationException) { }
        val second = async { coordinator.save() }
        val third = async { coordinator.save() }
        gate.complete(Unit)
        assertTrue(second.await().completed); assertTrue(third.await().completed)
        assertEquals(1, remote); assertEquals(2, local)
    }

    @Test fun unchangedSideIsNotLabeledAsPartiallySaved() = runTest {
        val reminderOnly = SettingsSaveCoordinator(false, true, { error("No request") }, { false })
        assertFalse(reminderOnly.save().partiallySaved)
        val mixed = SettingsSaveCoordinator(true, true, { true }, { false })
        assertTrue(mixed.save().partiallySaved)
    }
}
