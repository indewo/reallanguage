package com.reallanguage.app.feature.formation.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import org.junit.Rule
import org.junit.Test

class FinalFixScreenTest {
    @get:Rule val composeRule = androidx.compose.ui.test.junit4.v2.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @org.junit.Before fun landscape() {
        composeRule.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
    }

    @Test fun englishEmphasisMarksOnlySelectedTermsInTheirOwnSentenceWithoutChangingText() {
        val first = "I felt calm."
        val second = "I explained my idea clearly."
        val state = FinalFixUiState(
            listOf(EnglishChapterDraft("c1", listOf(
                EnglishSentenceDraft("u1", "我很平静。", true, listOf(EnglishCandidate("e1", first))),
                EnglishSentenceDraft("u2", "我清楚地解释了想法。", true, listOf(EnglishCandidate("e2", second))),
            ))), mapOf("u1" to "e1", "u2" to "e2"),
            listOf(LearningTarget("t1", "u2", "my idea", "我的想法", LearningTargetType.FIXED_EXPRESSION),
                LearningTarget("t2", "u2", "clearly", "清楚地", LearningTargetType.WORD),
                LearningTarget("t3", "u2", "calm", "不属于第一句的目标", LearningTargetType.WORD),
                LearningTarget("t4", "u2", "explain", "没有已确认屈折位置时不猜测", LearningTargetType.WORD)),
        )
        composeRule.setContent { RlTheme { FinalFixScreen(state, {}) } }
        fun layout(text: String): androidx.compose.ui.text.TextLayoutResult {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            composeRule.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        assertTrue("Unselected words must retain ink color", layout(first).layoutInput.style.color == com.reallanguage.app.core.designsystem.RlColor.Ink)
        assertTrue("Another sentence's selection must not bleed into this sentence", layout(first).layoutInput.text.spanStyles.isEmpty())
        val annotated = layout(second).layoutInput.text
        org.junit.Assert.assertEquals(second, annotated.text)
        org.junit.Assert.assertEquals(setOf("my idea", "clearly"), annotated.spanStyles.map {
            second.substring(it.start, it.end)
        }.toSet())
    }

    @Test fun shortScreenWithDoubleFontRemovesHelperCopyAndKeepsEqualActionsReachable() {
        val notice = "确认后将锁定中文来源、英文表达、重点句和学习目标。"
        composeRule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f),
            ) { RlTheme { Box(Modifier.size(640.dp, 360.dp)) {
                FinalFixScreen(FinalFixUiState(
                    listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "长句结尾也必须读全。", true,
                        listOf(EnglishCandidate("e1", "Readable sentence. ".repeat(20))))))),
                    mapOf("u1" to "e1"), (1..12).map { LearningTarget("t$it", "u1", "Target $it", "解释", LearningTargetType.WORD) },
                    fixFailed = true,
                ), {})
            } } }
        }
        composeRule.onAllNodesWithText(notice).assertCountEquals(0)
        composeRule.onAllNodesWithText("不会再弹出第二次确认。").assertCountEquals(0)
        composeRule.onNodeWithText("长句结尾也必须读全。").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Target 12").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("final_fix_back").assertIsDisplayed()
        composeRule.onNodeWithTag("final_fix_confirm").assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(instrumentation.targetContext.filesDir, "final-fix-double-font.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun wideDocumentsMatchReferenceInsets() {
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(2f, 1f),
            ) {
                RlTheme {
                    Box(Modifier.requiredSize(923.dp, 426.dp)) {
                        FinalFixScreen(FinalFixUiState(emptyList(), emptyMap(), emptyList()), {})
                    }
                }
            }
        }
        val summary = composeRule.onNodeWithTag("final_fix_summary").fetchSemanticsNode().boundsInRoot
        val left = composeRule.onNodeWithTag("final_fix_sentences").fetchSemanticsNode().boundsInRoot
        val right = composeRule.onNodeWithTag("final_fix_targets").fetchSemanticsNode().boundsInRoot
        val root = composeRule.onNodeWithTag("final_fix_page").fetchSemanticsNode().boundsInRoot
        assertTrue("Paper starts near 16% of reference width", (left.left - root.left) / root.width in .15f.. .18f)
        assertTrue("Two documents end near 81% of reference width", (right.right - root.left) / root.width in .79f.. .83f)
        assertTrue("Compact summary leaves more height for documents", (summary.top - root.top) / root.height in .18f.. .23f)
    }

    @Test
    fun longSentencesAndFailureRemainUsableOnShortLandscapeScreen() {
        val sentences = (1..10).map {
            EnglishSentenceDraft("u$it", "这是第 $it 句，确认内容必须能够完整查看。", true,
                listOf(EnglishCandidate("e$it", "Sentence $it: " + "Long but readable content. ".repeat(8))))
        }
        val state = FinalFixUiState(
            chapters = listOf(EnglishChapterDraft("c1", sentences)),
            selectedCandidateIds = (1..10).associate { "u$it" to "e$it" },
            selectedTargets = emptyList(), fixFailed = true,
        )
        composeRule.setContent {
            RlTheme { Box(Modifier.size(850.dp, 350.dp)) { FinalFixScreen(state, {}) } }
        }
        composeRule.onNodeWithText("这是第 10 句，确认内容必须能够完整查看。").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("final_fix_confirm").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithText("不会再弹出第二次确认。").assertCountEquals(0)
    }

    @Test
    fun overflowingTargetsCanBeScrolledIntoView() {
        val state = FinalFixUiState(
            chapters = emptyList(), selectedCandidateIds = emptyMap(),
            selectedTargets = (1..30).map {
                LearningTarget("t$it", "u1", "Target $it", "解释", LearningTargetType.WORD)
            },
        )
        composeRule.setContent { RlTheme { FinalFixScreen(state, {}) } }
        val scrollNode = composeRule.onNodeWithTag("final_fix_targets_scroll")
        fun offset() = scrollNode.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        composeRule.onNodeWithTag("final_fix_targets_up").assertDoesNotExist()
        composeRule.onNodeWithTag("final_fix_targets_down").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue("Down arrow must advance the actual document", offset() > 0f)
        composeRule.onNodeWithTag("final_fix_targets_up").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        org.junit.Assert.assertEquals(0f, offset(), 1f)
        composeRule.onNodeWithText("Target 30").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("final_fix_confirm").assertIsDisplayed()
    }

    @Test
    fun showsFinalBilingualContentTargetsAndDirectFixAction() {
        val actions = mutableListOf<FinalFixAction>()
        val state = FinalFixUiState(
            chapters = listOf(
                EnglishChapterDraft(
                    "c1",
                    listOf(
                        EnglishSentenceDraft(
                            "u1", "我想表达得更清楚。", true,
                            listOf(
                                EnglishCandidate(
                                    "e1",
                                    "I want to express myself more clearly.",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            selectedCandidateIds = mapOf("u1" to "e1"),
            selectedTargets = listOf(
                LearningTarget(
                    "t1", "u1", "express oneself", "表达自己",
                    LearningTargetType.PATTERN,
                ),
            ),
        )
        composeRule.setContent { RlTheme { FinalFixScreen(state, actions::add) } }
        composeRule.onAllNodesWithText("确认并固定").assertCountEquals(2)
        listOf(
            "我想表达得更清楚。",
            "I want to express myself more clearly.",
            "express oneself",
        ).forEach { composeRule.onNodeWithText(it).fetchSemanticsNode() }
        composeRule.onNodeWithTag("final_fix_summary").assertIsDisplayed()
        composeRule.onNodeWithTag("final_fix_sentences").assertIsDisplayed()
        composeRule.onNodeWithTag("final_fix_targets").assertIsDisplayed()
        composeRule.onNodeWithTag("final_fix_confirm").performClick()
        assert(actions.contains(FinalFixAction.ConfirmRequested))
    }

    @Test
    fun backAndConfirmActionsShareExactlyTheSameSizeAndBaseline() {
        composeRule.setContent {
            RlTheme {
                Box(Modifier.size(850.dp, 430.dp)) {
                    FinalFixScreen(FinalFixUiState(emptyList(), emptyMap(), emptyList()), {})
                }
            }
        }
        val back = composeRule.onNodeWithTag("final_fix_back").fetchSemanticsNode().boundsInRoot
        val confirm = composeRule.onNodeWithTag("final_fix_confirm").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(back.width, confirm.width, 1f)
        org.junit.Assert.assertEquals(back.height, confirm.height, .5f)
        org.junit.Assert.assertEquals(back.top, confirm.top, .5f)
        org.junit.Assert.assertEquals(back.bottom, confirm.bottom, .5f)
    }
}
