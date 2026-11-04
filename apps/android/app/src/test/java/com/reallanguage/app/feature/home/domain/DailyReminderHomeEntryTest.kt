package com.reallanguage.app.feature.home.domain

import org.junit.Assert.*
import org.junit.Test

class DailyReminderHomeEntryTest {
    @Test fun `first valid home shows once until user closes without pretending storage succeeded`() {
        val entry = DailyReminderHomeEntry(); val preferences = DailyReminderPreferences()
        assertTrue(entry.shouldShow("a", preferences)); assertTrue(entry.shouldShow("a", preferences))
        entry.closed("a")
        repeat(3) { assertFalse(entry.shouldShow("a", preferences)) }
        assertFalse(preferences.introHandled)
    }
    @Test fun `missing or failed ownership and already handled preference never open notice`() {
        val entry = DailyReminderHomeEntry()
        assertFalse(entry.shouldShow(null, DailyReminderPreferences()))
        assertFalse(entry.shouldShow("", DailyReminderPreferences()))
        assertFalse(entry.shouldShow("a", null))
        assertFalse(entry.shouldShow("a", DailyReminderPreferences(introHandled = true)))
    }
    @Test fun `switch account does not reuse another dismissal and a new process rereads persisted choice`() {
        val entry = DailyReminderHomeEntry(); entry.closed("a")
        assertTrue(entry.shouldShow("b", DailyReminderPreferences()))
        assertTrue(DailyReminderHomeEntry().shouldShow("a", DailyReminderPreferences()))
        assertFalse(DailyReminderHomeEntry().shouldShow("a", DailyReminderPreferences(introHandled = true)))
    }
}
