package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Same-size visual evidence for the user-reported complete-writing state. */
class CompleteWritingVisualEvidenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionWindow() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test fun capturesExpandedSilentWritingPaperAndInwardSentenceArrows() {
        compose.setContent { RlTheme { CompleteWritingScreen(fixture(CompleteWritingPhase.SILENT_SINGLE), {}) } }
        compose.waitForIdle()

        compose.onNodeWithTag("chapter-writing-workspace-artwork").assertDoesNotExist()
        compose.onNodeWithText("无声书写").assertDoesNotExist()
        compose.onNodeWithTag("complete-writing-input-single").assertIsDisplayed()
        compose.onNodeWithTag("learning-current-leading-arrow").assertIsDisplayed()
        compose.onNodeWithTag("learning-current-trailing-arrow").assertIsDisplayed()
        capture("complete-writing-silent-v91.png")
    }

    @Test fun capturesAudibleWritingWithTheSameExpandedLayout() {
        compose.setContent { RlTheme { CompleteWritingScreen(fixture(CompleteWritingPhase.AUDIBLE_SINGLE), {}) } }
        compose.waitForIdle()

        compose.onNodeWithTag("chapter-writing-workspace-artwork").assertDoesNotExist()
        compose.onNodeWithText("有声书写").assertDoesNotExist()
        compose.onNodeWithText("听完整句发音，键盘写出英文。").assertDoesNotExist()
        compose.onNodeWithTag("complete-writing-input-single").assertIsDisplayed()
        compose.onNodeWithTag("learning-current-leading-arrow").assertIsDisplayed()
        compose.onNodeWithTag("learning-current-trailing-arrow").assertIsDisplayed()
        capture("complete-writing-audible-v91.png")
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(2340, bitmap.width)
            assertEquals(1080, bitmap.height)
            val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val output = File(directory, name)
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            println("Complete writing screenshot=${output.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun fixture(phase: CompleteWritingPhase) = CompleteWritingUiState(
        chapter = FixedLearningChapter("visual-c1", 1, listOf(
            FixedLearningSentence("visual-s1", "It is a beautiful day today.", "今天是美好的一天。", true),
        )),
        lockedTargets = emptyList(),
        phase = phase,
    )
}
