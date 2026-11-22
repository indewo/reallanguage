package com.reallanguage.app.feature.home.presentation

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.home.domain.AccountSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class AccountDesignAlignmentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun longEmailUsesOwnLinesAndRecordingEntryRemainsReachable() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(5_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        val email = "alexandra.research.collaboration@international-example.com"
        val actions = mutableListOf<AccountAction>()
        compose.setContent { RlTheme { AccountScreen(AccountUiState(account = AccountSnapshot(email), loading = false), actions::add) } }
        compose.activityRule.scenario.onActivity { configureFocusedLearningWindow(it.window) }
        compose.waitUntil(5_000) { compose.onNodeWithTag("editorial-page-back").fetchSemanticsNode().boundsInRoot.top < 100f }
        val brandLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("RealLanguage", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { check(it(brandLayouts)) }
        val brand = brandLayouts.single()
        assertEquals("RealLanguage".length, brand.getLineEnd(brand.lineCount - 1))
        val brandNode = compose.onNodeWithText("RealLanguage", useUnmergedTree = true).fetchSemanticsNode()
        val origin = brandNode.layoutInfo.coordinates.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
        val region = compose.onNodeWithTag("account-passport-artwork").fetchSemanticsNode().boundsInRoot.intersect(brandNode.boundsInRoot)
        "RealLanguage".indices.forEach { index ->
            val glyph = brand.getBoundingBox(index).translate(origin)
            assertTrue("Every brand glyph must be visible: $glyph in $region", glyph.left >= region.left - 1f && glyph.right <= region.right + 1f && glyph.top >= region.top - 1f && glyph.bottom <= region.bottom + 1f)
        }
        compose.onNodeWithText(email).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(email).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { check(it(layouts)) }
        assertEquals(email, layouts.single().layoutInput.text.text)
        assertEquals(1, layouts.single().lineCount)
        assertFalse(layouts.single().hasVisualOverflow)
        val label = compose.onNodeWithText("邮箱").fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onNodeWithText(email).fetchSemanticsNode().boundsInRoot.top >= label.bottom)
        val back = compose.onNodeWithTag("editorial-page-back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("account-recordings").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(AccountAction.LocalRecordingsRequested), actions)
        compose.onNodeWithTag("editorial-page-back").assertIsDisplayed()
        assertEquals(back, compose.onNodeWithTag("editorial-page-back").fetchSemanticsNode().boundsInRoot)
        val inst = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.takeScreenshot()!!.let { bitmap -> java.io.File(inst.targetContext.filesDir, "account-design-test.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
}
