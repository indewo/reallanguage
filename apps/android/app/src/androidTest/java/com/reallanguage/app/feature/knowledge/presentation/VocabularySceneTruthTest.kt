package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyEntry
import com.reallanguage.app.feature.knowledge.domain.VocabularyExample
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import org.junit.Rule
import org.junit.Test

/** Content identity controls only; no generated content, account, audio or server is used. */
class VocabularySceneTruthTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hospitalSceneKeepsItsStoredContentWithoutClaimingACafeIllustration() {
        assertTextOnlyScene("在医院办理入院", "She checks in at the hospital.", "她在医院办理入院。")
    }

    @Test fun mentioningACafeDoesNotInventAnImageBindingAbsentFromTheContract() {
        assertTextOnlyScene("在咖啡店办理活动签到", "She checks in at the café.", "她在咖啡店签到。")
    }

    private fun assertTextOnlyScene(scene: String, english: String, chinese: String) {
        // The actual production record has scene text and saved examples, but no
        // trusted image/asset identifier. Neither keywords nor the demo drawing
        // can establish that the illustration depicts this record's scene.
        val sense = VocabularySenseRecord(
            "check-in-scene", "check in", "办理登记", KnowledgeStatus.LEARNING, 0, setOf("CET4"), 1,
            representativeScene = scene,
            examples = listOf(VocabularyExample(english, chinese, english)),
        )
        val state = VocabularyBookUiState("CET4", listOf(VocabularyEntry(sense.englishForm, listOf(sense))),
            expandedSenseId = sense.senseId, loading = false)
        compose.setContent { RlTheme { RlPaperBackground { VocabularyBookScreen(state, {}) } } }
        val inDetail = hasAnyAncestor(hasTestTag("vocabulary-detail-sheet"))
        // Text may be below the current local viewport; absence must not be
        // masked by substituting a picture or a newly invented scene caption.
        compose.onNode(hasText(scene) and inDetail, useUnmergedTree = true).assertExists()
        compose.onNode(hasText(english) and inDetail, useUnmergedTree = true).assertExists()
        compose.onNode(hasText(chinese) and inDetail, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("vocabulary-detail-collapse").assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val phase = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("vocabularySceneSnapshot")
        if (phase != null) {
            require(phase == "before" || phase == "after")
            compose.waitForIdle()
            instrumentation.uiAutomation.waitForIdle(200, 5_000)
            val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(directory.isDirectory || directory.mkdirs())
            val label = if (scene.startsWith("在医院")) "hospital" else "cafe-text-only"
            val output = java.io.File(directory, "41-scene-truth-$label-$phase.png")
            check(output.canonicalFile.parentFile == directory.canonicalFile)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { output.outputStream().use { check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { screenshot.recycle() }
        }
        compose.onNodeWithTag("vocabulary-representative-scene-image").assertDoesNotExist()
    }
}
