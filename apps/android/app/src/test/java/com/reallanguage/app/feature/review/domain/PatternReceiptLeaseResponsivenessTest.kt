package com.reallanguage.app.feature.review.domain

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PatternReceiptLeaseResponsivenessTest {
    @Test(timeout = 10_000)
    fun revocationNeverWaitsForTheDatabaseGateAndInvalidatesDelayedProof() {
        val lease = ReviewPatternEntryLease("account", 1, "review", "entry")
        val receipt = PatternIndependentReceipt(1,
            PatternIndependentReceiptIdentity("account", 1, "review", "entry", "chapter", "sentence", "ability", "target"),
            "example", "command", "a".repeat(64), "b".repeat(64), PatternIndependentDecision.PASS, true, true, true, .9)
        assertTrue(lease.rememberRegistered(receipt))
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val revokeStarted = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val afterRelease = AtomicReference<Pair<Boolean, Boolean>?>()
        val workerError = AtomicReference<Throwable?>()
        val databaseWorker = Thread({
            try {
                lease.serialized {
                    held.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    afterRelease.set(lease.rememberRegistered(receipt) to lease.containsRegistered(receipt))
                }
            } catch (failure: Throwable) { workerError.set(failure) }
        }, "isolated-review-database-gate")
        val lifecycle = Thread({
            revokeStarted.countDown()
            lease.revoke()
            revoked.countDown()
        }, "isolated-review-lifecycle")
        var returnedBeforeDatabaseRelease = false
        var inactiveBeforeDatabaseRelease = false
        try {
            databaseWorker.start()
            assertTrue(held.await(2, TimeUnit.SECONDS))
            lifecycle.start()
            assertTrue(revokeStarted.await(2, TimeUnit.SECONDS))
            // Synchronization assertion, not a database performance benchmark:
            // the gate remains deliberately held throughout this interval.
            returnedBeforeDatabaseRelease = revoked.await(300, TimeUnit.MILLISECONDS)
            inactiveBeforeDatabaseRelease = !lease.isActiveFor("account", 1)
        } finally {
            release.countDown()
            databaseWorker.join(2_000)
            if (lifecycle.state != Thread.State.NEW) lifecycle.join(2_000)
        }
        assertNull(workerError.get())
        assertTrue("Leaving the page must not wait behind database work", returnedBeforeDatabaseRelease)
        assertTrue("Revocation must be visible before the blocked work resumes", inactiveBeforeDatabaseRelease)
        assertEquals(false to false, afterRelease.get())
        assertFalse(lease.isActiveFor("account", 1))
    }
}
