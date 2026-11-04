package com.reallanguage.app.core.app

import org.junit.Assert.*
import org.junit.Test

class ReminderHomeRequestTest {
    private val key = "a".repeat(64)
    private val action = "com.reallanguage.app.DAILY_REMINDER_HOME"
    @Test fun `another account notification is removed before any child flow can receive its request`() {
        val stale = ReminderHomeRequest(key, "old-account")
        assertNull(reminderHomeRequestForOwner(stale) { false })
    }
    @Test fun `account guard preserves the same event only when identity still matches`() {
        val current = ReminderHomeRequest(key, "current-account")
        assertSame(current, reminderHomeRequestForOwner(current) { it.ownerKey == key })
        var consulted = false
        assertNull(reminderHomeRequestForOwner(null) { consulted = true; true })
        assertFalse(consulted)
    }
    @Test fun `only exact private reminder action and digest shaped home location are accepted`() {
        assertEquals(ReminderHomeRequest(key, "request"), parseReminderHomeRequest(action, "reallanguage-reminder://home/$key", "request"))
        for (uri in listOf(null, "https://home/$key", "reallanguage-reminder://home/account-email", "reallanguage-reminder://home/$key?x=1", "reallanguage-reminder://daily/$key", "reallanguage-reminder://home/$key/extra"))
            assertNull(parseReminderHomeRequest(action, uri, "request"))
        assertNull(parseReminderHomeRequest("external", "reallanguage-reminder://home/$key", "request"))
        assertNull(parseReminderHomeRequest(action, "reallanguage-reminder://home/$key", ""))
    }
    @Test fun `safe read only pages return home without starting a lesson`() {
        for (route in listOf(HomeDiaryRoute.Home, HomeDiaryRoute.TodayReview, HomeDiaryRoute.VocabularyBook,
            HomeDiaryRoute.PatternBook, HomeDiaryRoute.IncompleteLearning(), HomeDiaryRoute.Messages()))
            assertEquals(ReminderHomeDisposition.HOME, reminderHomeDisposition(route))
    }
    @Test fun `ordinary and review work use their existing save and discard boundaries`() {
        assertEquals(ReminderHomeDisposition.ORDINARY_EXIT, reminderHomeDisposition(HomeDiaryRoute.ChapterLearning("chapter")))
        assertEquals(ReminderHomeDisposition.REVIEW_EXIT, reminderHomeDisposition(HomeDiaryRoute.ReviewChapter("review")))
    }
    @Test fun `draft input and settings use their own existing back decisions`() {
        assertEquals(ReminderHomeDisposition.CURRENT_FLOW_BACK, reminderHomeDisposition(HomeDiaryRoute.Input("unsaved")))
        assertEquals(ReminderHomeDisposition.CURRENT_FLOW_BACK, reminderHomeDisposition(HomeDiaryRoute.Settings()))
    }
    @Test fun `active recording and unresolved submissions cannot be dropped by an external intent`() {
        for (route in listOf(HomeDiaryRoute.ActiveRecording(), HomeDiaryRoute.SafetyCheck("draft"), HomeDiaryRoute.SemanticSplit("draft"),
            HomeDiaryRoute.TranscriptResult("recording", "transcript"), HomeDiaryRoute.Account()))
            assertEquals(ReminderHomeDisposition.WAIT_FOR_SAFE_BOUNDARY, reminderHomeDisposition(route))
    }
}
