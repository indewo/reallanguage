package com.reallanguage.app.feature.recording.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountRecordingStorageTest {
    @Test fun derivesAStableNonReversibleFilesystemKey() {
        val first = accountRecordingStorageKey("account-1")
        assertEquals(first, accountRecordingStorageKey("account-1"))
        assertFalse(first.contains("account-1"))
        assertFalse(first.contains('/'))
        assertEquals(64, first.length)
    }
}
