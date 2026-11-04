package com.reallanguage.app.feature.settings.data

import com.reallanguage.app.core.session.SessionRuntime
import com.reallanguage.app.feature.home.data.DailyReminderRepository
import com.reallanguage.app.feature.home.data.DailyReminderPreferenceStore
import com.reallanguage.app.feature.home.domain.*
import com.reallanguage.app.feature.settings.domain.ReminderSettingsDraft
import java.time.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReminderSettingsAtomicSaveTest {
    private val initial = ReminderSettingsDraft(false, LocalTime.of(20, 0))
    private val proposed = ReminderSettingsDraft(true, LocalTime.of(21, 15))
    private val runtime = SessionRuntime().apply { update("isolated-test-session", "owner") }
    private var permission = ReminderPermission.GRANTED
    private val store = MemoryStore()
    private fun repo() = DailyReminderRepository(store, runtime, Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC),
        { ZoneId.of("Asia/Shanghai") }, { permission })
    private fun adapter() = LocalReminderSettingsRepository(repo())

    @Test fun enabledAndTimeCommitOnceAndRecreatedAdapterReadsExactSavedDraft() = runTest {
        assertTrue(adapter().save(initial, proposed))
        assertEquals(1, store.writes)
        assertEquals(proposed, adapter().load())
        assertEquals(1L, store.values.getValue("owner").revision)
        assertTrue(adapter().save(initial, proposed))
        assertEquals(1, store.writes)
    }

    @Test fun failureOrDeniedPermissionCannotHalfSaveTimeOrEnable() = runTest {
        store.reject = true
        assertFalse(adapter().save(initial, proposed))
        assertEquals(initial, adapter().load())
        store.reject = false; permission = ReminderPermission.DENIED
        assertFalse(adapter().save(initial, proposed))
        assertEquals(initial, adapter().load())
    }

    @Test fun staleDraftDoesNotOverwriteOtherSettingsAndAccountSwitchRejectsOldRepository() = runTest {
        assertTrue(adapter().save(initial, proposed))
        assertFalse(adapter().save(initial, proposed.copy(time = LocalTime.of(22, 0))))
        assertEquals(proposed, adapter().load())
        val old = adapter()
        runtime.update("isolated-other-session", "other")
        assertFalse(old.save(proposed, initial)); assertNull(old.load())
        assertEquals(initial, adapter().load())
        assertEquals(proposed.time, store.values.getValue("owner").time)
    }

    @Test fun noOpOrDeliveryLedgerChangeDoesNotDiscardReservationOrReenableAfterDisable() = runTest {
        assertTrue(adapter().save(initial, proposed))
        val date = LocalDate.parse("2026-09-08")
        store.values["owner"] = store.values.getValue("owner").copy(claimedLocalDates = setOf(date), revision = 2)
        assertTrue(adapter().save(proposed, proposed.copy(enabled = false)))
        assertEquals(setOf(date), store.values.getValue("owner").claimedLocalDates)
        assertEquals(3L, store.values.getValue("owner").revision)
        assertEquals("owner", repo().saveSettings(false, proposed.time, false, proposed.time).cancelScheduledForAccount)
    }

    private class MemoryStore : DailyReminderPreferenceStore {
        val values = mutableMapOf<String, DailyReminderPreferences>()
        var writes = 0; var reject = false
        override fun read(accountId: String): DailyReminderPreferences = values[accountId] ?: DailyReminderPreferences()
        override fun compareAndSet(accountId: String, expected: DailyReminderPreferences, replacement: DailyReminderPreferences): Boolean {
            writes++
            if (reject || read(accountId) != expected) return false
            values[accountId] = replacement; return true
        }
        override fun clear(accountId: String): Boolean { values.remove(accountId); return true }
    }
}
