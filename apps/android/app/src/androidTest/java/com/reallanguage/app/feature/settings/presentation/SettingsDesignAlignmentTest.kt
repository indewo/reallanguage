package com.reallanguage.app.feature.settings.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.settings.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Isolated rendering and editing; no account or persistence writes. */
class SettingsDesignAlignmentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun allFourTabsAndEightPoolsFitAndStyleUsesSameDraft() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        val original = InitialSettingsDraft(formalityLevel = 3, slangEnabled = false, basicVocabulary = BasicVocabulary.CET_6,
            voiceEnabled = true, autoPlayEnabled = false, speechSpeedLevel = 3)
        val state = mutableStateOf(SubsequentSettingsUiState(original = original, draft = original, loading = false))
        val actions = mutableListOf<SubsequentSettingsAction>()
        compose.setContent { RlTheme { SubsequentSettingsScreen(state.value, { action ->
            actions += action
            if (action is SubsequentSettingsAction.BasicVocabularyChanged) state.value = state.value.copy(draft = state.value.draft!!.copy(basicVocabulary = action.value))
        }) } }
        compose.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onNodeWithTag("editorial-page-back").fetchSemanticsNode().boundsInRoot.top < 100f }
        val inst = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.takeScreenshot()!!.let { bitmap -> java.io.File(inst.targetContext.filesDir, "settings-design-test.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        (0..3).forEach { compose.onNodeWithTag("subsequent-settings-navigation-$it").assertIsDisplayed() }
        val paper = compose.onNodeWithTag("subsequent-settings-paper").fetchSemanticsNode().boundsInRoot
        selectableVocabularyPools.forEach { pool ->
            val node = compose.onNodeWithTag("settings-vocabulary-${pool.name.lowercase()}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Pool must fit entirely inside paper: $pool $node $paper", node.top >= paper.top && node.bottom <= paper.bottom && node.right <= paper.right)
        }
        compose.onNodeWithTag("settings-vocabulary-senior_high").performClick()
        compose.onNodeWithTag("subsequent-settings-navigation-1").performClick()
        compose.onNodeWithTag("settings-shared-vocabulary-value").assertTextEquals("高中")
        val editor = compose.onNodeWithTag("settings-style-reference").assertIsDisplayed().fetchSemanticsNode()
        val stylePaper = compose.onNodeWithTag("subsequent-settings-paper").fetchSemanticsNode().boundsInRoot
        assertTrue("The entire style editor, including its lower border, must fit the paper", editor.boundsInRoot.height >= editor.layoutInfo.height - 1f && editor.boundsInRoot.bottom <= stylePaper.bottom)
        compose.onNodeWithTag("settings-edit-shared-vocabulary").performClick()
        compose.onNodeWithTag("settings-vocabulary-cet_6").performClick()
        compose.onNodeWithTag("subsequent-settings-navigation-1").performClick()
        compose.onNodeWithTag("settings-shared-vocabulary-value").assertTextEquals("六级")
        assertTrue(actions.all { it is SubsequentSettingsAction.BasicVocabularyChanged })
    }
}
