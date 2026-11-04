package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.platform.testTag
import org.junit.Assert.assertTrue
import org.junit.Before
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.feature.learning.domain.ChapterArchiveSummary
import com.reallanguage.app.feature.learning.domain.ChapterResultCategory
import com.reallanguage.app.feature.learning.domain.ChapterResultSummaryItem
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class ChapterResultScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    @Before fun landscape() {
        composeRule.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitUntil(5_000) {
            composeRule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun referenceViewportKeepsResultPanelAtTheOriginalPaperMargin() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.size(836.dp, 470.5.dp).testTag("result-reference")) {
                    RlTheme { ChapterResultScreen(ChapterResultUiState("c1", "cmd", chapterNumber = 1,
                        summary = ChapterArchiveSummary("c1", listOf(ChapterResultSummaryItem(ChapterResultCategory.SENTENCE, "整句会写", 2, 2)), true)), {}) }
                }
            }
        }
        val root = composeRule.onNodeWithTag("result-reference").getUnclippedBoundsInRoot()
        val panel = composeRule.onNodeWithTag("chapter-result-summary").getUnclippedBoundsInRoot()
        val width = (root.right - root.left).value
        assertTrue("The summary starts too far left of the original paper margin", (panel.left - root.left).value / width in .195f.. .205f)
        assertTrue("The summary is wider than the original paper content", (panel.right - root.left).value / width in .885f.. .895f)
        val reinforcement = composeRule.onNodeWithTag("chapter-result-reinforcement-paper").getUnclippedBoundsInRoot()
        assertTrue("The reference-width archive must keep its reinforcement paper in the right column: panel=$panel paper=$reinforcement",
            reinforcement.left > panel.left + (panel.right - panel.left) * .45f)
        assertTrue("The reinforcement paper must start beside the result ledger, not below the first screen",
            reinforcement.top <= panel.top + 2.dp)
        composeRule.onNodeWithTag("chapter-result-reinforcement-paper").assertIsDisplayed()
        val next = composeRule.onNodeWithText("进入下一章").getUnclippedBoundsInRoot()
        assertTrue("The primary action is wider than the original button", (next.left - root.left).value / width in .70f.. .72f)
    }

    @Test fun shortScreenCanReadEveryResultWithoutLosingTheNextButton() {
        val actions = mutableListOf<ChapterResultAction>()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                Box(Modifier.size(800.dp, 320.dp).testTag("short-result")) {
                    RlTheme { ChapterResultScreen(ChapterResultUiState("c1", "cmd", summary = ChapterArchiveSummary(
                        "c1", (1..8).map { ChapterResultSummaryItem(ChapterResultCategory.SENTENCE, "第${it}项较长的真实学习结果", 1, 2) }, true,
                    )), actions::add) }
                }
            }
        }
        val footerBefore = composeRule.onNodeWithTag("chapter-result-footer").getUnclippedBoundsInRoot()
        composeRule.onNodeWithText("第8项较长的真实学习结果").performScrollTo().assertIsDisplayed()
        val footerAfter = composeRule.onNodeWithTag("chapter-result-footer").getUnclippedBoundsInRoot()
        assertTrue("The next/later footer must not move when results scroll", footerBefore == footerAfter)
        val button = composeRule.onNodeWithText("进入下一章").getUnclippedBoundsInRoot()
        val root = composeRule.onNodeWithTag("short-result").getUnclippedBoundsInRoot()
        assertTrue(button.bottom <= root.bottom)
        composeRule.onNodeWithText("进入下一章").assertIsDisplayed().performClick()
        assertTrue(actions == listOf(ChapterResultAction.EnterNextChapterRequested))
    }

    @Test
    fun showsArchivedSummaryAndDispatchesNextChapter() {
        val actions = mutableListOf<ChapterResultAction>()
        val state = ChapterResultUiState(
            chapterId = "c1",
            commandId = "cmd",
            chapterNumber = 1,
            summary = ChapterArchiveSummary(
                chapterId = "c1",
                items = listOf(
                    ChapterResultSummaryItem(ChapterResultCategory.SENTENCE, "整句会写", 2, 2),
                    ChapterResultSummaryItem(ChapterResultCategory.PATTERN, "句式已练习", 1, 1),
                ),
                hasNextChapter = true,
            ),
        )

        composeRule.setContent { RlTheme { ChapterResultScreen(state, actions::add) } }

        composeRule.onNodeWithTag("chapter-result-summary").assertIsDisplayed()
        composeRule.onNodeWithText("本章已完成并归档").assertIsDisplayed()
        composeRule.onNodeWithText("整句会写").assertIsDisplayed()
        composeRule.onNodeWithText("进入下一章").assertIsDisplayed().performClick()
        assert(actions == listOf(ChapterResultAction.EnterNextChapterRequested))
    }

    @Test
    fun completedLastChapterKeepsTheSealBesideCompletionAndReturnAlignedWithCorrection() {
        val actions = mutableListOf<ChapterResultAction>()
        val state = ChapterResultUiState(
            chapterId = "c1",
            commandId = "cmd",
            chapterNumber = 1,
            summary = ChapterArchiveSummary(
                chapterId = "c1",
                items = listOf(
                    ChapterResultSummaryItem(ChapterResultCategory.SENTENCE, "整句能力", 3, 3),
                    ChapterResultSummaryItem(ChapterResultCategory.MEANING, "词义与固定搭配", 1, 1),
                    ChapterResultSummaryItem(ChapterResultCategory.CORRECTION, "订正", 1, 1),
                ),
                hasNextChapter = false,
            ),
        )

        val capture = InstrumentationRegistry.getArguments().getString("chapterResultCapture") == "after"
        composeRule.setContent {
            if (capture) {
                Box(Modifier.fillMaxSize().testTag("completed-result-reference")) {
                    RlTheme { ChapterResultScreen(state, actions::add) }
                }
            } else {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    Box(Modifier.size(836.dp, 470.5.dp).testTag("completed-result-reference")) {
                        RlTheme { ChapterResultScreen(state, actions::add) }
                    }
                }
            }
        }

        composeRule.onNodeWithText("完成结果已保存在本机。").assertDoesNotExist()
        val completionPaper = composeRule.onNodeWithTag("chapter-result-reinforcement-paper")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val ribbon = composeRule.onNodeWithTag("chapter-result-complete-ribbon")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val seal = composeRule.onNodeWithTag("chapter-result-complete-seal")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("The completion seal must sit directly after the completion ribbon", seal.left >= ribbon.right)
        val sealCenterY = (seal.top.value + seal.bottom.value) / 2f
        val ribbonCenterY = (ribbon.top.value + ribbon.bottom.value) / 2f
        assertTrue("The seal and completion ribbon must share one visual row",
            abs(sealCenterY - ribbonCenterY) <= 8f)
        assertTrue("The seal must stay inside the completion paper", seal.right <= completionPaper.right && seal.bottom <= completionPaper.bottom)

        val correction = composeRule.onNodeWithTag("chapter-result-ledger-CORRECTION")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val returnHome = composeRule.onNodeWithText("返回首页").assertIsDisplayed().getUnclippedBoundsInRoot()
        val returnCenterY = (returnHome.top.value + returnHome.bottom.value) / 2f
        val correctionCenterY = (correction.top.value + correction.bottom.value) / 2f
        assertTrue("Return home must align with the correction row instead of dropping to the page bottom",
            abs(returnCenterY - correctionCenterY) <= 12f)
        if (capture) {
            val output = File(requireNotNull(composeRule.activity.getExternalFilesDir(null)), "chapter-result-final.png")
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
        }
        composeRule.onNodeWithText("返回首页").performClick()
        assertTrue(actions == listOf(ChapterResultAction.ReturnHomeRequested))
    }
}
