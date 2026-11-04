package com.reallanguage.app.feature.learning.presentation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompleteWritingAudioPlaybackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val initial = CompleteWritingUiState(
        FixedLearningChapter("playback-chapter", 1, listOf(FixedLearningSentence("s1", "I write every day.", "我每天写字。", true))),
        emptyList(), audioRequestId = 7,
    )

    @Test fun engineDeclineAndCompletionReportTheExactRequestOnceInsteadOfRemainingPlaying() {
        val state = mutableStateOf(initial)
        val calls = mutableListOf<Pair<String, Float>>()
        val events = mutableListOf<CompleteWritingAction>()
        rule.setContent {
            CompleteWritingAudioPlayback(state.value, play = { text, rate ->
                calls += text to rate
                calls.size > 1
            }, stop = {}, onAction = events::add)
        }
        rule.waitForIdle()
        assertEquals(listOf(CompleteWritingAction.AudioPlaybackFinished(7, false)), events)
        assertEquals(listOf("I write every day." to .82f), calls)
        rule.runOnIdle { state.value = initial.copy(audioRequestId = 8) }; rule.waitForIdle()
        assertEquals(listOf(CompleteWritingAction.AudioPlaybackFinished(7, false),
            CompleteWritingAction.AudioPlaybackFinished(8, true)), events)
        assertEquals(2, calls.size)
        rule.waitForIdle()
        assertEquals(2, calls.size)
    }

    @Test fun replacingAPlayIgnoresItsLateCompletionAndUsesTheLatestCallback() {
        val state = mutableStateOf(initial)
        val firstResponse = CompletableDeferred<Boolean>()
        val events = mutableListOf<Pair<Long, CompleteWritingAction>>()
        var calls = 0
        rule.setContent {
            val request = state.value.audioRequestId
            CompleteWritingAudioPlayback(state.value, play = { _, _ ->
                calls++
                if (calls == 1) withContext(NonCancellable) { firstResponse.await() } else true
            }, stop = {}, onAction = { events += request to it })
        }
        rule.waitUntil(5_000) { calls == 1 }
        rule.runOnIdle { state.value = initial.copy(audioRequestId = 8) }; rule.waitForIdle()
        try {
            assertEquals(listOf(8L to CompleteWritingAction.AudioPlaybackFinished(8, true)), events)
        } finally { firstResponse.complete(false) }
        rule.waitForIdle()
        assertEquals(listOf(8L to CompleteWritingAction.AudioPlaybackFinished(8, true)), events)
    }

    @Test fun leavingStopsThePlatformPlayerAndCancelsOnlyItsPendingAwaitWithoutAFailureEvent() {
        val visible = mutableStateOf(true)
        var calls = 0
        var stops = 0
        var cancellations = 0
        val events = mutableListOf<CompleteWritingAction>()
        rule.setContent {
            if (visible.value) CompleteWritingAudioPlayback(initial, play = { _, _ ->
                calls++
                try { awaitCancellation() } finally { cancellations++ }
            }, stop = { stops++ }, onAction = events::add)
        }
        rule.waitUntil(5_000) { calls == 1 }
        rule.runOnIdle { visible.value = false }; rule.waitForIdle()
        assertEquals(1, cancellations)
        assertEquals(1, stops)
        assertTrue(events.isEmpty())
    }
}
