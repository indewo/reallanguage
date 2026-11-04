package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.domain.*
import java.time.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DailyReminderDeliveryPreferencesTest {
    private val storage = DeliveryPreferenceMemory()
    private val runtime = SessionRuntime().apply { update("isolated-token", "owner") }
    private var zone = ZoneId.of("Asia/Shanghai")
    private var permission = ReminderPermission.GRANTED
    private var now = Instant.parse("2026-09-08T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }
    private fun store() = PersistentDailyReminderPreferenceStore(storage)
    private fun repository(session: SessionRuntime = runtime) = DailyReminderRepository(store(), session, clock, { zone }, { permission })

    private suspend fun scheduled(): DailyReminderScheduleToken {
        val repository = repository()
        assertEquals(DailyReminderIntroResult.Saved, repository.recordChoice(DailyReminderChoice.ENABLED, "intro"))
        val preferences: DailyReminderDeliveryPreferences = repository
        return (preferences.nextSchedule() as DailyReminderScheduleDecision.ScheduleFuture).token
    }
    private suspend fun reserved(): DailyReminderScheduleToken = scheduled().also {
        now = it.scheduledAt
        val preferences: DailyReminderDeliveryPreferences = repository()
        assertEquals(ReminderDeliveryClaim.Reserved, preferences.claim(it))
    }

    @Test fun `delivery interface delegates durable claim and recreated store verifies without writing`() = runTest {
        val token = reserved()
        val current = requireNotNull(repository().load())
        assertEquals(token.revision + 1, current.revision)
        assertEquals(setOf(token.localDate), current.claimedLocalDates)
        val saved = storage.values.toMap()
        val writes = storage.writes
        val recreated: DailyReminderDeliveryPreferences = repository()
        assertTrue(recreated.mayPostReserved(token))
        assertTrue(repository().mayPostReserved(token))
        assertEquals(saved, storage.values)
        assertEquals(writes, storage.writes)
        assertEquals(ReminderDeliveryClaim.AlreadyReserved, recreated.claim(token))
        assertEquals(writes, storage.writes)
    }

    @Test fun `unclaimed or missing todays durable claim is never posting authority`() = runTest {
        val token = scheduled()
        assertFalse(repository().mayPostReserved(token))
        now = token.scheduledAt
        assertFalse(repository().mayPostReserved(token))
        assertEquals(ReminderDeliveryClaim.Reserved, repository().claim(token))
        assertTrue(repository().mayPostReserved(token))
        replace { it.copy(claimedLocalDates = emptySet()) }
        assertFalse(repository().mayPostReserved(token))
    }

    @Test fun `disabling then reenabling cannot revive a previously reserved token`() = runTest {
        val token = reserved()
        val repository = repository()
        assertTrue(repository.setEnabled(false).saved)
        assertFalse(repository.mayPostReserved(token))
        assertTrue(repository.setEnabled(true).saved)
        assertTrue(requireNotNull(repository.load()).enabled)
        assertFalse(repository.mayPostReserved(token))
        assertEquals(ReminderDeliveryClaim.AlreadyReserved, repository.claim(token))
    }

    @Test fun `changing time then restoring it still invalidates the old claim revision`() = runTest {
        val token = reserved()
        val repository = repository()
        assertTrue(repository.setTime(LocalTime.of(21, 0)).saved)
        assertFalse(repository.mayPostReserved(token))
        assertTrue(repository.setTime(LocalTime.of(20, 0)).saved)
        assertFalse(repository.mayPostReserved(token))
        assertEquals(token.revision + 3, requireNotNull(repository.load()).revision)
    }

    @Test fun `live permission denial or unknown blocks despite a persisted grant`() = runTest {
        val token = reserved()
        assertEquals(ReminderPermission.GRANTED, requireNotNull(repository().load()).permission)
        permission = ReminderPermission.DENIED
        assertFalse(repository().mayPostReserved(token))
        permission = ReminderPermission.UNKNOWN
        assertFalse(repository().mayPostReserved(token))
        permission = ReminderPermission.GRANTED
        assertTrue(repository().mayPostReserved(token))
    }

    @Test fun `different owner logout or account invalidation during read rejects the reserved token`() = runTest {
        val token = reserved()
        val previous = repository()
        val other = SessionRuntime().apply { update("other-token", "other-owner") }
        assertFalse(repository(other).mayPostReserved(token))
        runtime.update("other-token", "other-owner")
        assertFalse(previous.mayPostReserved(token))
        runtime.clear()
        assertFalse(previous.mayPostReserved(token))
        runtime.update("isolated-token", "owner")
        assertTrue(repository().mayPostReserved(token))
        val reading = repository()
        storage.afterRead = { runtime.clear() }
        assertFalse(reading.mayPostReserved(token))
    }

    @Test fun `logout cancellation persists an invalidating revision even after same owner signs in`() = runTest {
        val token = reserved()
        val old = repository()
        runtime.clear()
        assertTrue(old.cancelForLogout().saved)
        runtime.update("new-isolated-token", "owner")
        assertFalse(repository().mayPostReserved(token))
        assertEquals(setOf(token.localDate), requireNotNull(repository().load()).claimedLocalDates)
    }

    @Test fun `clock rollback next local day or another timezone cannot post old occurrence`() = runTest {
        val token = reserved()
        now = token.scheduledAt.minusSeconds(1)
        assertFalse(repository().mayPostReserved(token))
        now = token.scheduledAt
        assertTrue(repository().mayPostReserved(token))
        now = token.scheduledAt.plusSeconds(24 * 60 * 60)
        assertFalse(repository().mayPostReserved(token))
        now = token.scheduledAt
        zone = ZoneId.of("Asia/Hong_Kong")
        assertFalse(repository().mayPostReserved(token))
        zone = ZoneId.of("Asia/Shanghai")
        assertFalse(repository().mayPostReserved(token.copy(zoneId = "Asia/Hong_Kong")))
        assertFalse(repository().mayPostReserved(token.copy(localDate = token.localDate.minusDays(1))))
    }

    @Test fun `exact scheduled instant and exactly one revision increment are required`() = runTest {
        val token = reserved()
        assertFalse(repository().mayPostReserved(token.copy(scheduledAt = token.scheduledAt.minusSeconds(1))))
        assertFalse(repository().mayPostReserved(token.copy(revision = token.revision + 1)))
        assertFalse(repository().mayPostReserved(token.copy(revision = Long.MAX_VALUE)))
        replace { it.copy(revision = 0) }
        assertFalse(repository().mayPostReserved(token.copy(revision = -1)))
    }

    @Test fun `failed or corrupt reads fail closed and cannot mutate the claimed slot`() = runTest {
        val token = reserved()
        val saved = storage.values.toMap()
        val writes = storage.writes
        storage.readFailure = IllegalStateException("isolated unreadable preferences")
        assertFalse(repository().mayPostReserved(token))
        storage.readFailure = null
        assertEquals(saved, storage.values)
        assertEquals(writes, storage.writes)
        storage.values.replaceAll { _, _ -> "corrupt" }
        assertFalse(repository().mayPostReserved(token))
        assertEquals(writes, storage.writes)
    }

    @Test fun `storage and identity cancellation propagate instead of becoming rejected delivery`() = runTest {
        val token = reserved()
        storage.readFailure = CancellationException("isolated cancelled read")
        try {
            repository().mayPostReserved(token)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
        storage.readFailure = null
        val cancelledIdentity = object : ReminderLocalIdentity {
            override val accountId = "owner"
            override suspend fun isCurrent(): Boolean = throw CancellationException("isolated cancelled identity")
        }
        try {
            DailyReminderRepository(store(), cancelledIdentity, clock, { zone }, { permission }).mayPostReserved(token)
            fail("Identity cancellation must propagate")
        } catch (_: CancellationException) { /* expected */ }
    }

    @Test fun `load and delegated claim propagate cancellation from durable storage`() = runTest {
        val token = scheduled()
        now = token.scheduledAt
        storage.readFailure = CancellationException("isolated cancelled preference read")
        val loadFailure = runCatching { repository().load() }.exceptionOrNull()
        val claimFailure = runCatching { repository().claim(token) }.exceptionOrNull()
        assertTrue("Load must not turn cancellation into missing storage", loadFailure is CancellationException)
        assertTrue("Delegated claim must not turn cancellation into StorageFailed", claimFailure is CancellationException)
    }

    @Test fun `next schedule propagates cancellation from clock timezone and live permission`() = runTest {
        scheduled()
        val cancelledClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = throw CancellationException("isolated cancelled clock")
        }
        val repositories = listOf(
            DailyReminderRepository(store(), runtime, cancelledClock, { zone }, { permission }),
            DailyReminderRepository(store(), runtime, clock, { throw CancellationException("isolated cancelled zone") }, { permission }),
            DailyReminderRepository(store(), runtime, clock, { zone }, { throw CancellationException("isolated cancelled permission") }),
        )
        val failures = repositories.map { runCatching { it.nextSchedule() }.exceptionOrNull() }
        assertTrue("Scheduler cancellation must not be reported as storage unavailable", failures.all { it is CancellationException })
    }

    private fun replace(update: (DailyReminderPreferences) -> DailyReminderPreferences) {
        val store = store()
        val current = store.read("owner")
        assertTrue(store.compareAndSet("owner", current, update(current)))
    }
}

private class DeliveryPreferenceMemory : ReminderStringPreferences {
    val values = mutableMapOf<String, String>()
    var writes = 0
    var readFailure: Throwable? = null
    var afterRead: () -> Unit = {}
    override fun read(key: String): String? {
        readFailure?.let { throw it }
        return values[key].also { afterRead() }
    }
    override fun write(key: String, value: String?): Boolean {
        writes++
        if (value == null) values.remove(key) else values[key] = value
        return true
    }
}
