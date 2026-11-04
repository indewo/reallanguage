package com.reallanguage.app.core.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecordingPlaybackLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun backgroundAndPageRemovalBothStopPlayback() {
        val visible = mutableStateOf(true)
        val stops = AtomicInteger()
        compose.setContent { if (visible.value) RecordingPlaybackLifecycle("isolated", stops::incrementAndGet) }
        compose.waitForIdle()
        val beforeBackground = stops.get()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(stops.get() > beforeBackground)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        val beforeRemoval = stops.get()
        compose.runOnUiThread { visible.value = false }
        compose.waitForIdle()
        assertTrue(stops.get() > beforeRemoval)
    }
}
