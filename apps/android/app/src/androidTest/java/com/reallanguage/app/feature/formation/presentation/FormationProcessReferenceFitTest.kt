package com.reallanguage.app.feature.formation.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Verify the native card labels, not only their presence in an offscreen scroll tree. */
class FormationProcessReferenceFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun window() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test fun englishStageLabelsFitBeforeTheFixedNote() = verify(
        listOf("读取中文语义", "生成自然英语", "检查忠实程度"),
        "中文内容、重点句和章节会保留。",
    ) { EnglishGenerationScreen(EnglishGenerationUiState(processing = true), {}) }

    @Test fun targetStageLabelsFitBeforeTheFixedNote() = verify(
        listOf("分析重点句", "提取词汇与句式", "建立学习目标"),
        "只使用你已确认的表达与当前词汇范围。",
    ) { LearningTargetLoadingScreen(LearningTargetLoadingUiState(processing = true), {}) }

    @Test fun failedTargetStagesAreFixedAndItsPunchHolesStayRound() {
        compose.setContent {
            RlTheme {
                LearningTargetLoadingScreen(LearningTargetLoadingUiState(failed = true), {})
            }
        }
        compose.onNode(hasTestTag("process_stage_panel").and(hasScrollAction()))
            .assertDoesNotExist()
        val buttonsTop = compose.onNodeWithText("重试").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        listOf("分析重点句", "提取词汇与句式", "建立学习目标").forEach { label ->
            val bounds = compose.onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$label must remain above the fixed actions", bounds.bottom <= buttonsTop)
        }
        repeat(7) { index ->
            val hole = compose.onNodeWithTag("process_spine_hole_$index", useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("punch hole $index must be circular", kotlin.math.abs(hole.width - hole.height) <= 1f)
        }
    }

    private fun verify(labels: List<String>, note: String, screen: @Composable () -> Unit) {
        compose.setContent { RlTheme { screen() } }
        val footer = compose.onNodeWithText(note).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        labels.forEach { label ->
            val node = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$label must retain its established reading size", layout.layoutInput.style.fontSize.value >= 15f)
            assertTrue("$label must not be clipped by the paper viewport", bounds.height + 1f >= layout.size.height)
            assertTrue("$label must be wholly above the persistent note", bounds.bottom <= footer.top)
            assertTrue("$label must include the final glyph", layout.getLineEnd(layout.lineCount - 1) == label.length)
        }
    }
}
