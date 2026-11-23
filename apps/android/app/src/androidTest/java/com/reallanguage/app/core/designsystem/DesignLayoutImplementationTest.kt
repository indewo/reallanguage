package com.reallanguage.app.core.designsystem

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.feature.home.domain.*
import com.reallanguage.app.feature.home.presentation.*
import com.reallanguage.app.feature.knowledge.domain.*
import com.reallanguage.app.feature.knowledge.presentation.*
import com.reallanguage.app.feature.learning.presentation.LearningHeading
import org.junit.*
import org.junit.Assert.*

/** Production UI with isolated data: never writes or advances the user's chapters. */
class DesignLayoutImplementationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun landscape() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val inst = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.takeScreenshot()!!.let { bitmap ->
            java.io.File(inst.targetContext.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun patternCategoriesStayCenteredAndHeaderStaysFixedAcrossExpansion() {
        val pattern = PatternRecord("p1", "work up the courage to + 动词", "鼓起勇气做某事", KnowledgeStatus.MASTERED, KnowledgeStatus.LEARNING, 2, 4,
            representativeScene = "终于下定决心做一件犹豫已久的事", examples = listOf(PatternExample("I worked up the courage to apply.", "我鼓起勇气提出申请。", "e1")))
        val state = mutableStateOf(PatternBookUiState(patterns = listOf(pattern), loading = false))
        val actions = mutableListOf<PatternBookAction>()
        compose.setContent { RlTheme { PatternBookScreen(state.value) { action ->
            actions += action
            when (action) {
                is PatternBookAction.PatternSelected -> state.value = state.value.copy(expandedPatternId = action.patternId)
                is PatternBookAction.QueryChanged -> state.value = state.value.copy(query = action.query)
                PatternBookAction.CollapseRequested -> state.value = state.value.copy(expandedPatternId = null)
                else -> Unit
            }
        } } }
        val back = compose.onNodeWithTag("ledger-back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        capture("layout-pattern-centering-diagnostic.png")
        var previousTab: androidx.compose.ui.geometry.Rect? = null
        listOf("基础\n句式", "功能\n句式", "过渡\n衔接", "高级\n表达").forEachIndexed { index, label ->
            val tab = compose.onNodeWithTag("pattern-category-$index").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val displayLabel = if (compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) label else label.replace("\n", "")
            val text = compose.onNodeWithText(displayLabel, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            previousTab?.let { assertTrue("Categories must not overlap", tab.left >= it.right || tab.top >= it.bottom) }
            assertEquals(tab.center.x, text.center.x, 2f)
            assertEquals("Category $index: paper=$tab text=$text", tab.center.y, text.center.y, 2f)
            assertTrue(text.left >= tab.left && text.right <= tab.right && text.top >= tab.top && text.bottom <= tab.bottom)
            previousTab = tab
        }
        capture("layout-pattern-list.png")
        compose.onNodeWithTag("pattern-row-p1").performClick()
        compose.onNodeWithText("我鼓起勇气提出申请。").performScrollTo().assertIsDisplayed()
        assertEquals(back, compose.onNodeWithTag("ledger-back").fetchSemanticsNode().boundsInRoot)
        capture("layout-pattern-detail.png")
        compose.onNodeWithTag("pattern-detail-collapse").performScrollTo().performClick()
        compose.onNodeWithTag("pattern-book-search").performTextInput("不存在的句式")
        compose.onNodeWithText("没有匹配的句式或功能").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("高级表达", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val tab = compose.onNodeWithTag("pattern-category-3").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText("高级表达", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Keyboard must not compress category lettering", text.top >= tab.top && text.bottom <= tab.bottom)
        capture("layout-pattern-keyboard.png")
        compose.onNodeWithTag("ledger-back").performClick()
        assertEquals(PatternBookAction.ReturnHomeRequested, actions.last())
    }
    private val title = "我想要学习英语，用于日常生活，也为未来的大学学习做好准备。"
    private fun entries() = listOf(
        IncompleteLearningEntry("one", OrdinarySourceType.QUICK_RECORDING, title, 1, "LR-01", "章节开始", ResumeLearningKind.ORDINARY),
        IncompleteLearningEntry("two", OrdinarySourceType.DIARY, "记录今天的一次日常交流", null, "LR-00", "选择学习目标", ResumeLearningKind.FORMATION),
    )
    @Test fun incompleteCardsHaveIndependentTitleAndButtonAndPreserveFormationMeaning() {
        val actions = mutableListOf<IncompleteLearningAction>()
        compose.setContent { RlTheme { IncompleteLearningScreen(IncompleteLearningUiState(entries = entries(), loading = false), actions::add) } }
        val text = compose.onNodeWithText(title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("incomplete-learning-resume-one").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Continue must not cover the title", text.right <= button.left)
        compose.onNodeWithText("章节开始 · 第 1 / 9 步").assertIsDisplayed()
        compose.onNodeWithText("选择学习目标 · 尚未确认").performScrollTo().assertIsDisplayed()
        capture("layout-incomplete-cards.png")
        compose.onNodeWithTag("incomplete-learning-resume-one").performScrollTo().performClick()
        assertEquals(IncompleteLearningAction.ResumeRequested("one"), actions.last())
        compose.onNodeWithTag("editorial-page-back").assertIsDisplayed().performClick()
        assertEquals(IncompleteLearningAction.ReturnHomeRequested, actions.last())
    }
    @Test fun largeFontIncompleteCardKeepsResumeReachableWithoutLosingTitle() {
        val actions = mutableListOf<IncompleteLearningAction>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                RlTheme { IncompleteLearningScreen(IncompleteLearningUiState(entries = entries(), loading = false), actions::add) }
            }
        }
        compose.onNodeWithText(title).assertExists()
        val back = compose.onNodeWithTag("editorial-page-back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("incomplete-learning-resume-two").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(IncompleteLearningAction.ResumeRequested("two"), actions.last())
        assertEquals(back, compose.onNodeWithTag("editorial-page-back").fetchSemanticsNode().boundsInRoot)
        capture("layout-incomplete-double-font.png")
    }
    @Test fun learningExitAndPreviousStepKeepDistinctCallbacksWithSameBackStyle() {
        var exits = 0
        var previous = 0
        compose.setContent { RlTheme {
            LearningHeading("第 1 章 · 基础讲解", onBack = { previous++ }, onExit = { exits++ }, showPreviousInHeader = true)
        } }
        compose.onNodeWithTag("learning-header-exit").assertIsDisplayed().performClick()
        assertEquals(1, exits)
        assertEquals(0, previous)
        compose.onNodeWithText("返回上一步").performClick()
        assertEquals(1, previous)
    }
}
