package com.reallanguage.app.feature.scene.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.scene.domain.SceneContentType
import com.reallanguage.app.feature.scene.domain.SceneDraftUnit
import com.reallanguage.app.feature.scene.domain.SceneQuestionSession
import com.reallanguage.app.feature.scene.domain.SceneSpeaker
import com.reallanguage.app.feature.scene.domain.SceneStart
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class SceneChineseReviewScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun rendersSeparatedReviewWorkspaceAndRealActions() {
        val actions = mutableListOf<SceneChineseReviewAction>()
        val session = SceneQuestionSession(
            SceneStart.DirectDescription("项目延期"), SceneContentType.DIALOGUE, emptyList(), operationId = "op",
        )
        composeRule.setContent {
            RlTheme {
                SceneChineseReviewScreen(
                    SceneChineseReviewUiState(
                        session,
                        listOf(
                            SceneDraftUnit("u1", SceneSpeaker.OTHER, "项目现在进展怎么样？", emptyList()),
                            SceneDraftUnit("u2", SceneSpeaker.USER, "我想说明项目需要延期。", emptyList()),
                        ),
                    ),
                    actions::add,
                )
            }
        }

        composeRule.onNodeWithTag("scene-review-header").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-review-context").assertIsDisplayed()
        composeRule.onNodeWithTag("scene-review-editor").assertIsDisplayed()
        composeRule.onAllNodesWithText("\u91cd\u65b0\u751f\u6210\u548c\u5b9e\u9645\u8c03\u7528 AI \u7684\u5ba1\u6838\u4f1a\u6309\u5b9e\u9645\u4f7f\u7528\u8ba1\u8d39\uff0c\u4e0d\u5f39\u51fa\u5355\u6b21\u786e\u8ba4\u3002")
            .assertCountEquals(0)

        val returnBounds = composeRule.onNodeWithTag("scene-review-return").getUnclippedBoundsInRoot()
        val regenerateBounds = composeRule.onNodeWithTag("scene-review-regenerate").getUnclippedBoundsInRoot()
        val confirmBounds = composeRule.onNodeWithTag("scene-review-confirm").getUnclippedBoundsInRoot()
        listOf(regenerateBounds, confirmBounds).forEach { bounds ->
            assertEquals("All review actions must share one height", (returnBounds.bottom - returnBounds.top).value, (bounds.bottom - bounds.top).value, 1f)
            assertEquals("All review actions must share one width", (returnBounds.right - returnBounds.left).value, (bounds.right - bounds.left).value, 1f)
            assertEquals("All review actions must align on the same row", returnBounds.top.value, bounds.top.value, 1f)
        }

        val firstTurn = composeRule.onNodeWithTag("scene-review-unit-u1").getUnclippedBoundsInRoot()
        val secondTurn = composeRule.onNodeWithTag("scene-review-unit-u2").getUnclippedBoundsInRoot()
        assertTrue(
            "Adjacent dialogue turns must read as one compact exchange",
            secondTurn.top.value - firstTurn.bottom.value <= 16f,
        )
        composeRule.onNodeWithTag("scene-review-confirm").performClick()
        composeRule.onNodeWithTag("scene-review-return").performClick()
        composeRule.onNodeWithTag("scene-review-regenerate").performClick()

        assertTrue(actions.contains(SceneChineseReviewAction.ConfirmRequested))
        assertTrue(actions.contains(SceneChineseReviewAction.ReturnToQuestionsRequested))
        assertTrue(actions.contains(SceneChineseReviewAction.RegenerateRequested))
    }

    @Test fun paragraphGrowsToTwoLinesAndShrinksAfterManualDeletion() {
        val session = SceneQuestionSession(
            SceneStart.DirectDescription("跨部门沟通"), SceneContentType.ARTICLE, emptyList(), operationId = "op",
        )
        lateinit var textState: MutableState<String>
        composeRule.setContent {
            RlTheme {
                textState = remember { mutableStateOf("短句。") }
                var text by textState
                SceneChineseReviewScreen(
                    SceneChineseReviewUiState(
                        session,
                        listOf(SceneDraftUnit("p1", null, text, emptyList())),
                    ),
                    onAction = { action ->
                        if (action is SceneChineseReviewAction.UnitChanged) text = action.textZh
                    },
                )
            }
        }

        fun fieldHeight() = composeRule.onNodeWithTag("scene-review-unit-p1")
            .getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

        fun assertEveryLineFits() {
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithTag("scene-review-unit-p1")
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertTrue("Every editor line must fit its measured height", layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1f)
            assertTrue("Editor must not ellipsize the final line", !layout.isLineEllipsized(layout.lineCount - 1))
            assertTrue("Editor text must remain complete", layout.layoutInput.text.text == textState.value)
        }

        val oneLineHeight = fieldHeight()
        composeRule.runOnIdle {
            textState.value = "在办公室里，作为产品经理，我和跨部门同事沟通下一步开发方案与交付安排，并说明目标、风险、时间节点和需要共同确认的决策。"
        }
        composeRule.waitForIdle()
        val twoLineHeight = fieldHeight()
        assertEveryLineFits()
        assertTrue(
            "Two-line Chinese content must receive more vertical space (one=$oneLineHeight, two=$twoLineHeight)",
            twoLineHeight > oneLineHeight,
        )

        composeRule.runOnIdle {
            textState.value = "这是一段需要自然展示的更长文字。".repeat(12)
        }
        composeRule.waitForIdle()
        val manyLineHeight = fieldHeight()
        assertEveryLineFits()
        assertTrue(
            "The field must expose all lines by growing instead of creating an inner scroller",
            manyLineHeight > twoLineHeight,
        )

        composeRule.runOnIdle { textState.value = "缩回一行。" }
        composeRule.waitForIdle()
        assertTrue("Deleting back to one line must shrink the editor", fieldHeight() < twoLineHeight)
        assertEveryLineFits()
    }

    @Test fun capturesCompleteDialogueCardsForVisualQa() {
        val session = SceneQuestionSession(
            SceneStart.DirectDescription("手机没电后联系等待的朋友"), SceneContentType.DIALOGUE,
            emptyList(), operationId = "visual-qa",
        )
        composeRule.setContent {
            RlTheme {
                SceneChineseReviewScreen(
                    SceneChineseReviewUiState(
                        session,
                        listOf(
                            SceneDraftUnit("u1", SceneSpeaker.USER, "不好意思，我刚才手机没电了，我刚充上电。", emptyList()),
                            SceneDraftUnit("u2", SceneSpeaker.USER, "我可能得晚点到，不好意思。", emptyList()),
                            SceneDraftUnit("u3", SceneSpeaker.OTHER, "没事，你现在在哪里？", emptyList()),
                            SceneDraftUnit("u4", SceneSpeaker.USER, "我在苏州街。", emptyList()),
                        ),
                    ),
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithTag("scene-review-unit-u1").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FileOutputStream(File(context.cacheDir, "scene-review-qa.png")).use {
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
