package com.reallanguage.app.feature.formation.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.formation.domain.CandidateExample
import com.reallanguage.app.feature.formation.domain.EnglishCandidate
import com.reallanguage.app.feature.formation.domain.EnglishChapterDraft
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Reference 20 has three complete candidate cards above the fixed action strip. */
class LearningTargetReferenceCardFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun productionWindow() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    @Test fun allThreeReferenceExampleTranslationsFitAboveFooterWithoutShrinkingText() {
        val targets = listOf(
            LearningTarget("t1", "u1", "work up the courage", "鼓起勇气", LearningTargetType.WORD,
                candidateExample = CandidateExample("It took him a while to work up the courage to apologize.", "他花了一会儿才鼓起勇气道歉。")),
            LearningTarget("t2", "u1", "explain", "解释；讲清楚", LearningTargetType.FIXED_EXPRESSION,
                candidateExample = CandidateExample("She explained the rule in a simple way.", "她用简单的方式解释了规则。")),
            LearningTarget("t3", "u1", "work up the courage to + 动词", "鼓起勇气做某事", LearningTargetType.PATTERN,
                candidateExample = CandidateExample("He worked up the courage to apply for the scholarship.", "他鼓起勇气申请了奖学金。")),
        )
        val state = LearningTargetUiState(
            chapters = listOf(EnglishChapterDraft("c1", listOf(EnglishSentenceDraft("u1", "但最后还是鼓起勇气把想法讲清楚了。", true,
                listOf(EnglishCandidate("e1", "But in the end, I worked up the courage to explain my idea clearly.")))))),
            selectedCandidateIds = mapOf("u1" to "e1"), targets = targets,
            selectedTargetIds = targets.map { it.targetId }.toSet(), queryableExpressions = emptyList(),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                RlTheme { Box(Modifier.requiredSize(923.dp, 426.dp)) { LearningTargetScreen(state, {}) } }
            }
        }
        val footer = compose.onNodeWithTag("learning_targets_footer").fetchSemanticsNode().boundsInRoot
        targets.forEach { target ->
            listOf("en", "zh").forEach { language ->
                val node = compose.onNodeWithTag("learning_target_${target.targetId}_example_$language", useUnmergedTree = true)
                node.assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertTrue("Keep example text at the established 12sp floor", layout.layoutInput.style.fontSize.value >= 12f)
                assertFalse("No example line may be omitted", layout.multiParagraph.didExceedMaxLines)
                assertTrue("Full line height must fit", layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1f)
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertTrue("${target.targetId}/$language must not meet the fixed footer", bounds.bottom <= footer.top + 1f)
                assertTrue("The footer must not clip the measured text", bounds.height + 1f >= layout.size.height)
            }
        }
        val fixedCard = compose.onNodeWithTag("learning_target_t2").fetchSemanticsNode().boundsInRoot
        val fixedDisplay = compose.onNodeWithTag("learning_target_t2_display", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val fixedExample = compose.onNodeWithTag("learning_target_t2_example_en", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Fixed-expression body must start beyond the brass eyelet", fixedDisplay.left >= fixedCard.left + 49f * 2f - 1f)
        assertTrue("Example text and its marker must not touch the knot", fixedExample.left >= fixedDisplay.left + 14f * 2f - 1f)
        listOf("全部取消", "返回", "继续").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }
}
