package com.reallanguage.app.feature.capture

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.feature.capture.domain.BilingualParagraph
import com.reallanguage.app.feature.capture.domain.BilingualSentence
import com.reallanguage.app.feature.capture.presentation.BilingualPassagesScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

class BilingualPassagesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val opening = listOf("A coworker asked for the draft this morning.",
        "I still have a few numbers to check.", "I'll send it over tomorrow morning.")
    private val meeting = listOf("We're meeting on Thursday.", "I've got a conflict that day.", "Could we move it to Friday?")

    @Test fun eachTapStartsANewUtteranceAndLeavesTheSentenceUnselected() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realNativeSpeech") == "true")
        val sentence = BilingualSentence("speaker", "I can send you the draft tomorrow morning.", "我明天早上把初稿发给你。")
        compose.setContent {
            RlTheme { BilingualPassagesScreen(listOf(BilingualParagraph(sentence.textZh, sentence.textEn, listOf(sentence))),
                emptySet(), {}, {}, {}) }
        }
        repeat(2) {
            compose.onNodeWithTag("bilingual_play_speaker").performClick()
            compose.waitUntil(3_000) {
                compose.onAllNodes(hasStateDescription("正在朗读")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasStateDescription("可朗读")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("暂时无法朗读，请检查设备的英语语音。").assertDoesNotExist()
        }
        compose.onNodeWithText("已选 0/3").assertIsDisplayed()
    }

    @Test fun keepsWholeParagraphsInOneScrollingListAndLimitsSelectionAcrossThem() {
        val intro = "This was our conversation."
        fun paragraph(id: String, zh: String, en: List<String>, prefix: String = ""): BilingualParagraph =
            BilingualParagraph(zh, (listOf(prefix).filter(String::isNotBlank) + en).joinToString(" ").replace("asked for", "asked\nfor"),
                en.mapIndexed { index, text -> BilingualSentence("$id$index", text, "") })
        val paragraphs = listOf(paragraph("a", "今天上午，同事问我能不能今天发初稿。我还要核对几处数据，打算明早发给她。", opening, intro),
            paragraph("b", "我们原本约好周四开会，但我那天有别的安排。我想问能不能改到周五。", meeting))
        val more = (1..6).map { paragraph("s$it-", "我们继续谈下一项。", listOf("Let's talk about item $it.")) }
        var continued: Set<String>? = null
        compose.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            RlTheme {
                BilingualPassagesScreen(paragraphs + more, selected,
                    onSelect = { selected = if (it in selected) selected - it else selected + it },
                    onBack = {}, onContinue = { continued = selected })
            }
        }
        val grid = compose.onNodeWithTag("bilingual_scroll")
        compose.onNodeWithTag("bilingual_continue").assertIsNotEnabled()
        compose.onNodeWithText("中文段落").assertDoesNotExist()
        compose.onNodeWithText("英文段落").assertDoesNotExist()
        compose.onNodeWithText(intro).assertIsDisplayed()
        for (id in listOf("a0", "a1", "a2")) {
            grid.performScrollToNode(hasTestTag("bilingual_sentence_$id"))
            compose.onNodeWithTag("bilingual_sentence_$id").performClick()
        }
        compose.onNodeWithText("已选 3/3").assertIsDisplayed()
        grid.performScrollToNode(hasTestTag("bilingual_sentence_b0"))
        compose.onNodeWithTag("bilingual_sentence_b0").assertIsNotEnabled()
        compose.onNodeWithTag("bilingual_play_b0").assertIsEnabled()
        grid.performScrollToNode(hasTestTag("bilingual_sentence_a1"))
        compose.onNodeWithTag("bilingual_sentence_a1").performClick()
        grid.performScrollToNode(hasTestTag("bilingual_sentence_s6-0"))
        compose.onNodeWithTag("bilingual_sentence_s6-0").performClick()
        compose.onNodeWithText("已选 3/3").assertIsDisplayed()
        compose.onNodeWithTag("bilingual_continue").performClick()
        assertEquals(setOf("a0", "a2", "s6-0"), continued)
        grid.performScrollToNode(hasTestTag("bilingual_sentence_a0"))
        if (InstrumentationRegistry.getArguments().getString("captureProof") == "true") {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(compose.activity.getExternalFilesDir(null), "bilingual-screen.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
        }
    }
}
