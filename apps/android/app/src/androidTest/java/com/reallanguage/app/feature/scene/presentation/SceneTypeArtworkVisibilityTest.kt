package com.reallanguage.app.feature.scene.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SceneTypeArtworkVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun optionCopyStaysInBlankHeaderAreaWithoutChangingArtworkLayout() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.window.decorView.width == 1831 &&
                compose.activity.window.decorView.height == 859
        }
        compose.setContent {
            RlTheme {
                SceneTypeScreen(
                    SceneTypeUiState(
                        SceneStart.DirectDescription("向同事解释项目延期"),
                        SceneContentType.DIALOGUE,
                    ),
                    onAction = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("之后会通过几个问题补全细节").assertDoesNotExist()

        val illustrationNodes = compose.onAllNodesWithTag(
            "scene-type-option-illustration",
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertEquals(2, illustrationNodes.size)
        assertTrue(
            illustrationNodes.all {
                it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f
            },
        )

        listOf("dialogue", "article").forEach { type ->
            val card = compose.onNodeWithTag("scene-type-$type", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithTag("scene-type-$type-title", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val summary = compose.onNodeWithTag("scene-type-$type-summary", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val keywords = compose.onNodeWithTag("scene-type-$type-keywords", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot

            assertTrue("$type title must stay above its summary", title.bottom <= summary.top + 2f)
            assertTrue("$type summary must stay above its keywords", summary.bottom <= keywords.top + 8f)
            assertTrue(
                "$type copy must stay in the artwork's blank upper area: card=$card keywords=$keywords",
                keywords.bottom <= card.top + card.height * .38f,
            )
        }
    }
}
