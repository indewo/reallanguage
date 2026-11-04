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
