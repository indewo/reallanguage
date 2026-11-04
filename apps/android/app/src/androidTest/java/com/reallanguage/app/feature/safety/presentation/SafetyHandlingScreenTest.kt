package com.reallanguage.app.feature.safety.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SafetyCaseDetails
import com.reallanguage.app.feature.safety.domain.SafetyEdit
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import org.junit.Rule
import org.junit.Test

class SafetyHandlingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsSpecificReasonDeidentifiedTextAndEveryMarkedChange() {
        val actions = mutableListOf<SafetyHandlingAction>()
        composeRule.setContent {
            RlTheme {
                SafetyHandlingScreen(
                    state = SafetyHandlingUiState(
                        SafetyCaseDetails(
                            safetyCaseId = "case-1",
                            originalSource = ConfirmedSource(
                                text = "原始内容包含具体姓名张三。",
                                sourceType = SourceType.DIARY,
                                contentShape = SourceContentShape.ARTICLE,
                            ),
                            userReasonZh = "内容包含需要保护的具体身份信息。",
                            deidentifiedSource = ConfirmedSource(
                                text = "原始内容包含某位同事。",
                                sourceType = SourceType.DIARY,
                                contentShape = SourceContentShape.ARTICLE,
                            ),
                            edits = listOf(
                                SafetyEdit(
                                    originalText = "张三",
                                    replacementText = "某位同事",
                                    explanationZh = "隐藏具体姓名，同时保留人物关系。",
                                ),
                            ),
                        ),
                    ),
                    onAction = actions::add,
                )
            }
        }

        listOf(
            "内容安全处理",
            "内容包含需要保护的具体身份信息。",
            "原始内容包含某位同事。",
            "使用脱敏版本",
            "返回修改",
            "提交人工复核",
        ).forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        composeRule.onNodeWithText("直接使用原内容").assertDoesNotExist()
        listOf("隐藏具体姓名，同时保留人物关系。", "变更说明").forEach {
            composeRule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }

        listOf(
            "safety-handling-reason",
            "safety-handling-original",
            "safety-handling-deidentified",
            "safety-handling-changes",
            "safety-handling-editor-note",
        ).forEach { composeRule.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithTag("safety-handling-actions").assertIsDisplayed()
        composeRule.onNodeWithText("张三").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("某位同事").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("返回修改").performClick()
        composeRule.onNodeWithText("提交人工复核").performClick()
        composeRule.onNodeWithText("使用脱敏版本").performClick()
        assert(actions == listOf(
            SafetyHandlingAction.ReturnToModifyRequested,
            SafetyHandlingAction.SubmitManualReviewRequested,
            SafetyHandlingAction.UseDeidentifiedRequested,
        ))
    }
}
