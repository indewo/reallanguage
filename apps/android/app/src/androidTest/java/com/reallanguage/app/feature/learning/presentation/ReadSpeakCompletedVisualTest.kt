package com.reallanguage.app.feature.learning.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Completed read-and-speak state rendered in the actual focused-learning window. */
class ReadSpeakCompletedVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun completedPageHasRoundHolesNoGenericFeedbackAndAnInwardTrailingArrow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        val sentence = FixedLearningSentence(
            "read-completed",
            "It's a beautiful day today.",
            "今天是美好的一天。",
            true,
        )
        val state = ReadSpeakUiState(
            chapter = FixedLearningChapter("read-completed-chapter", 1, listOf(sentence)),
            currentFocusSentenceId = sentence.sentenceId,
            completed = true,
            highlightedWordCount = 5,
            feedbackZh = READ_SPEAK_NO_AUDIO_COMPLETION_COPY,
        )
        compose.setContent {
            RlTheme { RlPaperBackground {
                Box(Modifier.fillMaxSize().testTag("read-completed-viewport")) {
                    ReadSpeakScreen(state) {}
                }
            } }
        }
        compose.waitForIdle()
        compose.onNodeWithText("已读完").assertIsDisplayed()
        compose.onNodeWithText(READ_SPEAK_NO_AUDIO_COMPLETION_COPY).assertDoesNotExist()

        val holes = compose.onNodeWithTag("learning-round-hole-strip").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals("The punch-hole artwork must retain its source aspect ratio",
            320f / 1536f, holes.width / holes.height, .02f)

        val bitmap = compose.onNodeWithTag("learning-current-trailing-arrow").assertIsDisplayed()
            .captureToImage().asAndroidBitmap()
        try {
            val coloredXs = buildList {
                for (y in 0 until bitmap.height) {
                    for (x in 0 until bitmap.width) {
                        val color = bitmap.getPixel(x, y)
                        val red = android.graphics.Color.red(color)
                        val green = android.graphics.Color.green(color)
                        val blue = android.graphics.Color.blue(color)
                        if (red > green + 20 && red > blue + 20) add(x.toFloat())
                    }
                }
            }
            assertTrue("The trailing arrow needs visible action-colored pixels", coloredXs.isNotEmpty())
            assertTrue("A left-pointing triangle has its color centroid to the right of center",
                coloredXs.average() > (bitmap.width - 1) / 2f)

            val output = File(instrumentation.targetContext.cacheDir, CAPTURE_NAME)
            output.outputStream().use { check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue("Completed-state evidence must be a nonempty PNG", output.length() > 0L)
        } finally { bitmap.recycle() }
    }

    private companion object {
        const val CAPTURE_NAME = "read-speak-completed-v97.png"
    }
}
