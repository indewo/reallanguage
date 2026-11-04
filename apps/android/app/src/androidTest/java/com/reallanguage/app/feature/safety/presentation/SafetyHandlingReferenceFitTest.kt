package com.reallanguage.app.feature.safety.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.safety.domain.ConfirmedSource
import com.reallanguage.app.feature.safety.domain.SafetyCaseDetails
import com.reallanguage.app.feature.safety.domain.SafetyEdit
import com.reallanguage.app.feature.safety.domain.SourceContentShape
import com.reallanguage.app.feature.safety.domain.SourceType
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SafetyHandlingReferenceFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun productionWindow() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    @Test fun referenceTwoEditRowsAndBothPapersFitBeforeFixedActions() {
        val original = ConfirmedSource("今天我和王经理在上海办公室讨论了项目。", SourceType.DIARY, SourceContentShape.ARTICLE)
        val state = SafetyHandlingUiState(SafetyCaseDetails(
            safetyCaseId = "reference12", originalSource = original,
            userReasonZh = "原文包含可能识别个人身份的信息，暂时不能直接进入学习。",
            deidentifiedSource = original.copy(text = "今天我和一位同事在办公室讨论了项目。"),
            edits = listOf(SafetyEdit("王经理", "一位同事", "已隐藏"), SafetyEdit("上海办公室", "办公室", "已隐藏")),
        ))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                RlTheme { Box(Modifier.requiredSize(914.5.dp, 430.dp)) { SafetyHandlingScreen(state, {}) } }
            }
        }
        val actions = compose.onNodeWithTag("safety-handling-actions").getUnclippedBoundsInRoot()
        listOf("safety-handling-original", "safety-handling-deidentified", "safety-handling-changes", "safety-handling-editor-note").forEach { tag ->
            val node = compose.onNodeWithTag(tag)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$tag must remain wholly above the fixed actions", bounds.bottom <= actions.top + 1.dp)
        }
        compose.onNodeWithText("办公室").assertIsDisplayed()
        compose.onNodeWithText("已按隐私保护规范脱敏，\n不影响语义表达与学习理解。").assertIsDisplayed()
        listOf("返回修改", "提交人工复核", "使用脱敏版本").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }
}
