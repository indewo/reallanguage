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

class LearningTargetRelatedVocabularyScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val item = RelatedVocabularyItem("r-tea", "u1", "tea", "茶", LearningTargetType.WORD,
        SourceEvidence(1, 8, 11, "tea", "tea", listOf("tea")))
    private val pattern = LearningTarget("pattern", "u1", "drink + noun", "喝某物", LearningTargetType.PATTERN,
        structureParts = listOf("drink", "noun"), relatedVocabulary = listOf("tea"),
        relatedVocabularyItems = listOf(item), relatedVocabularyItemRefs = listOf(item.itemRef))
    private val word = LearningTarget("word", "u1", "tea", "茶", LearningTargetType.WORD, sourceEvidence = item.sourceEvidence)
    private val effects = mutableListOf<LearningTargetEffect>()
    private var requests = 0

    @Before fun productionWindow() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test fun cancellingVocabularyKeepsItsCurrentMeaningInsideThePatternWithoutSelectingAnExtraItem() {
        val vm = viewModel(listOf(pattern, word))
        render(vm)
        show("pattern", "tea：茶")
        compose.onNodeWithTag("learning_target_word").performScrollTo().performClick()
        assertEquals(setOf("pattern"), vm.uiState.value.selectedTargetIds)
        show("pattern", "tea：茶")
        assertEquals(2, vm.uiState.value.targets.size)
        compose.onNodeWithText("继续").performClick()
        compose.waitUntil(3_000) { effects.size == 1 }
        val fixed = effects.single() as LearningTargetEffect.OpenFinalConfirmation
        assertEquals(setOf("pattern"), fixed.selectedTargetIds)
        assertEquals(listOf(item), fixed.targets.first().relatedVocabularyItems)
        assertEquals(0, requests)
    }

    @Test fun anotherSentenceCannotBorrowTheFirstMeaningAndLegacyDoesNotInventOne() {
        val other = pattern.copy(targetId = "other", unitId = "u2", display = "serve + noun", explanationZh = "端上某物",
            relatedVocabularyItems = listOf(item.copy(unitId = "u2", senseZh = "茶点")))
        val vm = viewModel(listOf(pattern, other), twoSentences = true)
        render(vm)
        show("pattern", "tea：茶")
        cardText("pattern", "tea：茶点").assertDoesNotExist()
        compose.onNodeWithText("继续").performClick()
        show("other", "tea：茶点")
        compose.onNodeWithText("tea：茶").assertDoesNotExist()
        compose.onNodeWithText("返回").performClick()
        show("pattern", "tea：茶")
        assertEquals(0, vm.uiState.value.focusSentenceIndex)
        assertEquals(0, requests)
    }

    @Test fun legacyRelatedNamesAreRetainedWithAnExplicitMissingMeaningMessage() {
        val vm = viewModel(listOf(pattern.copy(relatedVocabularyItems = null, relatedVocabularyItemRefs = null)))
        render(vm)
        show("pattern", "相关生词：tea")
        show("pattern", "暂未提供关联词的本句词义说明。")
        cardText("pattern", "tea：茶").assertDoesNotExist()
        compose.onNodeWithText("全部取消").performClick()
        show("pattern", "暂未提供关联词的本句词义说明。")
        assertTrue(vm.uiState.value.selectedTargetIds.isEmpty())
        compose.onNodeWithText("返回").performClick()
        compose.waitUntil(3_000) { effects.size == 1 }
        assertEquals(LearningTargetEffect.ReturnToEnglishConfirmation, effects.single())
        assertEquals(0, requests)
    }

    @Test fun longChineseExplanationIsReadableAtDoubleFontAndDoesNotMoveTheFooter() {
        val sense = "指当前句子里饮用的茶，不是额外的学习目标。".repeat(8)
        val vm = viewModel(listOf(pattern.copy(relatedVocabularyItems = listOf(item.copy(senseZh = sense)))))
        render(vm, large = true)
        val footer = compose.onNodeWithText("继续").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val text = "tea：$sense"
        val node = show("pattern", text)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertFalse(layout.multiParagraph.didExceedMaxLines)
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
        assertTrue("The paper must expose a whole line, not just a clipped sliver", clip.width > 0 &&
            (0 until layout.lineCount).any { origin.y + layout.getLineTop(it) >= clip.top - 1 &&
                origin.y + layout.getLineBottom(it) <= clip.bottom + 1 })
        assertEquals(footer, compose.onNodeWithText("继续").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("返回").assertIsDisplayed().performClick()
        compose.waitUntil(3_000) { effects.size == 1 }
        assertEquals(LearningTargetEffect.ReturnToEnglishConfirmation, effects.single())
        assertEquals(0, requests)
    }

    private fun cardText(id: String, text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag("learning_target_$id")), useUnmergedTree = true)
    private fun show(id: String, text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("learning_target_$id").performScrollTo().assertIsDisplayed()
        return cardText(id, text).performScrollTo().assertIsDisplayed()
    }
    private fun viewModel(targets: List<LearningTarget>, twoSentences: Boolean = false) = LearningTargetViewModel(
        listOf(EnglishChapterDraft("c1", listOf(
            EnglishSentenceDraft("u1", "我喝茶。", true, listOf(EnglishCandidate("e1", "I drink tea."))),
        ) + if (twoSentences) listOf(EnglishSentenceDraft("u2", "我端上茶点。", true,
            listOf(EnglishCandidate("e2", "I serve tea.")))) else emptyList())),
        mapOf("u1" to "e1", "u2" to "e2"), targets, emptyList(), LearningTargetGenerator { _, _ ->
            requests++; error("Reading explanations or cancelling must never invoke AI")
        })
    private fun render(vm: LearningTargetViewModel, large: Boolean = false) {
        compose.setContent {
            val state by vm.uiState.collectAsState()
            LaunchedEffect(vm) { vm.effects.collect { effects += it } }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 2f else 1f)) {
                RlTheme { Box(if (large) Modifier.requiredSize(760.dp, 360.dp) else Modifier) {
                    LearningTargetScreen(state, vm::onAction)
                } }
            }
        }
    }
}
