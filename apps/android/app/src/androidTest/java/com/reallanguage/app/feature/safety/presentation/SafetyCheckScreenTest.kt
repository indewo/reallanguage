package com.reallanguage.app.feature.safety.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class SafetyCheckScreenTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val source = ConfirmedSource(
        text = "今天很开心。",
        sourceType = SourceType.DIARY,
        contentShape = SourceContentShape.ARTICLE,
    )

    @Test
    fun processingUsesNeutralLanguageAndNoFakePercentage() {
        composeRule.setContent {
            RlTheme {
                SafetyCheckScreen(
                    state = SafetyCheckUiState(source, processing = true),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("安全检查").assertIsDisplayed()
        composeRule.onNodeWithText("正在确认这份内容可以进入学习").assertIsDisplayed()
        composeRule.onNodeWithText("我们只检查本次确认的中文来源，不会改变你的原意。").assertIsDisplayed()
        composeRule.onNodeWithText("来源识别").assertIsDisplayed()
        composeRule.onNodeWithText("内容安全").assertIsDisplayed()
        composeRule.onNodeWithText("隐私保护").assertIsDisplayed()
        composeRule.onNodeWithText("完整性确认").fetchSemanticsNode()
        composeRule.onNodeWithTag("safety-check-list").assertIsDisplayed()
        composeRule.onNodeWithTag("safety-check-source").assertIsDisplayed()
        composeRule.onNodeWithText("50%").assertDoesNotExist()
        composeRule.onNodeWithText("内容违规").assertDoesNotExist()
    }

    @Test
    fun serviceFailureNamesTheFailedStepAndPreservesRecovery() {
        composeRule.setContent {
            RlTheme {
                SafetyCheckScreen(
                    state = SafetyCheckUiState(source, failed = true),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("安全检查暂未完成").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed()
        composeRule.onNodeWithText("删除").assertDoesNotExist()
    }

    @Test
    fun checklistNeverCoversTheSourcePromiseAndAllFourSubtitlesFit() {
        composeRule.setContent { RlTheme { SafetyCheckScreen(SafetyCheckUiState(source, processing = true), {}) } }
        val promise = composeRule.onNodeWithText("我们只检查本次确认的中文来源，不会改变你的原意。")
            .fetchSemanticsNode().boundsInRoot
        val checklist = composeRule.onNodeWithTag("safety-check-list").fetchSemanticsNode().boundsInRoot
        assertTrue("Checklist must start below the explanatory copy: $promise / $checklist", checklist.top >= promise.bottom)
        for (text in listOf("确认中文来源", "检查不当或敏感信息", "隐藏个人信息", "保留原意与上下文")) {
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Text layout required for $text", layouts.size == 1)
            val layout = layouts.single()
            // Compose may retain the wider measured paragraph while its Text node wraps tightly.
            // Verify actual glyph boxes, not hasVisualOverflow's cached paragraph width.
            assertFalse("Subtitle must not omit lines: $text", layout.multiParagraph.didExceedMaxLines)
            assertTrue("Every subtitle glyph must fit: $text", text.indices.all { index ->
                val glyph = layout.getBoundingBox(index)
                glyph.left >= -1f && glyph.top >= -1f &&
                    glyph.right <= layout.size.width + 1f && glyph.bottom <= layout.size.height + 1f
            })
            assertTrue("Subtitle's last line must be visible: $text",
                layouts.single().getLineBottom(layouts.single().lineCount - 1) <= layouts.single().size.height + 1f)
        }
    }
}
