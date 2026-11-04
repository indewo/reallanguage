package com.reallanguage.app.core.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.CheckpointSaveResult
import com.reallanguage.app.feature.learning.domain.LearningCheckpointRepository
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import com.reallanguage.app.feature.learning.presentation.LearningExitViewModel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Local lifecycle-only complements the real Graph/SQL regression; not an API success substitute. */
class LearningExitEntryLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val checkpoint = OrdinaryLearningCheckpoint("isolated-chapter", "LR-04", "s")

    @Test fun leavingExitEntryCancelsItsPendingSaveWithoutNavigation() {
        val visible = mutableStateOf(true)
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val navigations = AtomicInteger()
        val repository = LearningCheckpointRepository {
            started.incrementAndGet()
            try { awaitCancellation() } finally { cancelled.incrementAndGet() }
        }
        val factory = viewModelFactory { initializer { LearningExitViewModel(checkpoint, repository) } }
        compose.setContent { RlTheme {
            if (visible.value) LearningExitEntry(factory, "same-entry",
                { navigations.incrementAndGet() }, { navigations.incrementAndGet() }, { navigations.incrementAndGet() })
        } }
        compose.onNodeWithTag("learning-exit-pause").performClick()
        compose.waitUntil(5_000) { started.get() == 1 }
        compose.runOnIdle { visible.value = false }
        compose.waitUntil(5_000) { cancelled.get() == 1 }
        assertEquals(0, navigations.get())
    }

    @Test fun savingExitUsesTheCurrentHomeCallbackExactlyOnce() {
        val pending = CompletableDeferred<CheckpointSaveResult>()
        val started = AtomicInteger()
        val oldHome = AtomicInteger()
        val currentHome = AtomicInteger()
        val useCurrentCallback = mutableStateOf(false)
        val repository = LearningCheckpointRepository { started.incrementAndGet(); pending.await() }
        val factory = viewModelFactory { initializer { LearningExitViewModel(checkpoint, repository) } }
        compose.setContent { RlTheme {
            val callback: () -> Unit = if (useCurrentCallback.value) ({ currentHome.incrementAndGet(); Unit })
                else ({ oldHome.incrementAndGet(); Unit })
            LearningExitEntry(factory, "same-entry", {}, callback, {})
        } }
        compose.onNodeWithTag("learning-exit-pause").performClick()
        compose.waitUntil(5_000) { started.get() == 1 }
        compose.runOnIdle { useCurrentCallback.value = true }
        compose.waitForIdle()
        compose.runOnIdle { pending.complete(CheckpointSaveResult.Saved) }
        compose.waitUntil(5_000) { currentHome.get() + oldHome.get() == 1 }
        assertEquals(0, oldHome.get())
        assertEquals(1, currentHome.get())
        assertEquals(1, started.get())
    }
}
