package com.reallanguage.app.feature.learning.presentation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.learning.domain.OrdinaryLearningCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real user viewport regression. The device owner supplies 2340 x 1080 px,
 * density 320 and font scale 1.5 before this test is run.
 */
class LearningExitDialogPhoneFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun enlargedPhoneKeepsEveryChoiceAndActionOnOneFixedPage() {
        val configuration = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        assertEquals(2340, metrics.widthPixels)
        assertEquals(1080, metrics.heightPixels)
        assertEquals(320, configuration.densityDpi)
        assertEquals(1.5f, configuration.fontScale, .001f)

        compose.setContent {
            RlTheme {
                LearningExitDialog(
                    state = LearningExitUiState(
                        OrdinaryLearningCheckpoint("phone-fit-chapter", "LR-09", "round-2"),
                    ),
                    onAction = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText(compose.activity.getString(R.string.learning_exit_page_title)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.learning_exit_explanation)).assertIsDisplayed()
        compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).assertCountEquals(0)

        val tags = listOf("learning-exit-continue", "learning-exit-pause", "learning-exit-abandon")
        val bounds = tags.map { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        val root = generateSequence(compose.onNodeWithTag(tags.first()).fetchSemanticsNode()) { it.parent }.last().boundsInRoot
        assertTrue("Every action must keep a visible lower safe margin: root=$root buttons=$bounds",
            bounds.all { root.bottom - it.bottom >= 48f })
        assertTrue("The right action must move left of the page edge: root=$root abandon=${bounds.last()}",
            root.right - bounds.last().right >= 240f)
    }
}
