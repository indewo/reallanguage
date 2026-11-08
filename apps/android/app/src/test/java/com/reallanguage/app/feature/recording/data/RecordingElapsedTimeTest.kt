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
        now += 3_700_000
        assertEquals(3600, elapsed.tick())
    }

    @Test fun pausingExcludesWallTimeAndResumesFromTheSameAudioPosition() {
        var now = 0L
        val clock = RecordingElapsedTime { now }
        now = 7_750
        clock.setPaused(true)
        now += 19_000
        assertEquals(7, clock.tick())
        clock.setPaused(false)
        now += 350
        assertEquals(8, clock.tick())
    }
}
