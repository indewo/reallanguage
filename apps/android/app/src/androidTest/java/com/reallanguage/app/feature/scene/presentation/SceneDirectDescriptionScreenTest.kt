package com.reallanguage.app.feature.scene.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlKeyboardEditingHost
import com.reallanguage.app.core.designsystem.RlTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SceneDirectDescriptionScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dedicatedPageEditsFullDescriptionAndContinuesWithoutCallingAi() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(
            "wm size 1831x859",
            "wm density 320",
            "settings put system font_scale 1.0",
        ).forEach { command ->
            val result = automation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.window.decorView.width == 1831 && compose.activity.window.decorView.height == 859
        }
        val actions = mutableListOf<SceneDirectDescriptionAction>()
        compose.setContent {
            var state by remember { mutableStateOf(SceneDirectDescriptionUiState()) }
            RlTheme { RlKeyboardEditingHost {
                SceneDirectDescriptionScreen(state, onAction = { action ->
                    actions += action
                    if (action is SceneDirectDescriptionAction.TextChanged) state = state.copy(
                        text = action.value,
                        selectionStart = action.selectionStart,
                        selectionEnd = action.selectionEnd,
                    )
                })
            } }
        }
        compose.onNodeWithTag("scene-description-input").performTextInput("和同事确认项目延期安排")
        compose.activityRule.scenario.onActivity { it.window.insetsController?.hide(WindowInsets.Type.ime()) }
        compose.waitForIdle()
        compose.onNodeWithTag("scene-description-input").assertTextContains("和同事确认项目延期安排")
        compose.onNodeWithTag("scene-description-continue").assertHasClickAction().performClick()
        assertTrue(actions.last() is SceneDirectDescriptionAction.ContinueRequested)

        val directory = File(requireNotNull(compose.activity.getExternalFilesDir(null)), "visual-qa")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "scene-direct-description-current.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val copy = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cp ${File(directory, "scene-direct-description-current.png").absolutePath} " +
                "/sdcard/Download/scene-direct-description-current.png",
        )
        ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
        assertEquals(1831, compose.activity.window.decorView.width)
    }
}
