package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.FixedSceneResponseTask
import com.reallanguage.app.feature.learning.domain.SceneDialogueLine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Screenshot evidence for the exact user-reported viewport and both changed states. */
class SceneResponseVisualEvidenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionWindow() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test fun contextImagePreservesItsPeopleWhileFillingTheFixedSceneFrame() {
        render(fixture())
        compose.onNodeWithTag("scene-response-context-image").assertIsDisplayed()
        capture("scene-response-image-v89.png")
    }

    @Test fun postResponseCheckIsVisibleInPlaceWithoutOpeningAnotherPage() {
        render(fixture(transcribing = true))
        compose.onNodeWithTag("scene-response-checking-spinner").assertIsDisplayed()
        compose.onNodeWithTag("scene-response-recording-status").assertIsDisplayed()
        capture("scene-response-checking-v89.png")
    }

    private fun render(state: SceneResponseUiState) {
        compose.setContent { RlTheme { SceneResponseScreen(state, onExit = {}, onAction = {}) } }
        compose.waitForIdle()
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
            println("Scene response screenshot=${output.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun fixture(transcribing: Boolean = false) = SceneResponseUiState(
        chapter = FixedLearningChapter("visual-c1", 1,
            listOf(FixedLearningSentence("visual-s1", "It is a beautiful day today.", "今天是美好的一天。", true))),
        tasks = listOf(FixedSceneResponseTask(
            "visual-t1",
            "visual-s1",
            "You are standing outside on a bright morning after the rain has stopped.",
            listOf(
                SceneDialogueLine("AI", "The sky is clear, and everyone seems cheerful."),
                SceneDialogueLine("AI", "You take a deep breath and smile at the weather."),
            ),
            "It is a beautiful day today.",
        )),
        roundIndex = 2,
        transcribing = transcribing,
    )
}
