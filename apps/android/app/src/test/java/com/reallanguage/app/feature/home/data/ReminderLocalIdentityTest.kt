package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.session.*
import com.reallanguage.app.feature.home.domain.*
import java.time.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReminderLocalIdentityTest {
    @Test fun `runtime identity captures only its account and rejects logout or account switch`() = runTest {
        val runtime = SessionRuntime().apply { update("isolated-access", "owner") }
        val identity = RuntimeReminderLocalIdentity(runtime)
        assertEquals("owner", identity.accountId)
        assertTrue(identity.isCurrent())
        runtime.update("refreshed-isolated-access", "owner")
        assertTrue(identity.isCurrent())
        runtime.update("other-isolated-access", "other")
        assertFalse(identity.isCurrent())
        runtime.clear()
        assertFalse(identity.isCurrent())
    }

    @Test fun `stored identity works after process recreation without runtime or an access token`() = runTest {
        val credentials = MemoryCredentials()
        val identity = requireNotNull(StoredSessionReminderLocalIdentity.load(credentials) { true })
        assertEquals("owner", identity.accountId)
        assertTrue(identity.isCurrent())
        assertEquals(0, credentials.saves)
        assertEquals(0, credentials.clears)
        assertTrue(credentials.reads >= 2)
    }

    @Test fun `persisted identity rechecks current account instead of retaining a signed in snapshot`() = runTest {
        val credentials = MemoryCredentials()
        val identity = requireNotNull(StoredSessionReminderLocalIdentity.load(credentials) { true })
        credentials.value = StoredSession("other", "other-isolated-refresh", true)
        assertFalse(identity.isCurrent())
        credentials.value = null
        assertFalse(identity.isCurrent())
        assertEquals(0, credentials.saves)
        assertEquals(0, credentials.clears)
    }

    @Test fun `locked user and failed credential reads never authorize local delivery or clear credentials`() = runTest {
        val credentials = MemoryCredentials()
        var unlocked = false
        assertNull(StoredSessionReminderLocalIdentity.load(credentials) { unlocked })
        assertEquals(0, credentials.reads)
        unlocked = true
        val identity = requireNotNull(StoredSessionReminderLocalIdentity.load(credentials) { unlocked })
        credentials.failure = IllegalStateException("isolated read failure")
        assertFalse(identity.isCurrent())
        assertNull(StoredSessionReminderLocalIdentity.load(credentials) { unlocked })
        unlocked = false
        val reads = credentials.reads
        assertFalse(identity.isCurrent())
        assertEquals(reads, credentials.reads)
        assertEquals(0, credentials.clears)
    }

    @Test fun `cancellation is propagated rather than converted into signed out`() = runTest {
        val credentials = MemoryCredentials().apply { failure = CancellationException("isolated cancellation") }
        try {
            StoredSessionReminderLocalIdentity.load(credentials) { true }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `recreated persistent identity can claim saved preferences without creating an access token`() = runTest {
        val values = mutableMapOf<String, String>()
        val store = PersistentDailyReminderPreferenceStore(object : ReminderStringPreferences {
            override fun read(key: String): String? = values[key]
            override fun write(key: String, value: String?): Boolean {
                if (value == null) values.remove(key) else values[key] = value
                return true
            }
        })
        val credentials = MemoryCredentials()
        val at = Instant.parse("2026-09-08T12:00:00Z")
        suspend fun repository() = DailyReminderRepository(store,
            requireNotNull(StoredSessionReminderLocalIdentity.load(credentials) { true }),
            Clock.fixed(at, ZoneOffset.UTC), { ZoneId.of("Asia/Shanghai") }, { ReminderPermission.GRANTED })
        val first = repository()
        assertEquals(DailyReminderIntroResult.Saved, first.recordChoice(DailyReminderChoice.ENABLED, "intro"))
        val token = (first.nextSchedule() as DailyReminderScheduleDecision.ScheduleFuture).token
        val reconstructed = repository()
        assertEquals(first.load(), reconstructed.load())
        assertEquals(ReminderDeliveryClaim.Reserved, reconstructed.claimScheduledDelivery(token))
        assertEquals(ReminderDeliveryClaim.AlreadyReserved, repository().claimScheduledDelivery(token))
        credentials.value = null
        assertEquals(ReminderDeliveryClaim.Rejected, reconstructed.claimScheduledDelivery(token))
        assertEquals(0, credentials.saves); assertEquals(0, credentials.clears)
    }

    private class MemoryCredentials : SessionCredentialStore {
        var value: StoredSession? = StoredSession("owner", "isolated-refresh", true)
        var reads = 0
        var saves = 0
        var clears = 0
        var failure: Exception? = null
        override suspend fun load(): StoredSession? { reads += 1; failure?.let { throw it }; return value }
        override suspend fun save(session: StoredSession) { saves += 1; value = session }
        override suspend fun clear() { clears += 1; value = null }
    }
}
