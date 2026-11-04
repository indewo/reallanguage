package com.reallanguage.app.feature.home.data

import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.domain.*
import java.time.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DailyReminderRepositoryTest {
    private val storage = MemoryReminderPreferences()
    private val session = SessionRuntime().apply { update("isolated-token", "owner") }
    private var zone = ZoneId.of("Asia/Shanghai")
    private var permission = ReminderPermission.GRANTED
    private var now = Instant.parse("2026-09-08T10:00:00Z")
    private fun repository(runtime: SessionRuntime = session) = DailyReminderRepository(
        PersistentDailyReminderPreferenceStore(storage), runtime,
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
            override fun instant(): Instant = now
        }, { zone }, { permission },
    )

    @Test fun `first load defaults disabled at twenty and does not claim permission or tasks`() = runTest {
        assertEquals(DailyReminderPreferences(), repository().load())
        assertEquals(DailyReminderScheduleDecision.Cancel("owner", ReminderCancelReason.DISABLED), repository().nextSchedule())
        assertTrue(storage.values.isEmpty())
    }

    @Test fun `enable and user time survive repository recreation and accounts stay separate`() = runTest {
        assertEquals(DailyReminderIntroResult.Saved, repository().recordChoice(DailyReminderChoice.ENABLED, "intro"))
        assertTrue(repository().setTime(LocalTime.of(21, 15)).saved)
        val restored = requireNotNull(repository().load())
        assertTrue(restored.introHandled); assertTrue(restored.enabled)
        assertEquals(LocalTime.of(21, 15), restored.time)
        assertEquals("Asia/Shanghai", restored.zoneId)
        assertEquals(ReminderPermission.GRANTED, restored.permission)
        assertEquals(Instant.parse("2026-09-08T13:15:00Z"), (repository().nextSchedule() as DailyReminderScheduleDecision.ScheduleFuture).token.scheduledAt)
        val other = SessionRuntime().apply { update("other-test-token", "other") }
        assertEquals(DailyReminderPreferences(), repository(other).load())
    }

    @Test fun `dismissal persists while denied permission cannot be recorded as enabled`() = runTest {
        permission = ReminderPermission.DENIED
        assertEquals(DailyReminderIntroResult.Failed, repository().recordChoice(DailyReminderChoice.ENABLED, "intro"))
        assertEquals(DailyReminderIntroResult.Saved, repository().recordChoice(DailyReminderChoice.DISMISSED, "intro"))
        val restored = requireNotNull(repository().load())
        assertTrue(restored.introHandled); assertFalse(restored.enabled)
        assertEquals(ReminderPermission.DENIED, restored.permission)
    }

    @Test fun `replayed intro command cannot undo later disabling or change original choice`() = runTest {
        val repo = repository()
        assertEquals(DailyReminderIntroResult.Saved, repo.recordChoice(DailyReminderChoice.ENABLED, "intro"))
        assertTrue(repo.setEnabled(false).saved)
        assertEquals(DailyReminderIntroResult.Saved, repository().recordChoice(DailyReminderChoice.ENABLED, "intro"))
        assertFalse(requireNotNull(repository().load()).enabled)
        assertEquals(DailyReminderIntroResult.Failed, repository().recordChoice(DailyReminderChoice.DISMISSED, "intro"))
        assertEquals(DailyReminderIntroResult.Failed, repository().recordChoice(DailyReminderChoice.ENABLED, "different-intro"))
    }

    @Test fun `daily reservation survives restart and duplicate triggers do not send again`() = runTest {
        repository().recordChoice(DailyReminderChoice.ENABLED, "intro")
        val token = (repository().nextSchedule() as DailyReminderScheduleDecision.ScheduleFuture).token
        assertEquals(ReminderDeliveryClaim.Rejected, repository().claimScheduledDelivery(token))
        now = token.scheduledAt
        assertEquals(ReminderDeliveryClaim.Reserved, repository().claimScheduledDelivery(token))
        assertEquals(ReminderDeliveryClaim.AlreadyReserved, repository().claimScheduledDelivery(token))
        assertEquals(setOf(LocalDate.parse("2026-09-08")), requireNotNull(repository().load()).claimedLocalDates)
        assertEquals(Instant.parse("2026-09-09T12:00:00Z"), (repository().nextSchedule() as DailyReminderScheduleDecision.ScheduleFuture).token.scheduledAt)
        // A reserved notification that later fails is not called delivered, and its slot is not reused.
        assertTrue(repository().setEnabled(false).saved)
        assertTrue(repository().setEnabled(true).saved)
        assertEquals(ReminderDeliveryClaim.AlreadyReserved, repository().claimScheduledDelivery(token))
    }

    @Test fun `disabled logged out changed time and denied permission invalidate old triggers`() = runTest {
        val repo = repository()
        repo.recordChoice(DailyReminderChoice.ENABLED, "intro")
        val token = (repo.nextSchedule() as DailyReminderScheduleDecision.ScheduleFuture).token
        now = token.scheduledAt
        assertEquals("owner", repo.setTime(LocalTime.of(21, 0)).cancelScheduledForAccount)
        assertEquals(ReminderDeliveryClaim.Rejected, repo.claimScheduledDelivery(token))
        permission = ReminderPermission.DENIED
        assertEquals(DailyReminderScheduleDecision.Cancel("owner", ReminderCancelReason.PERMISSION_UNAVAILABLE), repo.nextSchedule())
        assertEquals(ReminderDeliveryClaim.Rejected, repo.claimScheduledDelivery(token))
        session.clear()
        assertEquals("owner", repo.cancelForLogout().cancelScheduledForAccount)
        assertEquals(DailyReminderScheduleDecision.Cancel("owner", ReminderCancelReason.SIGNED_OUT), repo.nextSchedule())
        assertEquals(ReminderDeliveryClaim.Rejected, repo.claimScheduledDelivery(token))
        session.update("isolated-token", "owner")
        assertTrue(requireNotNull(repository().load()).enabled)
    }

    @Test fun `old account repository cannot read write or reserve using a newly signed in account`() = runTest {
        val previous = repository()
        previous.recordChoice(DailyReminderChoice.ENABLED, "intro")
        val token = (previous.nextSchedule() as DailyReminderScheduleDecision.ScheduleFuture).token
        session.update("other-test-token", "other")
        assertNull(previous.load())
        assertFalse(previous.setEnabled(true).saved)
        assertEquals(ReminderDeliveryClaim.Rejected, previous.claimScheduledDelivery(token))
        previous.cancelForLogout()
        assertEquals(DailyReminderPreferences(), repository().load())
    }

    @Test fun `failed durable commit never grants delivery or pretends a saved preference`() = runTest {
        storage.failWrites = true
        assertEquals(DailyReminderIntroResult.Failed, repository().recordChoice(DailyReminderChoice.ENABLED, "intro"))
        assertEquals(DailyReminderPreferences(), repository().load())
        storage.failWrites = false
        repository().recordChoice(DailyReminderChoice.ENABLED, "intro")
        val token = (repository().nextSchedule() as DailyReminderScheduleDecision.ScheduleFuture).token
        now = token.scheduledAt
        storage.failWrites = true
        assertEquals(ReminderDeliveryClaim.StorageFailed, repository().claimScheduledDelivery(token))
        assertTrue(requireNotNull(repository().load()).claimedLocalDates.isEmpty())
        storage.failWrites = false
        assertEquals(ReminderDeliveryClaim.Reserved, repository().claimScheduledDelivery(token))
    }

    @Test fun `two recreated receivers atomically reserve only one daily slot`() = runTest {
        repository().recordChoice(DailyReminderChoice.ENABLED, "intro")
        val token = (repository().nextSchedule() as DailyReminderScheduleDecision.ScheduleFuture).token
        now = token.scheduledAt
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = executor.invokeAll(List(2) { java.util.concurrent.Callable { runBlocking { repository().claimScheduledDelivery(token) } } }).map { it.get() }
            assertEquals(1, results.count { it == ReminderDeliveryClaim.Reserved })
            assertEquals(1, results.count { it == ReminderDeliveryClaim.AlreadyReserved })
        } finally { executor.shutdownNow() }
    }

    @Test fun `failed preference commit that changed memory is restored before an intro retry`() = runTest {
        storage.failWrites = true
        storage.mutateOnFailedWrite = true
        assertEquals(DailyReminderIntroResult.Failed, repository().recordChoice(DailyReminderChoice.ENABLED, "intro"))
        assertEquals(DailyReminderPreferences(), repository().load())
        storage.failWrites = false
        assertEquals(DailyReminderIntroResult.Saved, repository().recordChoice(DailyReminderChoice.ENABLED, "intro"))
        assertTrue(requireNotNull(repository().load()).enabled)
    }

    @Test fun `missed time timezone changes and DST remain explicit decisions rather than catchup`() = runTest {
        repository().recordChoice(DailyReminderChoice.ENABLED, "intro")
        now = Instant.parse("2026-09-08T12:01:00Z")
        val missed = repository().nextSchedule() as DailyReminderScheduleDecision.NeedsDecision
        assertEquals(ReminderDecisionGap.MISSED_TIME, missed.reason)
        assertEquals(Instant.parse("2026-09-09T12:00:00Z"), missed.futureCandidate?.scheduledAt)
        assertTrue(requireNotNull(repository().load()).claimedLocalDates.isEmpty())
        zone = ZoneId.of("Asia/Tokyo")
        assertEquals(ReminderDecisionGap.TIME_ZONE_CHANGED, (repository().nextSchedule() as DailyReminderScheduleDecision.NeedsDecision).reason)
        val prefs = DailyReminderPreferences(true, true, LocalTime.of(2, 30), "Europe/Berlin", ReminderPermission.GRANTED)
        val missing = futureReminderDecision("owner", prefs, Instant.parse("2026-03-29T00:00:00Z"), ZoneId.of("Europe/Berlin"), ReminderPermission.GRANTED)
        assertEquals(ReminderDecisionGap.AMBIGUOUS_OR_MISSING_LOCAL_TIME, (missing as DailyReminderScheduleDecision.NeedsDecision).reason)
    }

    @Test fun `deletion clears only owned metadata and corrupted storage is not reset to defaults`() = runTest {
        val first = repository()
        first.recordChoice(DailyReminderChoice.ENABLED, "intro")
        val other = SessionRuntime().apply { update("other-token", "other") }
        repository(other).recordChoice(DailyReminderChoice.DISMISSED, "other-intro")
        assertTrue(first.clearForAccountDeletion().saved)
        assertEquals(DailyReminderPreferences(), repository().load())
        assertTrue(requireNotNull(repository(other).load()).introHandled)
        storage.values.replaceAll { _, _ -> "corrupt" }
        assertNull(repository(other).load())
        assertEquals(DailyReminderScheduleDecision.Cancel("other", ReminderCancelReason.STORAGE_UNAVAILABLE), repository(other).nextSchedule())
    }
}

private class MemoryReminderPreferences : ReminderStringPreferences {
    val values = mutableMapOf<String, String>()
    var failWrites = false
    var mutateOnFailedWrite = false
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String?): Boolean {
        if (failWrites && !mutateOnFailedWrite) return false
        if (value == null) values.remove(key) else values[key] = value
        return !failWrites
    }
}
