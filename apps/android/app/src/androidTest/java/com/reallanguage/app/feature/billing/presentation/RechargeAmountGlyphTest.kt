package com.reallanguage.app.feature.billing.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.billing.domain.PaymentMethod
import com.reallanguage.app.feature.billing.domain.RechargeOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Public preview amounts only. No ViewModel, repository, account, order or payment call. */
class RechargeAmountGlyphTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun everyRecommendedAmountIncludesBothFractionalDigitsAndSelectsItsExactFenValue() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
        val amounts = listOf(1_000L, 3_000L, 5_000L, 10_000L)
        val captions = listOf("轻量体验", "日常练习", "稳定积累", "长期使用")
        val state = RechargeUiState(
            originId = "isolated-amount-visibility",
            balanceAmountFen = 3_680,
            recommendedOptions = amounts.zip(captions) { amount, caption -> RechargeOption(amount, caption) },
            selectedAmountFen = 3_000,
            selectedMethod = PaymentMethod.WECHAT_PAY,
            loading = false,
        )
        val actions = mutableListOf<RechargeAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1f)) {
                RlTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize(915.5.dp, 429.5.dp).clipToBounds().testTag(VIEWPORT)) {
                            RechargeScreen(state, actions::add)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode()
        assertEquals(1831, viewport.layoutInfo.width)
        assertEquals(859, viewport.layoutInfo.height)
        val viewportCoordinates = viewport.layoutInfo.coordinates
        val actualClip = viewportCoordinates.findRootCoordinates().localBoundingBoxOf(viewportCoordinates, clipBounds = true)
        assertEquals("Require the supplied screenshot's actual viewport", 1831f, actualClip.width, 1f)
        assertEquals("Require the supplied screenshot's actual viewport", 859f, actualClip.height, 1f)
        // Inspect the visibly clipped largest amount first, then the other public options.
        amounts.asReversed().forEach { amount ->
            val label = "￥${amount / 100}.${(amount % 100).toString().padStart(2, '0')}"
            val card = compose.onNodeWithTag("recharge-amount-$amount").assertIsDisplayed()
            val text = compose.onNodeWithText(label, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(1, layouts.size)
            val layout = layouts.single()
            assertEquals("The rendered source must equal the public integer-fen amount", label, layout.layoutInput.text.text)
            assertFalse("Money digits must not be discarded", layout.multiParagraph.didExceedMaxLines)
            assertEquals("The last fractional digit must still be in the layout", label.length, layout.getLineEnd(layout.lineCount - 1))
            repeat(layout.lineCount) { assertFalse("Money digits must not be ellipsized", layout.isLineEllipsized(it)) }
            val coordinates = text.fetchSemanticsNode().layoutInfo.coordinates
            val origin = coordinates.localToRoot(Offset.Zero)
            val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
                .intersect(card.fetchSemanticsNode().boundsInRoot).intersect(viewport.boundsInRoot)
            val glyphs = label.indices.map { layout.getBoundingBox(it).translate(origin) }
            assertTrue("All digits of $label must fit their real text/card/viewport: clip=$clip glyphs=$glyphs",
                clip.width > 0 && clip.height > 0 && glyphs.all { fits(it, clip) })
        }
        assertEquals(emptyList<RechargeAction>(), actions)
        compose.onNodeWithText("￥100.00").assertIsEnabled().performTouchInput { click() }
        assertEquals(listOf(RechargeAction.RecommendedAmountSelected(10_000)), actions)
        // Only selection was exercised: never press Submit or launch a payment provider.
        assertFalse(actions.contains(RechargeAction.SubmitRequested))
    }

    private companion object {
        const val VIEWPORT = "recharge-amount-glyph-viewport"
        fun fits(glyph: Rect, clip: Rect) = glyph.left >= clip.left - 1f && glyph.right <= clip.right + 1f &&
            glyph.top >= clip.top - 1f && glyph.bottom <= clip.bottom + 1f
    }
}
