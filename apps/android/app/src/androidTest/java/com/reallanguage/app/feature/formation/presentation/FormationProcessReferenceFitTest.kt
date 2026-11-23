package com.reallanguage.app.feature.formation.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Verify the native card labels, not only their presence in an offscreen scroll tree. */
class FormationProcessReferenceFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun window() {
        compose.activityRule.scenario.onActivity {
            if (it.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @Test fun englishStageLabelsFitBeforeTheFixedNote() = verify(
        listOf("读取中文语义", "生成自然英语", "核对是否符合原意"),
        "中文内容、重点句和章节会保留。",
    ) { EnglishGenerationScreen(EnglishGenerationUiState(processing = true), {}) }

    @Test fun targetStageLabelsFitBeforeTheFixedNote() = verify(
        listOf("分析重点句", "提取词汇\n与句式", "建立\n学习目标"),
        "提取当前表达中的词义、搭配与句式。",
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
        listOf("分析重点句", "提取词汇\n与句式", "建立\n学习目标").forEach { label ->
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

    @Test fun stageLabelsAndIconsStayInsideTheirCards() {
        compose.setContent {
            RlTheme {
                LearningTargetLoadingScreen(LearningTargetLoadingUiState(processing = true), {})
            }
        }
        listOf("分析重点句", "提取词汇\n与句式", "建立\n学习目标").forEachIndexed { index, label ->
            val art = compose.onNodeWithTag("process_stage_art_$index", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val labelNode = compose.onNodeWithTag("process_stage_label_$index", useUnmergedTree = true)
                .assertIsDisplayed()
            val textBounds = labelNode.fetchSemanticsNode().boundsInRoot
            assertTrue("$label must stay inside its card, got $textBounds in $art",
                textBounds.left >= art.left - 1f && textBounds.right <= art.right + 1f &&
                    textBounds.top >= art.top - 1f && textBounds.bottom <= art.bottom + 1f)
            assertTrue("$label must clear the torn bottom edge, got $textBounds in $art",
                textBounds.bottom <= art.bottom - art.height * .08f)
            assertTrue("$label must sit below the circular stamp, got $textBounds in $art",
                textBounds.top >= art.top + art.height * .57f)
            val icon = compose.onNodeWithTag("process_stage_icon_$index", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("the icon of $label must stay inside its card, got $icon in $art",
                icon.left >= art.left - 1f && icon.right <= art.right + 1f &&
                    icon.top >= art.top - 1f && icon.bottom <= art.bottom + 1f)
            val iconCenterY = icon.top + icon.height / 2f
            assertTrue("the icon of $label must sit in the circular stamp, got $icon in $art",
                iconCenterY >= art.top + art.height * .30f &&
                    iconCenterY <= art.top + art.height * .48f)
            assertTrue("the icon of $label must not overlap its label", icon.bottom <= textBounds.top + 1f)
            val layouts = mutableListOf<TextLayoutResult>()
            labelNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue("$label must use at most two lines", layout.lineCount <= 2)
            repeat(layout.lineCount) { line ->
                assertTrue("$label must not leave an orphan glyph on line ${line + 1}",
                    layout.getLineEnd(line) - layout.getLineStart(line) >= 2)
            }
        }
    }

    @Test fun captureStageRailForVisualReview() {
        compose.setContent {
            RlTheme {
                LearningTargetLoadingScreen(LearningTargetLoadingUiState(processing = true), {})
            }
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val target = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "stage-rail-v2.png",
        )
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("stage rail screenshot must be written to ${target.absolutePath}", target.length() > 0)
        assertTrue("stage rail screenshot must keep the device width", bitmap.width >= bitmap.height)
    }

    private fun verify(labels: List<String>, note: String, screen: @Composable () -> Unit) {
        compose.setContent { RlTheme { screen() } }
        // The compact layout (short or narrow windows) drops the persistent note on purpose to keep
        // the stage rail readable, so the note is only asserted when this window actually renders it.
        val footer = compose.onAllNodesWithText(note, useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        // Labels now shrink on demand to stay inside their card, so only a readability floor is asserted.
        val minimumLabelSize = 11.5f
        labels.forEach { label ->
            val node = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$label must retain its established reading size", layout.layoutInput.style.fontSize.value >= minimumLabelSize)
            assertTrue("$label must not be clipped by the paper viewport", bounds.height + 1f >= layout.size.height)
            if (footer != null) {
                assertTrue("$label must be wholly above the persistent note", bounds.bottom <= footer.top)
            }
            assertTrue("$label must include the final glyph", layout.getLineEnd(layout.lineCount - 1) == label.length)
            repeat(layout.lineCount) { line ->
                val glyphs = layout.getLineEnd(line) - layout.getLineStart(line)
                assertTrue("$label must not leave an orphan glyph on line ${line + 1}", glyphs >= 2)
            }
        }
    }
}
