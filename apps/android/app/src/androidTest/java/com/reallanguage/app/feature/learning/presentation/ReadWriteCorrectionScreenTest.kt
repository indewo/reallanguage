package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.click

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import org.junit.Rule
import org.junit.Test

class ReadWriteCorrectionScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun correctionCanvasCanBeClearedWithoutCompletingLearning() {
        composeRule.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        composeRule.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        val chapter = FixedLearningChapter("isolated-correction", 1, listOf(
            FixedLearningSentence("s1", "Hello, what would you like to order?", "你好，你想点什么？", true),
            FixedLearningSentence("s2", "We have a special deal today.", "今天我们有特别优惠。", false)))
        val actions = mutableListOf<ReadWriteCorrectionAction>()
        composeRule.setContent { RlTheme { ReadWriteCorrectionScreen(
            ReadWriteCorrectionUiState(chapter, "s1", listOf("order：点餐")), actions::add) } }
        val clear = composeRule.onNodeWithTag("correction-clear")
        clear.assertIsDisplayed().assertIsNotEnabled()
        val canvas = composeRule.onNodeWithTag("correction-writing-canvas")
        canvas.assertIsDisplayed().performTouchInput { swipe(Offset(width * .2f, height * .4f), Offset(width * .6f, height * .6f), 300) }
        clear.assertIsEnabled().performClick().assertIsNotEnabled()
        org.junit.Assert.assertTrue("Drawing and clearing must not submit a learning result", actions.isEmpty())
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.handwriting_finished)).assertIsDisplayed().assertIsEnabled()
    }

    @Test fun pendingSaveClearlyDisablesConflictingActions() {
        composeRule.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        composeRule.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true)))
        val actions = mutableListOf<ReadWriteCorrectionAction>()
        composeRule.setContent { RlTheme { ReadWriteCorrectionScreen(ReadWriteCorrectionUiState(
            chapter, "s1", listOf("felt：感到"), saving = true,
        ), actions::add) } }
        val back = composeRule.onNodeWithText(composeRule.activity.getString(R.string.formation_back))
        // These controls are fixed outside the shared content scroll, as in production.
        back.assertIsDisplayed()
        val file = java.io.File(composeRule.activity.filesDir, "correction-saving.png")
        file.outputStream().use { composeRule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        back.assertIsNotEnabled().performTouchInput { click() }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.learning_mute))
            .assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        org.junit.Assert.assertTrue("Saving must block conflicting actions", actions.isEmpty())
    }
}
