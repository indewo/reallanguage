package com.reallanguage.app.core.app

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

/** Explicit opt-in only: uses the signed-in test account and real production API. No fake graph. */
class ProductionDiaryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun enterCoffeeRequestFromActualHome() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runProductionSmoke") == "true")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("home-diary-card").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-diary-card").performClick()
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("diary-text-input").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("home-diary-card").performClick()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("diary-text-input").fetchSemanticsNodes().isNotEmpty() }
        // Stop instead of replacing any pre-existing user draft.
        assertEquals("", compose.onNodeWithTag("diary-text-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val request = "我想学习如何在咖啡店用英语点一杯不加糖的热拿铁。"
        compose.onNodeWithTag("diary-text-input").performTextInput(request)
        assertEquals(request, compose.onNodeWithTag("diary-text-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            (compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
        compose.onNodeWithTag("diary-submit").assertIsEnabled().performClick()
        compose.waitUntil(120_000) {
            compose.onAllNodesWithTag("bilingual_continue").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(request.removeSuffix("。"), substring = true).assertExists()
        compose.onNode(hasTestTagPrefix("bilingual_sentence_")).performClick()
        compose.onNodeWithTag("bilingual_continue").assertIsEnabled().performClick()
        compose.waitUntil(120_000) {
            compose.onAllNodesWithTag("learning_targets_page").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().printToLog("ProductionDiaryDevice")
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.takeScreenshot()!!.let { bitmap ->
            java.io.File(inst.targetContext.filesDir, "production-coffee-targets.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }
}
