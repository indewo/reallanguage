package com.reallanguage.app.feature.recording.data

import com.reallanguage.app.feature.recording.domain.RECORDING_LIMIT_SECONDS

internal class RecordingElapsedTime(private val monotonicMilliseconds: () -> Long) {
    private var runningSince = monotonicMilliseconds()
    private var activeMilliseconds = 0L
    var paused = false
        private set

    fun setPaused(value: Boolean) {
        if (paused == value) return
        val now = monotonicMilliseconds()
        if (value) activeMilliseconds += (now - runningSince).coerceAtLeast(0L) else runningSince = now
        paused = value
    }

    fun tick(): Int {
        val running = if (paused) 0L else (monotonicMilliseconds() - runningSince).coerceAtLeast(0L)
        return ((activeMilliseconds + running) / 1_000L).coerceAtMost(RECORDING_LIMIT_SECONDS.toLong()).toInt()
    }
}
