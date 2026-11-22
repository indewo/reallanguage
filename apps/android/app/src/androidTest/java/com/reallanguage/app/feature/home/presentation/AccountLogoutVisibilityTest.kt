package com.reallanguage.app.feature.home.presentation

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
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
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.R
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.AccountSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Isolated rendered states and real touch, not a real session or logout request. */
class AccountLogoutVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun focusedLandscapeHost() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            configureFocusedLearningWindow(it.window)
        }
    }

    @Test fun baselineLogoutLabelIsCompleteAndItsButtonDispatchesLogout() {
        verifyLogout(short = false, busy = false)
    }

    @Test fun shortDoubleFontLogoutLabelIsCompleteAfterExistingLedgerScroll() {
        verifyLogout(short = true, busy = false)
    }

    @Test fun baselineLoggingOutLabelIsCompleteAndRepeatedTouchDoesNotSubmit() {
        verifyLogout(short = false, busy = true)
    }

    @Test fun shortDoubleFontLoggingOutLabelIsCompleteAndRepeatedTouchDoesNotSubmit() {
        verifyLogout(short = true, busy = true)
    }

    @Test fun longUnbrokenEmailAndVerifiedTabRemainWholeAtDoubleFont() {
        val email = "a".repeat(96) + "@example.com"
        val actions = mutableListOf<AccountAction>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
                RlTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredSize(640.dp, 360.dp).clipToBounds().testTag(VIEWPORT)) {
                            AccountScreen(AccountUiState(account = AccountSnapshot(email), loading = false), actions::add)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val back = compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        val backBounds = back.fetchSemanticsNode().boundsInRoot

        compose.onNodeWithText("邮箱已验证", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        capture("account-verified-double-font.png")
        assertWholeTextInRegion("邮箱已验证", "account-passport-artwork")

        val emailNode = compose.onNodeWithText(email, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val emailLayouts = mutableListOf<TextLayoutResult>()
        emailNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(emailLayouts) }
        assertEquals(email, emailLayouts.single().layoutInput.text.text)
        assertEquals("Long addresses remain on one horizontally scrollable line", 1, emailLayouts.single().lineCount)
        val emailScroll = compose.onNodeWithTag("account-email-scroll")
        val horizontal = emailScroll.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("The complete long address must be reachable", horizontal.maxValue() > 0f)
        emailScroll.performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { horizontal.value() > 0f }
        val labelBottom = compose.onNodeWithText("邮箱", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("The email value must start below its label", emailNode.fetchSemanticsNode().boundsInRoot.top >= labelBottom)
        back.assertIsDisplayed()
        assertEquals("The fixed account header must not scroll away", backBounds, back.fetchSemanticsNode().boundsInRoot)
        capture("account-long-email-double-font.png")
        assertEquals("Reading and scrolling account details must not dispatch actions", emptyList<AccountAction>(), actions)
    }

    private fun verifyLogout(short: Boolean, busy: Boolean) {
        val width = if (short) 1280 else 1831
        val height = if (short) 720 else 859
        val scale = if (short) 2f else 1f
        val actions = mutableListOf<AccountAction>()
        var actualDensity: Density? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, scale)) {
                val density = LocalDensity.current
                SideEffect { actualDensity = density }
                RlTheme {
                    Box(Modifier.fillMaxSize()) {
                        // Constrain the actual viewport, without adding any test-side scroll.
                        Box(Modifier.requiredSize((width / 2f).dp, (height / 2f).dp).clipToBounds().testTag(VIEWPORT)) {
                            AccountScreen(
                                AccountUiState(
                                    account = AccountSnapshot("learner@example.com"),
                                    loading = false,
                                    loggingOut = busy,
                                ),
                                actions::add,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2f, requireNotNull(actualDensity).density, .001f)
            assertEquals(scale, requireNotNull(actualDensity).fontScale, .001f)
        }
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode()
        assertEquals(width, viewport.layoutInfo.width)
        assertEquals(height, viewport.layoutInfo.height)
        val viewportCoordinates = viewport.layoutInfo.coordinates
        val actualClip = viewportCoordinates.findRootCoordinates()
            .localBoundingBoxOf(viewportCoordinates, clipBounds = true)
        assertEquals("The host must not silently shrink the width", width.toFloat(), actualClip.width, 1f)
        assertEquals("The host must not silently shrink the height", height.toFloat(), actualClip.height, 1f)

        val button = compose.onNodeWithTag("account-logout")
        if (short) button.performScrollTo()
        button.assertIsDisplayed()
        if (busy) button.assertIsNotEnabled() else button.assertIsEnabled().assertHasClickAction()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(if (busy) R.string.account_logging_out else R.string.account_logout)
        assertWholeLabel(label, button)
        assertEquals("Displaying or scrolling account details must not submit logout", emptyList<AccountAction>(), actions)
        button.performTouchInput { click() }
        if (busy) {
            button.performTouchInput { click() }
            assertEquals("Busy logout must remain disabled after repeated physical touches", emptyList<AccountAction>(), actions)
        } else {
            assertEquals(listOf(AccountAction.LogoutRequested), actions)
        }
    }

    private fun assertWholeLabel(label: String, button: SemanticsNodeInteraction) {
        val text = compose.onNodeWithText(label, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue("Inspect the actual production Text paragraph", it(layouts))
        }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertEquals(label, layout.layoutInput.text.text)
        assertFalse("The full logout label must remain present", layout.multiParagraph.didExceedMaxLines)
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { assertFalse(layout.isLineEllipsized(it)) }
        val coordinates = text.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
            .intersect(button.fetchSemanticsNode().boundsInRoot)
            .intersect(compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot)
        val glyphs = label.indices.filter { !label[it].isWhitespace() }
            .map { layout.getBoundingBox(it).translate(origin) }
        assertTrue("All logout glyphs must fit the real button and clipped ledger: " +
            "label=$label, paragraph=${layout.size}, lines=${layout.lineCount}, clip=$clip, glyphs=$glyphs",
            clip.width > 0f && clip.height > 0f && glyphs.isNotEmpty() && glyphs.all { fits(it, clip) })
    }

    private fun assertWholeTextInRegion(label: String, regionTag: String) {
        val text = compose.onNodeWithText(label, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue("Inspect the actual production Text paragraph", it(layouts))
        }
        val layout = layouts.single()
        assertEquals(label, layout.layoutInput.text.text)
        assertFalse("The complete text must remain present: $label", layout.multiParagraph.didExceedMaxLines)
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
        repeat(layout.lineCount) { assertFalse(layout.isLineEllipsized(it)) }
        val coordinates = text.fetchSemanticsNode().layoutInfo.coordinates
        val origin = coordinates.localToRoot(Offset.Zero)
        // Centered Text semantics can expose the paragraph width rather than its containing Box.
        // Measure the real paper/row region and its ancestor clipping, not that narrower paragraph rectangle.
        val region = compose.onNodeWithTag(regionTag).fetchSemanticsNode().layoutInfo.coordinates
        val clip = region.findRootCoordinates().localBoundingBoxOf(region, clipBounds = true)
            .intersect(compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot)
        val glyphs = label.indices.filter { !label[it].isWhitespace() }
            .map { layout.getBoundingBox(it).translate(origin) }
        assertTrue("Every glyph must fit its real region and clipped viewport: " +
            "label=$label, paragraph=${layout.size}, lines=${layout.lineCount}, clip=$clip, glyphs=$glyphs",
            clip.width > 0f && clip.height > 0f && glyphs.isNotEmpty() && glyphs.all { fits(it, clip) })
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private companion object {
        const val VIEWPORT = "account-logout-visibility-viewport"
        fun fits(glyph: Rect, clip: Rect) = glyph.left >= clip.left - 1f && glyph.right <= clip.right + 1f &&
            glyph.top >= clip.top - 1f && glyph.bottom <= clip.bottom + 1f
    }
}
