package com.reallanguage.app.feature.recording.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingElapsedTimeTest {
    @Test fun delayedTicksReportElapsedTimeInsteadOfCountingCallbacks() {
        var now = 10_000L
        val elapsed = RecordingElapsedTime { now }
        now += 4_700
        assertEquals(4, elapsed.tick())
        now += 115_300
        assertEquals(120, elapsed.tick())
    }

    @Test fun extraCallbacksDoNotAdvanceTimeOrExceedTheRecordingLimit() {
        var now = 10_000L
        val elapsed = RecordingElapsedTime { now }
        assertEquals(0, elapsed.tick())
        now += 125_000
        assertEquals(120, elapsed.tick())
    }
}
