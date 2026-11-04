package com.reallanguage.app.feature.recording.data

/** Isolates the service ticker for checking delayed/background delivery. */
internal class RecordingElapsedTime(private val monotonicMilliseconds: () -> Long) {
    private val startedAt = monotonicMilliseconds()

    fun tick(): Int {
        return ((monotonicMilliseconds() - startedAt).coerceAtLeast(0L) / 1_000L).coerceAtMost(120L).toInt()
    }
}
