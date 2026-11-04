package com.reallanguage.app.feature.formation.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Same 1846x852/d320/font1 reference content; no DB, identity, HTTP, AI or image alteration. */
class LearningTargetCandidatePaperReadabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val first = LearningTarget("t1", "u1", "work up the courage", "鼓起勇气", LearningTargetType.WORD,
        candidateExample = CandidateExample("It took him a while to work up the courage to apologize.", "他花了一会儿才鼓起勇气道歉。"))
    private val second = LearningTarget("t2", "u1", "explain", "解释；讲清楚", LearningTargetType.FIXED_EXPRESSION,
        candidateExample = CandidateExample("She explained the rule in a simple way.", "她用简单的方式解释了规则。"))
    private val third = LearningTarget("t3", "u1", "work up the courage to + 动词", "鼓起勇气做某事", LearningTargetType.PATTERN,
        structureParts = listOf("work up the courage to", "动词"), relatedVocabulary = listOf("courage"),
        candidateExample = CandidateExample("He worked up the courage to apply for the scholarship.", "他鼓起勇气申请了奖学金。"))
    private val actions = mutableListOf<LearningTargetAction>()

    @Before fun mirrorMainWindow() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test fun referenceInitialWordAndExpressionExamplesAreWholeWithoutAnyScrolling() {
        render()
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "learning-target-three-card-regression.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        val footer = compose.onNodeWithText("继续").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        // The original reference shows both complete bilingual examples immediately.
        // Deliberately do not performScrollTo: reachability after scrolling is a separate contract.
        listOf(first, second).forEach { target ->
            val example = requireNotNull(target.candidateExample)
            listOf(target.explanationZh, example.textEn, example.textZh).forEach { text ->
                assertWholeParagraphOnItsPaper(target.targetId, text)
            }
        }
        assertEquals(footer, compose.onNodeWithText("继续").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("全部取消").assertIsDisplayed()
        compose.onNodeWithText("返回").assertIsDisplayed()
        assertTrue("Initial reading must not require an interaction or selection change", actions.isEmpty())
    }

    @Test fun referenceInitialPatternExampleIncludesItsWholeTranslationBeforeAnyScrolling() {
        render()
        compose.waitForIdle()
        val footer = compose.onNodeWithText("继续").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val example = requireNotNull(third.candidateExample)
        // Extra structure/related words may scroll; the reference bilingual example may not
        // start half-clipped merely because those additional business fields are present.
        listOf(third.explanationZh, example.textEn, example.textZh).forEach { text ->
            assertWholeParagraphOnItsPaper(third.targetId, text)
        }
        assertEquals(footer, compose.onNodeWithText("继续").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("全部取消").assertIsDisplayed()
        compose.onNodeWithText("返回").assertIsDisplayed()
        assertTrue("Initial reading must not require an interaction or selection change", actions.isEmpty())
    }

    @Test fun normalBilingualExamplesRemainInsideTheExistingPaperNotItsTornEdgeOrShadow() {
        render()
        val footer = compose.onNodeWithText("继续").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        listOf(first, second).forEach { target ->
            val example = requireNotNull(target.candidateExample)
            listOf(target.explanationZh, example.textEn, example.textZh).forEach { text ->
                assertWholeParagraphOnItsPaper(target.targetId, text)
            }
        }
        assertEquals(footer, compose.onNodeWithText("继续").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
    }

    @Test fun patternExtraFieldsCanBeReadWholeOnPaperAndScrolledBackWithoutMovingTheFooter() {
        render()
        val footer = compose.onNodeWithText("继续").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val example = requireNotNull(third.candidateExample)
        val texts = listOf(third.explanationZh, example.textEn, example.textZh,
            "结构：work up the courage to + 动词", "相关生词：courage")
        (texts + texts.reversed()).forEach { text ->
            assertWholeParagraphOnItsPaper("t3", text)
            assertEquals("Local content reading cannot displace or cover the next action", footer,
                compose.onNodeWithText("继续").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        }
        compose.onNodeWithText("返回").assertIsDisplayed().performClick()
        assertEquals(listOf(LearningTargetAction.BackRequested), actions)
    }

    private fun assertWholeParagraphOnItsPaper(id: String, text: String) {
        val interaction = cardText(id, text)
        val results = mutableListOf<TextLayoutResult>()
        interaction.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        val layout = results.single()
        assertFalse("Scrolling cannot substitute for deleted or ellipsized text", layout.multiParagraph.didExceedMaxLines)
        val renderedText = if (listOf(first, second, third).single { it.targetId == id }.candidateExample?.textEn == text) "例  $text" else text
        assertEquals(renderedText.length, layout.getLineEnd(layout.lineCount - 1))
        val coordinates = interaction.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
        val card = compose.onNodeWithTag("learning_target_$id", useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
        val cardOrigin = card.localToRoot(Offset.Zero)
        // Existing rl_torn_note is 1700x630, including outer background/shadow.
        // Keep actual glyphs in its conservative paper rectangle (45,90)..(1655,540),
        // not just the full Image composable bounds. No generated substitute image.
        val paperLeft = cardOrigin.x + card.size.width * (45f / 1700f)
        val paperRight = cardOrigin.x + card.size.width * (1655f / 1700f)
        val paperTop = cardOrigin.y + card.size.height * (90f / 630f)
        val paperBottom = cardOrigin.y + card.size.height * (540f / 630f)
        for (line in 0 until layout.lineCount) {
            val top = origin.y + layout.getLineTop(line)
            val bottom = origin.y + layout.getLineBottom(line)
            val left = origin.x + layout.getLineLeft(line)
            val right = origin.x + layout.getLineRight(line)
            assertTrue("[$text] line $line must be whole inside its actual paper: " +
                "line=($left,$top)..($right,$bottom), paper=($paperLeft,$paperTop)..($paperRight,$paperBottom), clip=$clip",
                top >= maxOf(paperTop, clip.top) - 1f && bottom <= minOf(paperBottom, clip.bottom) + 1f &&
                    left >= maxOf(paperLeft, clip.left) - 1f && right <= minOf(paperRight, clip.right) + 1f)
        }
    }

    private fun cardText(id: String, text: String): SemanticsNodeInteraction {
        val target = listOf(first, second, third).single { it.targetId == id }
        val suffix = when (text) {
            target.explanationZh -> "explanation"
            target.candidateExample?.textEn -> "example_en"
            target.candidateExample?.textZh -> "example_zh"
            "结构：${target.structureParts.joinToString(" + ")}" -> "structure"
            "相关生词：${target.relatedVocabulary.joinToString("、")}" -> "related"
            else -> error("Unknown target detail")
        }
        return compose.onNodeWithTag("learning_target_${id}_$suffix", useUnmergedTree = true)
    }

    private fun render() {
        val state = LearningTargetUiState(
            chapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "但最后还是鼓起勇气把想法讲清楚了。", true,
                listOf(EnglishCandidate("e1", "But in the end, I worked up the courage to explain my idea clearly.")))))),
            selectedCandidateIds = mapOf("u1" to "e1"), targets = listOf(first, second, third), selectedTargetIds = setOf("t1", "t2", "t3"),
            queryableExpressions = emptyList(),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                RlTheme { Box(Modifier.requiredSize(923.dp, 426.dp)) { LearningTargetScreen(state, { actions += it }) } }
            }
        }
    }
}
