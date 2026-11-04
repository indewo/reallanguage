package com.reallanguage.app.feature.error.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.error.domain.ServiceFailureCategory
import com.reallanguage.app.feature.error.domain.ServiceFailureContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ServiceFailureScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun separatesFailureBoundaryAndPreservedItemsAndDispatchesRecoveryActions() {
        val actions = mutableListOf<ServiceFailureAction>()
        val state = ServiceFailureUiState(
            context = ServiceFailureContext(
                stepId = "formation",
                stepLabelZh = "章节形成",
                preservedDataLabelZh = "已确认的中文内容",
                category = ServiceFailureCategory.AI_OR_SERVICE,
            ),
            preservedItemsZh = listOf("今天开会时，我本来有点紧张。", "但最后还是鼓起勇气把想法讲清楚了。"),
        )

        composeRule.setContent { RlTheme { ServiceFailureScreen(state, actions::add) } }

        composeRule.onNodeWithTag("service-failure-boundary").assertIsDisplayed()
        composeRule.onNodeWithTag("service-failure-preserved").assertIsDisplayed()
        composeRule.onNodeWithText("今天开会时，我本来有点紧张。").assertIsDisplayed()
        composeRule.onNodeWithTag("service-failure-retry").performClick()
        composeRule.onNodeWithTag("service-failure-return").performClick()

        assertEquals(
            listOf(ServiceFailureAction.RetryRequested, ServiceFailureAction.ReturnRequested),
            actions,
        )
    }

    @Test
    fun moreThanTwoPreservedItemsRemainReadableAndRecoveryActionsRemainReachable() {
        val items = listOf("第一条原文完整保留。", "第二条原文也必须完整保留，不能因为纸张高度而被裁切。",
            "第三条内容不应被装饰模板静默删除。")
        val actions = mutableListOf<ServiceFailureAction>()
        composeRule.setContent {
            RlTheme {
                ServiceFailureScreen(ServiceFailureUiState(ServiceFailureContext("formation", "章节形成",
                    "已确认的中文内容", ServiceFailureCategory.AI_OR_SERVICE), preservedItemsZh = items), actions::add)
            }
        }
        items.forEach { value ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(value).performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertTrue("Every preserved paragraph must have real measured height",
                layouts.isNotEmpty() && layouts.all { !it.didOverflowHeight && it.lineCount > 0 &&
                    it.getLineBottom(it.lineCount - 1) <= it.size.height + 1f })
        }
        composeRule.onNodeWithTag("service-failure-retry").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("service-failure-return").assertIsDisplayed().performClick()
        assertEquals(listOf(ServiceFailureAction.RetryRequested, ServiceFailureAction.ReturnRequested), actions)
    }
}
