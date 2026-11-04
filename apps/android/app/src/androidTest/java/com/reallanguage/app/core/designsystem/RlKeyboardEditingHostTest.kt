package com.reallanguage.app.core.designsystem

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RlKeyboardEditingHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun landscapeImeShowsSingleLineMirrorAndEditsTheSourceValue() {
        compose.activityRule.scenario.onActivity {
            if (it.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            configureFocusedLearningWindow(it.window)
        }
        var observed = ""
        compose.setContent {
            var value by remember { mutableStateOf("前文 很长的当前句 后文") }
            observed = value
            RlTheme {
                RlKeyboardEditingHost {
                    RlKeyboardAwareOutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        modifier = Modifier.testTag("source-field"),
                    )
                }
            }
        }
        compose.onNodeWithTag("source-field").performClick()
        compose.waitUntil(5_000) {
            compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        compose.onNodeWithTag("landscape-keyboard-editing-bar").assertIsDisplayed()
        compose.onNodeWithTag("landscape-keyboard-editing-input").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("landscape-keyboard-editing-input").performTextReplacement("可见并可编辑的完整一行")
        compose.waitForIdle()
        assertEquals("可见并可编辑的完整一行", observed)
    }

    @Test fun floatingImeWithSmallBottomInsetStillShowsEditorAboveTheKeyboardRegion() {
        compose.activityRule.scenario.onActivity {
            if (it.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            configureFocusedLearningWindow(it.window)
        }
        var observed = ""
        compose.setContent {
            var value by remember { mutableStateOf("需要左右查看和修改的当前长句") }
            observed = value
            RlTheme {
                RlKeyboardEditingHost(
                    imeVisibleOverride = true,
                    // Samsung and other floating IMEs can report only a shallow
                    // navigation-area inset instead of their floating rectangle.
                    imeBottomOverride = 24.dp,
                ) {
                    RlKeyboardAwareOutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        modifier = Modifier.testTag("floating-source-field"),
                    )
                }
            }
        }
        compose.onNodeWithTag("floating-source-field").performClick()
        val editor = compose.onNodeWithTag("landscape-keyboard-editing-bar").assertIsDisplayed()
        val editorBounds = editor.fetchSemanticsNode().boundsInRoot
        val rootHeight = compose.activity.window.decorView.height.toFloat()
        org.junit.Assert.assertTrue(
            "Floating-IME fallback editor must stay in the upper keyboard-safe band: editor=$editorBounds rootHeight=$rootHeight",
            editorBounds.bottom < rootHeight * .48f,
        )
        compose.onNodeWithTag("landscape-keyboard-editing-input").performClick()
        compose.onNodeWithTag("landscape-keyboard-editing-input").performTextReplacement("悬浮键盘下仍可编辑")
        compose.waitForIdle()
        assertEquals("悬浮键盘下仍可编辑", observed)
    }

    @Test fun longAuxiliaryInputExposesHorizontalBrowsingAndKeepsTheCaretVisible() {
        compose.activityRule.scenario.onActivity {
            if (it.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            configureFocusedLearningWindow(it.window)
        }
        val longText = "这是开头，" + "需要左右浏览的上下文内容，".repeat(18) + "这是当前光标位置"
        compose.setContent {
            var value by remember { mutableStateOf(longText) }
            RlTheme {
                RlKeyboardEditingHost {
                    RlKeyboardAwareOutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        modifier = Modifier.testTag("long-source-field"),
                    )
                }
            }
        }
        compose.onNodeWithTag("long-source-field").performClick()
        compose.waitUntil(5_000) {
            compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        val input = compose.onNodeWithTag("landscape-keyboard-editing-input").assertIsDisplayed()
        input.performClick()
        compose.waitForIdle()
        val bounds = input.fetchSemanticsNode().boundsInRoot
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val before = requireNotNull(automation.takeScreenshot())
        input.performTouchInput { swipeRight(durationMillis = 500) }
        compose.waitForIdle()
        val after = requireNotNull(automation.takeScreenshot())
        try {
            var changed = 0
            val left = bounds.left.toInt().coerceAtLeast(0)
            val right = bounds.right.toInt().coerceAtMost(before.width)
            val top = bounds.top.toInt().coerceAtLeast(0)
            val bottom = bounds.bottom.toInt().coerceAtMost(before.height)
            for (y in top until bottom step 2) for (x in left until right step 2) {
                if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
            }
            org.junit.Assert.assertTrue(
                "A physical horizontal swipe must reveal different long-context text pixels: changed=$changed bounds=$bounds",
                changed > 250,
            )
        } finally {
            before.recycle()
            after.recycle()
        }
    }
}
