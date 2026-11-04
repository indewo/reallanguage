package com.reallanguage.app.feature.formation.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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

class LearningTargetCandidateExampleScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val examples = listOf(CandidateExample("She drinks tea at home.", "她在家喝茶。"),
        CandidateExample("We drink water after running.", "我们跑步后喝水。"),
        CandidateExample("They take a walk after lunch.", "他们午饭后散步。"))
    private val targets = listOf(
        LearningTarget("tea", "u1", "tea", "茶", LearningTargetType.WORD, candidateExample = examples[0]),
        LearningTarget("drink", "u1", "drink", "喝", LearningTargetType.WORD, candidateExample = examples[1]),
        LearningTarget("walk", "u2", "walk", "散步", LearningTargetType.WORD, candidateExample = examples[2]),
    )
    private var calls = 0
    private val effects = mutableListOf<LearningTargetEffect>()

    @Before fun mirrorMainWindow() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test fun eachCurrentTargetOwnsItsBilingualExampleAndChangingSentenceDoesNotMixCards() {
        val vm = viewModel(targets)
        render(vm)
        listOf("tea", "drink").forEachIndexed { index, id ->
            cardText(id, examples[index].textEn).assertIsDisplayed()
            cardText(id, examples[index].textZh).assertIsDisplayed()
            val meaning = cardText(id, targets[index].explanationZh).fetchSemanticsNode().boundsInRoot
            val example = cardText(id, examples[index].textEn).fetchSemanticsNode().boundsInRoot
            assertTrue("The candidate illustration belongs below this target's meaning", example.top >= meaning.bottom)
            cardText(id, examples[1 - index].textEn).assertDoesNotExist()
        }
        compose.onNodeWithText(examples[2].textEn).assertDoesNotExist()
        compose.onNodeWithText("继续").performClick()
        cardText("walk", examples[2].textEn).assertIsDisplayed()
        cardText("walk", examples[2].textZh).assertIsDisplayed()
        compose.onNodeWithText(examples[0].textEn).assertDoesNotExist()
        compose.onNodeWithText("返回").performClick()
        cardText("tea", examples[0].textEn).assertIsDisplayed()
        assertEquals(targets, vm.uiState.value.targets)
        assertEquals(0, calls)
    }

    @Test fun legacyCandidateExplicitlySaysExampleWasNotProvidedAndDoesNotCopyTheSourceSentence() {
        val vm = viewModel(targets.take(1).map { it.copy(candidateExample = null) })
        render(vm)
        cardText("tea", "暂未提供候选例句。").assertIsDisplayed()
        cardText("tea", "I drink tea.").assertDoesNotExist()
        compose.onNodeWithText("继续").assertIsEnabled()
        compose.onNodeWithText("全部取消").performClick()
        assertTrue(vm.uiState.value.selectedTargetIds.isEmpty())
        assertNull(vm.uiState.value.targets.single().candidateExample)
        compose.onNodeWithText("返回").performClick()
        compose.waitUntil(3_000) { effects.size == 1 }
        assertEquals(LearningTargetEffect.ReturnToEnglishConfirmation, effects.single())
        assertEquals(0, calls)
    }

    @Test fun longCandidateExampleAndTranslationCanScrollAtDoubleFontWithoutLosingSelectionOrReturn() {
        val english = "She drinks tea at home after a long day. ".repeat(10).trim()
        val chinese = "忙碌一天以后，她在家喝茶。".repeat(10)
        val example = CandidateExample(english, chinese)
        val vm = viewModel(targets.take(1).map { it.copy(candidateExample = example) })
        render(vm, large = true)
        val footerBefore = compose.onNodeWithText("继续").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        listOf(english, chinese).forEach { text ->
            // A card below the long accessible header is reached in the outer
            // list first; its unmerged text then uses the local paper scroll.
            compose.onNodeWithTag("learning_target_tea").performScrollTo().assertIsDisplayed()
            val node = cardText("tea", text)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            assertFalse(layout.multiParagraph.didExceedMaxLines)
            assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue(layout.multiParagraph.height <= layout.size.height + 1f)
            node.performScrollTo().assertIsDisplayed()
            assertWholeVisibleLine(node, layout)
        }
        compose.onNodeWithText("全部取消").performClick()
        assertTrue(vm.uiState.value.selectedTargetIds.isEmpty())
        assertEquals(example, vm.uiState.value.targets.single().candidateExample)
        assertEquals(footerBefore, compose.onNodeWithText("继续").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("返回").assertIsDisplayed().performClick()
        compose.waitUntil(3_000) { effects.size == 1 }
        assertEquals(LearningTargetEffect.ReturnToEnglishConfirmation, effects.single())
        assertEquals(0, calls)
    }

    private fun cardText(target: String, text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag("learning_target_$target")), useUnmergedTree = true)

    private fun assertWholeVisibleLine(interaction: SemanticsNodeInteraction, layout: TextLayoutResult) {
        val node = interaction.fetchSemanticsNode()
        val coordinates = node.layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
        var parent = node.parent
        while (parent != null && !parent.config.contains(SemanticsProperties.VerticalScrollAxisRange)) parent = parent.parent
        val viewport = requireNotNull(parent) { "The paper needs a real finite scroll viewport" }.boundsInRoot
        val tallestLine = (0 until layout.lineCount).maxOf { layout.getLineBottom(it) - layout.getLineTop(it) }
        assertTrue("Two-times text requires at least one whole line of paper: viewport=$viewport line=$tallestLine", viewport.height + 1f >= tallestLine)
        assertTrue("A zero-area or clipped half-line is not readable: origin=$origin clip=$clip layout=${layout.size}",
            clip.width > 0f && (0 until layout.lineCount).any { line ->
                origin.y + layout.getLineTop(line) >= clip.top - 1f &&
                    origin.y + layout.getLineBottom(line) <= clip.bottom + 1f &&
                    origin.x + layout.getLineLeft(line) >= clip.left - 1f &&
                    origin.x + layout.getLineRight(line) <= clip.right + 1f
            })
    }
    private fun viewModel(values: List<LearningTarget>) = LearningTargetViewModel(
        listOf(EnglishChapterDraft("c1", listOf(
            EnglishSentenceDraft("u1", "我喝茶。", true, listOf(EnglishCandidate("e1", "I drink tea."))),
            EnglishSentenceDraft("u2", "我散步。", true, listOf(EnglishCandidate("e2", "I take a walk.")))))),
        mapOf("u1" to "e1", "u2" to "e2"), values, emptyList(), LearningTargetGenerator { _, _ ->
            calls++; error("Showing, clearing and returning to saved examples must not generate")
        })
    private fun render(vm: LearningTargetViewModel, large: Boolean = false) {
        compose.setContent {
            val state by vm.uiState.collectAsState()
            LaunchedEffect(vm) { vm.effects.collect { effects += it } }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else 1f)) {
                RlTheme {
                    Box(if (large) Modifier.requiredSize(760.dp, 360.dp) else Modifier) {
                        LearningTargetScreen(state, vm::onAction)
                    }
                }
            }
        }
    }
}
