package com.reallanguage.app.core.network

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

/** A synchronous, in-memory platform source; no Android registration, network or business calls. */
class NetworkAvailabilityWatchTest {
    @Test fun initiallyUnavailableStateIsReportedDuringSubscriptionWithoutWaitingForAnotherEvent() {
        val source = Source(initial = false)
        var unavailable = 0
        val watch = NetworkAvailabilityWatch(source) { unavailable++ }
        try {
            watch.start()
            assertEquals(1, source.subscriptions)
            assertEquals("The immediate current state must not be missed during registration", 1, unavailable)
        } finally { watch.close() }
    }

    @Test fun repeatedStartSubscribesOnlyOnceAndInitialAvailabilityDoesNotReportFailure() {
        val source = Source(initial = true)
        var unavailable = 0
        val watch = NetworkAvailabilityWatch(source) { unavailable++ }
        try {
            repeat(4) { watch.start() }
            assertEquals(1, source.subscriptions)
            assertEquals(0, unavailable)
            source.emit(false)
            assertEquals(1, unavailable)
            watch.start()
            assertEquals(1, source.subscriptions)
        } finally { watch.close() }
    }

    @Test fun repeatedUnavailableIsDeduplicatedAndAvailabilityOnlyRearmsTheNextLoss() {
        val source = Source(initial = false)
        var unavailable = 0
        val watch = NetworkAvailabilityWatch(source) { unavailable++ }
        try {
            watch.start()
            assertEquals(1, unavailable)
            repeat(3) { source.emit(false) }
            assertEquals(1, unavailable)
            repeat(3) { source.emit(true) }
            assertEquals("Available is not account verification or an automatic recovery action", 1, unavailable)
            source.emit(false)
            assertEquals(2, unavailable)
            source.emit(false)
            assertEquals(2, unavailable)
            source.emit(true)
            assertEquals(2, unavailable)
            source.emit(false)
            assertEquals(3, unavailable)
            assertEquals(1, source.subscriptions)
        } finally { watch.close() }
    }

    @Test fun closeReleasesOnceIgnoresLatePlatformCallbacksAndPreventsRestart() {
        val source = Source(initial = true)
        var unavailable = 0
        val watch = NetworkAvailabilityWatch(source) { unavailable++ }
        watch.start()
        repeat(3) { watch.close() }
        assertEquals(1, source.releases)
        // The platform may already have queued callbacks when its registration was released.
        source.emit(false)
        source.emit(true)
        source.emit(false)
        repeat(3) { watch.start() }
        assertEquals(0, unavailable)
        assertEquals(1, source.subscriptions)
        assertEquals(1, source.releases)
    }

    @Test fun closeBeforeStartNeverCreatesAPlatformSubscription() {
        val source = Source(initial = false)
        var unavailable = 0
        val watch = NetworkAvailabilityWatch(source) { unavailable++ }
        watch.close()
        watch.start()
        watch.close()
        assertEquals(0, source.subscriptions)
        assertEquals(0, source.releases)
        assertEquals(0, unavailable)
    }

    @Test fun registrationExceptionFailsClosedOnceWithoutThrowingOrImplicitlyRetrying() {
        val source = Source(initial = true).apply { failureBeforeCurrentState = IOException("isolated registration failure") }
        var unavailable = 0
        val watch = NetworkAvailabilityWatch(source) { unavailable++ }
        try {
            watch.start()
            assertEquals(1, unavailable)
            repeat(3) { watch.start() }
            assertEquals(1, source.subscriptions)
            assertEquals(1, unavailable)
        } finally { watch.close() }
        assertEquals("A failed subscription never returned a release handle", 0, source.releases)
    }

    @Test fun registrationFailureAfterImmediateUnavailableDoesNotReportTheSameLossTwice() {
        val source = Source(initial = false).apply { failureAfterCurrentState = IllegalStateException("isolated late registration failure") }
        var unavailable = 0
        val watch = NetworkAvailabilityWatch(source) { unavailable++ }
        try {
            watch.start()
            assertEquals(1, unavailable)
            watch.start()
            assertEquals(1, source.subscriptions)
            assertEquals(1, unavailable)
        } finally { watch.close() }
    }

    @Test fun closingFromTheImmediateCallbackStillReleasesTheHandleReturnedAfterThatCallback() {
        val source = Source(initial = false)
        var unavailable = 0
        lateinit var watch: NetworkAvailabilityWatch
        watch = NetworkAvailabilityWatch(source) {
            unavailable++
            // A synchronous initial loss can remove the owning composition before subscribe returns.
            watch.close()
        }
        watch.start()
        assertEquals(1, source.subscriptions)
        assertEquals(1, unavailable)
        assertEquals("The newly returned platform handle must not leak after reentrant close", 1, source.releases)
        watch.close()
        watch.start()
        source.emit(true)
        source.emit(false)
        assertEquals(1, source.subscriptions)
        assertEquals(1, source.releases)
        assertEquals(1, unavailable)
    }

    private class Source(private val initial: Boolean) : NetworkAvailabilitySource {
        var subscriptions = 0
        var releases = 0
        var failureBeforeCurrentState: Exception? = null
        var failureAfterCurrentState: Exception? = null
        private val listeners = mutableListOf<(Boolean) -> Unit>()
        override fun subscribe(listener: (Boolean) -> Unit): AutoCloseable {
            subscriptions++
            failureBeforeCurrentState?.let { throw it }
            listeners += listener
            listener(initial)
            failureAfterCurrentState?.let { throw it }
            // Deliberately not self-idempotent: releasing twice must be observable in the watch test.
            return AutoCloseable { releases++ }
        }
        fun emit(available: Boolean) { listeners.toList().forEach { it(available) } }
    }
}
