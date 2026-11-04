package com.reallanguage.app.feature.recording.presentation

import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneStart
import com.reallanguage.app.feature.scene.presentation.SceneQuestionsError
import com.reallanguage.app.feature.scene.presentation.SceneQuestionsScreen
import com.reallanguage.app.feature.scene.presentation.SceneQuestionsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.Before

/** A short landscape viewport must show the actionable state before decorative covers. */
class CompactEditorialPriorityScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun showLandscapeWorkspace() {
        rule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        rule.waitUntil(5_000) { rule.activity.window.decorView.width > rule.activity.window.decorView.height }
    }

    @Test fun localRecordingFailureAndRetryAreVisibleWithoutScrollingPastArtwork() {
        rule.setContent { RlTheme { Box(Modifier.requiredSize(640.dp, 360.dp)) {
            LocalRecordingsScreen(LocalRecordingsUiState(loading = false, loadFailed = true), {})
        } } }
        rule.onNodeWithText("暂时无法读取本机录音").assertIsDisplayed()
        rule.onNodeWithText("重试").assertIsDisplayed()
    }

    @Test fun recordingReadFailureAndRetryAreVisibleWithoutScrollingPastArtwork() {
        rule.setContent { RlTheme { Box(Modifier.requiredSize(640.dp, 360.dp)) {
            RecordingConfirmationScreen(RecordingConfirmationUiState(loading = false, error = RecordingConfirmationError.LOAD_FAILED), {})
        } } }
        rule.onNodeWithText("暂时无法读取这段录音").assertIsDisplayed()
        rule.onNodeWithText("重试").assertIsDisplayed()
    }

    @Test fun insufficientSceneInformationAndReturnActionAreVisibleBeforeArtwork() {
        rule.setContent { RlTheme { Box(Modifier.requiredSize(640.dp, 360.dp)) {
            SceneQuestionsScreen(SceneQuestionsUiState(
                start = SceneStart.DirectDescription("和朋友约见面"), contentType = SceneContentType.DIALOGUE,
                loading = false, error = SceneQuestionsError.INSUFFICIENT_INFORMATION,
            ), {})
        } } }
        rule.onNodeWithTag("scene-questions-error").assertIsDisplayed()
        rule.onNodeWithTag("scene-questions-submit").assertIsDisplayed()
    }
}
