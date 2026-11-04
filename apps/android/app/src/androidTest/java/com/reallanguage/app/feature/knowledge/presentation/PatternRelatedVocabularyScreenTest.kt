package com.reallanguage.app.feature.knowledge.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import com.reallanguage.app.feature.knowledge.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before

/** Display-only fixture: stored explanation values, never new vocabulary tasks or AI output evidence. */
class PatternRelatedVocabularyScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun useProductionWindow() {
        compose.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
    }

    @Test fun expandedPatternDisplaysOnlyItsOwnSavedBilingualExplanation() {
        val first = record("first", listOf(PatternRelatedVocabulary("tea", "茶（饮品）")))
        val second = record("second", listOf(PatternRelatedVocabulary("bank", "河岸")))
        val state = mutableStateOf(PatternBookUiState(listOf(first, second), "first", loading = false))
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.setContent { RlTheme { PatternBookScreen(state.value, {}) } }
        compose.onNodeWithText("tea：茶（饮品）").performScrollTo().assertIsDisplayed()
        capture("43-related-vocabulary-current.png")
        compose.onNodeWithText("bank：河岸").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(expandedPatternId = "second") }
        compose.onNodeWithText("bank：河岸").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("tea：茶（饮品）").assertDoesNotExist()
    }

    @Test fun absentHistoricalExplanationsDoNotInventTextOrAnEmptyNewSection() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val old = record("old", null)
        val empty = record("empty", emptyList())
        val state = mutableStateOf(PatternBookUiState(listOf(old, empty), "old", loading = false))
        compose.setContent { RlTheme { PatternBookScreen(state.value, {}) } }
        compose.onNodeWithTag("pattern-detail-sheet").assertIsDisplayed()
        compose.onNodeWithText("相关生词").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(expandedPatternId = "empty") }
        compose.onNodeWithText("相关生词").assertDoesNotExist()
    }

    @Test fun longSavedMeaningAtDoubleFontIsReadableAndDoesNotBlockCollapseOrRemoval() {
        val meaning = "这里表示饮用的一种茶，而不是另一处上下文的用餐含义。".repeat(7)
        val text = "tea：$meaning"
        val pattern = record("long", listOf(PatternRelatedVocabulary("tea", meaning)))
        val actions = mutableListOf<PatternBookAction>()
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                RlTheme { Box(Modifier.size(640.dp, 360.dp)) {
                    PatternBookScreen(PatternBookUiState(listOf(pattern), "long", loading = false), actions::add)
                } }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        assertTrue(layout.multiParagraph.height <= layout.size.height + 1f)
        repeat(layout.lineCount) { line ->
            assertTrue(layout.getLineLeft(line) >= -1f)
            assertTrue(layout.getLineRight(line) <= layout.size.width + 1f)
        }
        compose.onNodeWithTag("pattern-detail-collapse").performScrollTo().assertIsDisplayed().performClick()
        capture("43-related-vocabulary-font2-actions.png")
        compose.onNodeWithTag("pattern-detail-remove").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(PatternBookAction.CollapseRequested, PatternBookAction.RemoveRequested), actions)
    }

    private fun record(id: String, related: List<PatternRelatedVocabulary>?) = PatternRecord(id, "used to + verb",
        "表示过去的习惯", KnowledgeStatus.LEARNING, KnowledgeStatus.LEARNING, 0, 1,
        structureParts = listOf("used to", "verb"), relatedVocabulary = related)

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureRelatedVocabulary") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), name)
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
