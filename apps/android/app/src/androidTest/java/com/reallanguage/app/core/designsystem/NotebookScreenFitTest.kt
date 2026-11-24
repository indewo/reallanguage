package com.reallanguage.app.core.designsystem

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.feature.billing.domain.DailyAiCharge
import com.reallanguage.app.feature.billing.presentation.*
import com.reallanguage.app.feature.learning.domain.*
import com.reallanguage.app.feature.learning.presentation.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

/** Isolated render fixtures, never a substitute for a real learning submission. */
class NotebookScreenFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val chapter = FixedLearningChapter("fixture", 1, listOf(FixedLearningSentence("s", "I would like a coffee without sugar.", "我想要一杯不加糖的咖啡。", true)))
    @Before fun window() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        compose.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
    }
    @Test fun patternResponseIsContainedAndActionsRemainReachable() {
        val examples = (1..5).map { PatternPracticeExample("e$it", "在咖啡馆点单，要一杯不加糖的冰美式。", "What would you like?", "Can I get an iced Americano without sugar?", "我可以要一杯不加糖的冰美式咖啡吗？") }
        val state = PatternSpeakingUiState(chapter, "s", listOf(LockedSpeakingPattern("p", "s", "without [noun]", "不加……；没有……")),
            stage = PatternSpeakingStage.RESPONDING, examples = examples, responseIndex = 1,
            transcript = "Can I get an iced Americano without sugar?", feedbackZh = "回应完成。", currentResponseCompleted = true)
        compose.setContent { RlTheme { PatternSpeakingScreen(state) {} } }
        compose.onNodeWithText("下一场景").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("回应完成。").assertIsDisplayed()
        val paper = compose.onNodeWithTag("pattern-exercise-content").fetchSemanticsNode().boundsInRoot
        val feedback = compose.onNodeWithText("回应完成。").fetchSemanticsNode().boundsInRoot
        assertTrue(feedback.bottom <= paper.bottom && feedback.right <= paper.right)
        capture("notebook-pattern-render.png")
    }
    @Test fun sceneDialogueAppearsAboveItsControlsAndFinalRoundConcealsAnswer() {
        val task = FixedSceneResponseTask("t", "s", "You are ordering at a coffee shop.",
            listOf(SceneDialogueLine("AI", "Welcome! What can I get for you today?")), "A fixed answer which must be hidden.")
        compose.setContent { RlTheme { SceneResponseScreen(SceneResponseUiState(chapter, listOf(task), roundIndex = 2)) {} } }
        compose.onNodeWithText(task.targetSentenceEn).assertDoesNotExist()
        compose.onNodeWithTag("scene-dialogue-scroll").assertIsDisplayed()
        compose.onNodeWithTag("scene-response-main-action").assertIsDisplayed().assertIsEnabled()
        capture("notebook-scene-render.png")
    }
    @Test fun dailyLedgerShowsOneBalanceAndCompleteGroupValues() {
        compose.setContent { RlTheme { AiBillingScreen(AiBillingUiState(balanceAmountFen = 9652, loading = false,
            dailyCharges = listOf(DailyAiCharge("2026-11-24", "提取学习目标", 501, 501),
                DailyAiCharge("2026-11-24", "英文发音", 3, 3)))) {} } }
        compose.onAllNodesWithText("￥96.52").assertCountEquals(1)
        compose.onNodeWithText("501").assertIsDisplayed()
        compose.onNodeWithText("-￥5.01").assertIsDisplayed()
        compose.onNodeWithText("申请退款").assertIsDisplayed().assertIsEnabled()
        capture("notebook-fees-render.png")
    }
    @Test fun largeTextKeepsPrimaryActionsAndTaskContextReachable() {
        val page = mutableStateOf(0)
        val example = PatternPracticeExample("e", "在咖啡馆点单，不加糖。", "What would you like?", "A coffee without sugar, please.", "请给我一杯不加糖的咖啡。")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                RlTheme {
                    when (page.value) {
                        0 -> AiBillingScreen(AiBillingUiState(balanceAmountFen = 9652, loading = false,
                            dailyCharges = listOf(DailyAiCharge("2026-11-24", "提取学习目标", 501, 501)))) {}
                        1 -> SceneResponseScreen(SceneResponseUiState(chapter, listOf(FixedSceneResponseTask("t", "s", "Order at a coffee shop.",
                            listOf(SceneDialogueLine("AI", "What can I get for you?")), "Hidden answer.")), roundIndex = 2)) {}
                        else -> PatternSpeakingScreen(PatternSpeakingUiState(chapter, "s", listOf(LockedSpeakingPattern("p", "s", "without [noun]", "不加……")),
                            stage = PatternSpeakingStage.RESPONDING, examples = listOf(example))) {}
                    }
                }
            }
        }
        compose.onNodeWithText("申请退款").assertIsDisplayed().assertIsEnabled()
        capture("notebook-fees-large-font.png")
        compose.runOnIdle { page.value = 1 }
        compose.onNodeWithTag("scene-response-main-action").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Hidden answer.").assertDoesNotExist()
        capture("notebook-scene-large-font.png")
        compose.runOnIdle { page.value = 2 }
        compose.onNodeWithText(example.sceneZh).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("开始回应").assertIsDisplayed().assertIsEnabled()
        capture("notebook-pattern-large-font.png")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
