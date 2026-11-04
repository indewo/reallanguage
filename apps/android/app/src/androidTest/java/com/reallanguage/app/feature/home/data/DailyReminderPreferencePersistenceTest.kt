package com.reallanguage.app.feature.home.data

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.domain.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Only a UUID-named preference file; never reads or clears production reminder preferences. */
class DailyReminderPreferencePersistenceTest {
    @Test fun actualPreferencesSurviveRecreationReserveOnlyOnceAndKeepAccountBoundaries() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceFile = "reminder-regression-${UUID.randomUUID()}"
        val owner = "reminder-owner-${UUID.randomUUID()}"
        val otherOwner = "reminder-other-${UUID.randomUUID()}"
        val runtime = SessionRuntime().apply { update("isolated-test-token", owner) }
        var now = Instant.parse("2026-09-08T10:00:00Z")
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
            override fun instant(): Instant = now
        }
        fun repository(session: SessionRuntime = runtime) = DailyReminderRepository(
            AndroidDailyReminderPreferenceStore(context, preferenceFile), session, clock,
            { ZoneId.of("Asia/Shanghai") }, { ReminderPermission.GRANTED },
        )
        try {
            assertEquals(DailyReminderPreferences(), repository().load())
            assertEquals(DailyReminderIntroResult.Saved, repository().recordChoice(DailyReminderChoice.ENABLED, "intro"))
            assertTrue(repository().setTime(LocalTime.of(21, 15)).saved)
            val beforeRestart = requireNotNull(repository().load())
            val restoredSession = SessionRuntime().apply { update("new-isolated-token", owner) }
            assertEquals(beforeRestart, repository(restoredSession).load())
            val token = (repository(restoredSession).nextSchedule() as DailyReminderScheduleDecision.ScheduleFuture).token
            assertEquals(Instant.parse("2026-09-08T13:15:00Z"), token.scheduledAt)
            now = token.scheduledAt
            assertEquals(ReminderDeliveryClaim.Reserved, repository(restoredSession).claimScheduledDelivery(token))
            assertEquals(ReminderDeliveryClaim.AlreadyReserved, repository(restoredSession).claimScheduledDelivery(token))
            val other = SessionRuntime().apply { update("other-isolated-token", otherOwner) }
            assertEquals(DailyReminderPreferences(), repository(other).load())
            assertEquals(DailyReminderIntroResult.Saved, repository(other).recordChoice(DailyReminderChoice.DISMISSED, "other-intro"))
            val otherBefore = repository(other).load()
            val disabled = repository(restoredSession).setEnabled(false)
            assertTrue(disabled.saved)
            assertEquals(owner, disabled.cancelScheduledForAccount)
            assertEquals(ReminderDeliveryClaim.Rejected, repository(restoredSession).claimScheduledDelivery(token))
            val exiting = repository(restoredSession)
            restoredSession.clear()
            assertEquals(owner, exiting.cancelForLogout().cancelScheduledForAccount)
            assertEquals(ReminderDeliveryClaim.Rejected, exiting.claimScheduledDelivery(token))
            restoredSession.update("another-isolated-token", owner)
            val after = requireNotNull(repository(restoredSession).load())
            assertFalse(after.enabled)
            assertEquals(LocalTime.of(21, 15), after.time)
            assertEquals(setOf(LocalDate.parse("2026-09-08")), after.claimedLocalDates)
            assertEquals(otherBefore, repository(other).load())
            assertTrue(repository(restoredSession).clearForAccountDeletion().saved)
            assertEquals(DailyReminderPreferences(), repository(restoredSession).load())
            assertEquals(otherBefore, repository(other).load())
        } finally {
            context.deleteSharedPreferences(preferenceFile)
        }
    }
}
