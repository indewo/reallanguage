package com.reallanguage.app.feature.learning.presentation

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LearningCheckpointGateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newStepCannotStartBeforeItsResumePointerIsSaved() {
        val saved = CompletableDeferred<CheckpointSaveResult>()
        val checkpoints = mutableListOf<OrdinaryLearningCheckpoint>()
        compose.setContent {
            RlTheme {
                LearningCheckpointGate(OrdinaryLearningCheckpoint("c1", "LR-03", "s1"), LearningCheckpointRepository {
                    checkpoints += it; saved.await()
                }, {}) { Text("真实学习模块") }
            }
        }
        compose.onNodeWithText("真实学习模块").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, checkpoints.size); saved.complete(CheckpointSaveResult.Saved) }
        compose.waitForIdle()
        compose.onNodeWithText("真实学习模块").assertIsDisplayed()
    }

    @Test fun failedSaveStaysBlockedAndRetrySavesTheSameStep() {
        var calls = 0
        val returned = mutableStateOf(false)
        compose.setContent {
            RlTheme {
                LearningCheckpointGate(OrdinaryLearningCheckpoint("c1", "LR-04", "s1"), LearningCheckpointRepository {
                    calls++; if (calls == 1) CheckpointSaveResult.Failed else CheckpointSaveResult.Saved
                }, { returned.value = true }) { Text("手写模块") }
            }
        }
        compose.onNodeWithText("手写模块").assertDoesNotExist()
        compose.onNodeWithTag("checkpoint-retry").performClick()
        compose.onNodeWithText("手写模块").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, calls); assertEquals(false, returned.value) }
    }
}
