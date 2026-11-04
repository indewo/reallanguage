package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyEntry
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import org.junit.Rule
import org.junit.Test

class VocabularyBookScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun separatesSensesAndSelectsTheRequestedMeaning() {
        val actions = mutableListOf<VocabularyBookAction>()
        val senses = listOf(
            VocabularySenseRecord("run-1", "run", "跑步", KnowledgeStatus.MASTERED, 3, setOf("六级"), 1),
            VocabularySenseRecord("run-2", "run", "经营", KnowledgeStatus.LEARNING, 1, setOf("商务英语"), 2),
        )
        val state = VocabularyBookUiState("六级 + 商务英语", listOf(VocabularyEntry("run", senses)), loading = false)

        composeRule.setContent { RlTheme { VocabularyBookScreen(state, actions::add) } }

        composeRule.onNodeWithTag("vocabulary-sense-list").assertIsDisplayed()
        composeRule.onNodeWithText("当前范围：六级 + 商务英语").assertIsDisplayed()
        composeRule.onNodeWithTag("vocabulary-sense-run-2").assertIsDisplayed().performClick()
        assert(actions == listOf(VocabularyBookAction.SenseSelected("run-2")))
    }

    @Test fun expandingAnotherEnglishEntryNeverPairsFirstWordWithSelectedMeaningAndPhonetic() {
        val run = VocabularySenseRecord("run-business", "run", "经营", KnowledgeStatus.LEARNING, 1, setOf("CET4"), 1,
            phonetic = "/rʌn/", pronunciationText = "run")
        val calm = VocabularySenseRecord("calm-state", "calm", "平静", KnowledgeStatus.MASTERED, 2, setOf("CET4"), 2,
            phonetic = "/kɑːm/", pronunciationText = "calm")
        val state = VocabularyBookUiState("CET4", listOf(VocabularyEntry("run", listOf(run)), VocabularyEntry("calm", listOf(calm))),
            expandedSenseId = calm.senseId, loading = false)
        composeRule.setContent { RlTheme { RlPaperBackground { VocabularyBookScreen(state, {}) } } }
        composeRule.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        composeRule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            java.io.File(instrumentation.targetContext.filesDir, "vocabulary-cross-entry-state.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        // The outer list may recycle an off-screen sibling after it brings the
        // selected detail into view. Identity is verified on the selected owner.
        composeRule.onAllNodesWithText("calm").assertCountEquals(1)
        composeRule.onNodeWithText("/kɑːm/").assertIsDisplayed()
        composeRule.onNodeWithTag("vocabulary-detail-sheet").assertIsDisplayed()
    }

    @Test fun defaultGroupUsesStoredEnglishRatherThanReferenceExample() {
        val actions = mutableListOf<VocabularyBookAction>()
        val calm = VocabularySenseRecord("calm-state", "calm", "平静", KnowledgeStatus.LEARNING, 0, setOf("CET4"), 1,
            phonetic = "/kɑːm/", pronunciationText = "calm")
        val state = VocabularyBookUiState("CET4", listOf(VocabularyEntry("calm", listOf(calm))), loading = false)
        composeRule.setContent { RlTheme { VocabularyBookScreen(state, actions::add) } }
        // Preserve the original identity check without requiring a global badge
        // absent from both the reference and the grouped-list product rule.
        composeRule.onAllNodesWithText("calm", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithText("/kɑːm/").assertIsDisplayed()
        val row = hasAnyAncestor(hasTestTag("vocabulary-sense-calm-state"))
        composeRule.onNode(hasText("平静") and row, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasText("学习中") and row, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("run").assertDoesNotExist()
        composeRule.onNodeWithText("/rʌn/").assertDoesNotExist()
        composeRule.onNodeWithText("R · 本期词汇").assertDoesNotExist()
        composeRule.onNodeWithTag("vocabulary-detail-sheet").assertDoesNotExist()
        composeRule.onNodeWithTag("vocabulary-sense-calm-state").performTouchInput { click() }
        org.junit.Assert.assertEquals(listOf(VocabularyBookAction.SenseSelected(calm.senseId)), actions)
    }

    @Test fun searchControlsStayVisibleAndEmitQueryClearAndSearchActions() {
        val actions = mutableListOf<VocabularyBookAction>()
        val ability = VocabularySenseRecord(
            "ability", "ability", "能力", KnowledgeStatus.NOT_STARTED, 0,
            setOf("CET_4"), 1, pronunciationText = "ability", partOfSpeech = "n.",
        )
        val state = VocabularyBookUiState(
            currentScope = "四级单词",
            entries = listOf(VocabularyEntry("ability", listOf(ability))),
            loading = false,
            query = "abil",
        )
        composeRule.setContent { RlTheme { VocabularyBookScreen(state, actions::add) } }

        composeRule.onNodeWithTag("vocabulary-search-bar").assertIsDisplayed()
        composeRule.onNodeWithTag("vocabulary-search-input").assertIsDisplayed()
        composeRule.onNodeWithText("n.").assertIsDisplayed()
        composeRule.onNodeWithTag("vocabulary-search-submit").performClick()
        composeRule.onNodeWithTag("vocabulary-search-clear").performClick()
        org.junit.Assert.assertEquals(
            listOf(VocabularyBookAction.SearchRequested, VocabularyBookAction.ClearSearchRequested),
            actions,
        )
    }

    @Test fun searchInputClearAndSubmitStayInOneFlatBalancedRow() {
        val ability = VocabularySenseRecord(
            "ability", "ability", "能力", KnowledgeStatus.NOT_STARTED, 0,
            setOf("CET_6"), 1, pronunciationText = "ability", partOfSpeech = "n.",
        )
        composeRule.setContent {
            RlTheme { RlPaperBackground {
                VocabularyBookScreen(
                    VocabularyBookUiState(
                        currentScope = "六级单词",
                        entries = listOf(VocabularyEntry("ability", listOf(ability))),
                        loading = false,
                    ),
                    {},
                )
            } }
        }
        composeRule.waitForIdle()

        val bar = composeRule.onNodeWithTag("vocabulary-search-bar").getUnclippedBoundsInRoot()
        val input = composeRule.onNodeWithTag("vocabulary-search-input").getUnclippedBoundsInRoot()
        val clear = composeRule.onNodeWithTag("vocabulary-search-clear").getUnclippedBoundsInRoot()
        val submit = composeRule.onNodeWithTag("vocabulary-search-submit").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("search row is still too tall", bar.bottom - bar.top <= 38.5.dp)
        org.junit.Assert.assertEquals("clear and submit widths differ", clear.right - clear.left, submit.right - submit.left)
        org.junit.Assert.assertEquals("clear and submit heights differ", clear.bottom - clear.top, submit.bottom - submit.top)
        org.junit.Assert.assertTrue("input is not in the same row", input.top >= bar.top && input.bottom <= bar.bottom)
        org.junit.Assert.assertTrue("clear is not in the same row", clear.top >= bar.top && clear.bottom <= bar.bottom)
        org.junit.Assert.assertTrue("submit is not in the same row", submit.top >= bar.top && submit.bottom <= bar.bottom)

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag("vocabulary-search-hint", useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                org.junit.Assert.assertTrue(it(layouts))
            }
        org.junit.Assert.assertEquals("search hint wrapped", 1, layouts.single().lineCount)
        org.junit.Assert.assertFalse("search hint was ellipsized", layouts.single().isLineEllipsized(0))
        org.junit.Assert.assertFalse("search hint overflowed its input paper", layouts.single().didOverflowWidth)
        saveScreenshot("search-row-flat")
    }

    @Test fun alphabetRailShowsAllLettersWithoutEllipsisAndJumpsFromSelectedLetter() {
        val actions = mutableListOf<VocabularyBookAction>()
        val ability = VocabularySenseRecord(
            "ability", "ability", "能力", KnowledgeStatus.NOT_STARTED, 0,
            setOf("CET_4"), 1, pronunciationText = "ability", partOfSpeech = "n.",
        )
        val state = VocabularyBookUiState(
            currentScope = "四级单词",
            entries = listOf(VocabularyEntry("ability", listOf(ability))),
            loading = false,
        )
        composeRule.setContent { RlTheme { VocabularyBookScreen(state, actions::add) } }

        ('A'..'Z').forEach { letter ->
            composeRule.onNodeWithTag("vocabulary-initial-$letter", useUnmergedTree = true)
                .assertExists()
                .assertIsDisplayed()
        }
        composeRule.onNodeWithText("…").assertDoesNotExist()
        composeRule.onNodeWithTag("vocabulary-initial-R", useUnmergedTree = true).performClick()
        org.junit.Assert.assertEquals(VocabularyBookAction.InitialSelected('R'), actions.last())
    }

    @Test fun alphabetRailDragCommitsTheLetterUnderTheReleasePoint() {
        val actions = mutableListOf<VocabularyBookAction>()
        val ability = VocabularySenseRecord(
            "ability", "ability", "能力", KnowledgeStatus.NOT_STARTED, 0,
            setOf("CET_4"), 1, pronunciationText = "ability", partOfSpeech = "n.",
        )
        composeRule.setContent {
            RlTheme { RlPaperBackground {
                VocabularyBookScreen(
                    VocabularyBookUiState("四级单词", listOf(VocabularyEntry("ability", listOf(ability))), loading = false),
                    actions::add,
                )
            } }
        }

        composeRule.onNodeWithTag("vocabulary-alphabet-rail").performTouchInput {
            swipe(
                start = androidx.compose.ui.geometry.Offset(center.x, height * .12f),
                end = androidx.compose.ui.geometry.Offset(center.x, height * .78f),
                durationMillis = 650,
            )
        }
        val initials = actions.filterIsInstance<VocabularyBookAction.InitialSelected>().map { it.initial }
        org.junit.Assert.assertEquals("drag did not commit exactly one final letter", 1, initials.size)
        org.junit.Assert.assertTrue("drag still selected the starting letter", initials.single() > 'M')
    }

    @Test fun poolLongestHeadwordKeepsEveryWordCompleteOnOneLine() {
        fun entry(word: String) = VocabularyEntry(
            word,
            listOf(
                VocabularySenseRecord(
                    "$word-n", word, "测试释义", KnowledgeStatus.NOT_STARTED, 0,
                    setOf("CET_6"), 1, pronunciationText = word, partOfSpeech = "n.",
                ),
            ),
        )
        val longest = "notwithstanding"
        composeRule.setContent {
            RlTheme { RlPaperBackground {
                VocabularyBookScreen(
                    VocabularyBookUiState(
                        currentScope = "六级单词",
                        entries = listOf(entry("qualification"), entry("qualify")),
                        loading = false,
                        selectedInitial = 'Q',
                        longestHeadword = longest,
                    ),
                    {},
                )
            } }
        }
        composeRule.waitForIdle()

        listOf("qualification", "qualify").forEach { word ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithTag("vocabulary-word-label-$word", useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                    org.junit.Assert.assertTrue(it(layouts))
                }
            val layout = layouts.single()
            org.junit.Assert.assertEquals("$word wrapped", 1, layout.lineCount)
            org.junit.Assert.assertEquals("$word was truncated", word.length, layout.getLineEnd(0))
            org.junit.Assert.assertFalse("$word was ellipsized", layout.isLineEllipsized(0))
            org.junit.Assert.assertFalse("$word exceeded its one-line box", layout.multiParagraph.didExceedMaxLines)
        }
        saveScreenshot("q-longest-single-line")
    }

    @Test fun longSenseMeaningsExpandPaperAndRemainInsideItsSafeBounds() {
        val senses = listOf(
            VocabularySenseRecord(
                "comprehensive-adj", "comprehensive",
                "全面的；综合性的；包含许多不同部分并对重要细节进行完整说明的",
                KnowledgeStatus.LEARNING, 1, setOf("CET_6"), 1,
                pronunciationText = "comprehensive", partOfSpeech = "adj.",
            ),
            VocabularySenseRecord(
                "comprehensive-n", "comprehensive",
                "为学生准备的综合考试；覆盖多个学习领域并需要完整作答的测试",
                KnowledgeStatus.NOT_STARTED, 0, setOf("CET_6"), 2,
                pronunciationText = "comprehensive", partOfSpeech = "n.",
            ),
        )
        composeRule.setContent {
            RlTheme { RlPaperBackground {
                VocabularyBookScreen(
                    VocabularyBookUiState("六级单词", listOf(VocabularyEntry("comprehensive", senses)), loading = false),
                    {},
                )
            } }
        }
        composeRule.waitForIdle()

        val paper = composeRule.onNodeWithTag("vocabulary-meaning-comprehensive").getUnclippedBoundsInRoot()
        senses.forEach { sense ->
            val row = composeRule.onNodeWithTag("vocabulary-sense-${sense.senseId}").getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("sense starts outside paper", row.top >= paper.top)
            org.junit.Assert.assertTrue("sense ends outside paper", row.bottom <= paper.bottom - 4.dp)
            org.junit.Assert.assertTrue("sense exceeds paper width", row.left >= paper.left && row.right <= paper.right)
            org.junit.Assert.assertTrue("sense row is vertically cramped", row.bottom - row.top >= 41.dp)
        }
    }

    @Test fun compactWordAndMeaningPapersShareHeightAndKeepControlsOffTornEdges() {
        val sense = VocabularySenseRecord(
            "academic-adj", "academic", "学术的；学院的", KnowledgeStatus.NOT_STARTED,
            0, setOf("CET_6"), 1, phonetic = "/ˌækəˈdemɪk/", pronunciationText = "academic",
            partOfSpeech = "adj.",
        )
        composeRule.setContent {
            RlTheme { RlPaperBackground {
                VocabularyBookScreen(
                    VocabularyBookUiState("六级单词", listOf(VocabularyEntry("academic", listOf(sense))), loading = false),
                    {},
                )
            } }
        }
        composeRule.waitForIdle()

        val wordPaper = composeRule.onNodeWithTag("vocabulary-word-academic").getUnclippedBoundsInRoot()
        val meaningPaper = composeRule.onNodeWithTag("vocabulary-meaning-academic").getUnclippedBoundsInRoot()
        val pronunciation = composeRule.onNodeWithTag("vocabulary-group-pronunciation-academic-adj")
            .getUnclippedBoundsInRoot()
        org.junit.Assert.assertEquals(wordPaper.bottom - wordPaper.top, meaningPaper.bottom - meaningPaper.top)
        org.junit.Assert.assertTrue("pronunciation touches top paper edge", pronunciation.top >= wordPaper.top + 8.dp)
        org.junit.Assert.assertTrue("pronunciation touches torn bottom edge", pronunciation.bottom <= wordPaper.bottom - 8.dp)
    }

    @Test fun fourLongLaborSensesStayInsideTheMeaningPaper() {
        fun sense(id: String, pos: String, meaning: String) = VocabularySenseRecord(
            id, "labor", meaning, KnowledgeStatus.NOT_STARTED, 0, setOf("CET_6"), 1,
            phonetic = "/ˈleɪbə/", pronunciationText = "labor", partOfSpeech = pos,
        )
        val senses = listOf(
            sense("labor-adj", "adj.", "劳工的；劳动的"),
            sense("labor-n", "n.", "工作；劳动；劳力"),
            sense("labor-vi", "vi.", "过分详尽阐述"),
            sense("labor-vt", "vt.", "苦干；劳动；分娩；阵痛；致力于"),
        )
        composeRule.setContent {
            RlTheme { RlPaperBackground {
                VocabularyBookScreen(
                    VocabularyBookUiState("六级单词", listOf(VocabularyEntry("labor", senses)), loading = false),
                    {},
                )
            } }
        }
        composeRule.waitForIdle()

        val paper = composeRule.onNodeWithTag("vocabulary-meaning-labor").getUnclippedBoundsInRoot()
        senses.forEach { sense ->
            val row = composeRule.onNodeWithTag("vocabulary-sense-${sense.senseId}").getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("${sense.senseId} extends below torn paper", row.bottom <= paper.bottom - 8.dp)
            org.junit.Assert.assertTrue("${sense.senseId} starts outside paper", row.top >= paper.top + 4.dp)
            org.junit.Assert.assertTrue("${sense.senseId} is too narrow", row.right <= paper.right && row.left >= paper.left)
        }
    }

    @Test fun captureChosenVocabularyDesignForPixelRegression() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .first()
            com.reallanguage.app.core.app.configureFocusedLearningWindow(activity.window)
        }
        fun sense(id: String, word: String, pos: String, meaning: String, status: KnowledgeStatus, count: Int = 0) =
            VocabularySenseRecord(
                id, word, meaning, status, count, setOf("CET_6"), 1,
                phonetic = when (word) {
                    "abstract" -> "/ˈæbstrækt/"
                    "academic" -> "/ˌækəˈdemɪk/"
                    else -> null
                },
                pronunciationText = word,
                partOfSpeech = pos,
            )
        val entries = listOf(
            VocabularyEntry("abstract", listOf(
                sense("abstract-adj", "abstract", "adj.", "抽象的", KnowledgeStatus.NOT_STARTED),
                sense("abstract-n", "abstract", "n.", "摘要；抽象的东西", KnowledgeStatus.LEARNING),
                sense("abstract-vi", "abstract", "vi.", "做摘要", KnowledgeStatus.TENTATIVE),
                sense("abstract-vt", "abstract", "vt.", "移除；摘要；偷", KnowledgeStatus.MASTERED, 2),
            )),
            VocabularyEntry("academic", listOf(
                sense("academic-adj", "academic", "adj.", "学术的；学院的", KnowledgeStatus.NOT_STARTED),
            )),
            VocabularyEntry("access", listOf(
                sense("access-n", "access", "n.", "入口；接近；使用权", KnowledgeStatus.NOT_STARTED),
            )),
        )
        val state = VocabularyBookUiState(
            currentScope = "六级单词", entries = entries, loading = false, selectedInitial = 'A',
        )
        composeRule.setContent { RlTheme { RlPaperBackground { VocabularyBookScreen(state, {}) } } }
        composeRule.waitForIdle()
        saveScreenshot("reference")
    }

    @Test fun initialRibbonNeverCoversWordsBeforeOrAfterScrolling() {
        val entries = lEntries()
        composeRule.setContent { RlTheme { RlPaperBackground {
            VocabularyBookScreen(VocabularyBookUiState("六级单词", entries, loading = false, selectedInitial = 'L'), {})
        } } }
        val ribbon = composeRule.onNodeWithTag("vocabulary-initial-ribbon").getUnclippedBoundsInRoot()
        fun checkWord(word: String) {
            val text = composeRule.onNodeWithTag("vocabulary-word-label-$word", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("initial ribbon covers $word", text.top >= ribbon.bottom + 6.dp)
            val botanical = composeRule.onNodeWithTag("vocabulary-botanical", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("botanical decoration covers word column", botanical.right <= text.left - 4.dp)
        }
        checkWord("lab")
        saveScreenshot("l-first")
        composeRule.onNodeWithTag("vocabulary-sense-list").performScrollToIndex(3)
        checkWord("ladder")
        saveScreenshot("l-scrolled")
    }

    @Test fun pronunciationIsIconOnlyBesideTheWordAndNeverCrowdsLongPhonetics() {
        val actions = mutableListOf<VocabularyBookAction>()
        val entry = lEntries().first { it.englishForm == "lady" }
        composeRule.setContent { RlTheme { RlPaperBackground {
            VocabularyBookScreen(VocabularyBookUiState("六级单词", listOf(entry), loading = false), actions::add)
        } } }
        val phonetic = composeRule.onNodeWithTag("vocabulary-phonetic-lady", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val word = composeRule.onNodeWithTag("vocabulary-word-label-lady", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val icon = composeRule.onNodeWithTag("vocabulary-group-pronunciation-lady-n").getUnclippedBoundsInRoot()
        val paper = composeRule.onNodeWithTag("vocabulary-word-lady").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("pronunciation is not beside the word", icon.top < word.bottom && icon.bottom > word.top)
        org.junit.Assert.assertTrue("icon overlaps word", icon.left >= word.right + 4.dp)
        org.junit.Assert.assertTrue("phonetic overlaps pronunciation", phonetic.top >= icon.bottom)
        org.junit.Assert.assertTrue("icon crosses paper edge", icon.right <= paper.right - 12.dp && icon.bottom <= paper.bottom - 8.dp)
        composeRule.onNodeWithText("标准美音").assertDoesNotExist()
        composeRule.onNodeWithTag("vocabulary-group-pronunciation-lady-n").performClick()
        org.junit.Assert.assertEquals(listOf(VocabularyBookAction.SensePronunciationRequested("lady-n")), actions)
    }

    @Test fun alphabetEndsKeepBreathingRoomWhenAOrZIsSelected() {
        val selected = mutableStateOf('A')
        composeRule.setContent { RlTheme { RlPaperBackground {
            val word = if (selected.value == 'A') "abstract" else "zoo"
            val entry = VocabularyEntry(word, listOf(VocabularySenseRecord(word, word, "测试", KnowledgeStatus.NOT_STARTED,
                0, setOf("CET_6"), 1, pronunciationText = word)))
            VocabularyBookScreen(VocabularyBookUiState("六级单词", listOf(entry), loading = false, selectedInitial = selected.value), {})
        } } }
        fun checkEnds() {
            composeRule.waitForIdle()
            val rail = composeRule.onNodeWithTag("vocabulary-alphabet-rail").getUnclippedBoundsInRoot()
            val a = composeRule.onNodeWithTag("vocabulary-initial-A", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val z = composeRule.onNodeWithTag("vocabulary-initial-Z", useUnmergedTree = true).getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("A has no top breathing room", a.top >= rail.top + 12.dp)
            org.junit.Assert.assertTrue("Z has no bottom breathing room", z.bottom <= rail.bottom - 12.dp)
            val glyphs = ('A'..'Z').map { letter ->
                composeRule.onNodeWithTag("vocabulary-initial-text-$letter", useUnmergedTree = true)
                    .assertIsDisplayed()
                    .getUnclippedBoundsInRoot()
            }
            glyphs.zipWithNext().forEachIndexed { index, (current, next) ->
                org.junit.Assert.assertTrue(
                    "${'A' + index} overlaps ${'A' + index + 1}",
                    current.bottom <= next.top + .5.dp,
                )
            }
        }
        checkEnds()
        composeRule.runOnIdle { selected.value = 'Z' }
        checkEnds()
        saveScreenshot("z-selected")
    }

    @Test fun narrowLargeTextKeepsLongMeaningsInsideTheirOwnPaper() {
        val entries = lEntries().filter { it.englishForm == "labor" || it.englishForm == "lag" }
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                RlTheme { RlPaperBackground { Box(Modifier.width(720.dp)) {
                    VocabularyBookScreen(VocabularyBookUiState("六级单词", entries, loading = false, selectedInitial = 'L'), {})
                } } }
            }
        }
        composeRule.waitForIdle()
        val paper = composeRule.onNodeWithTag("vocabulary-meaning-labor").getUnclippedBoundsInRoot()
        val word = composeRule.onNodeWithTag("vocabulary-word-label-labor", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val wordPaper = composeRule.onNodeWithTag("vocabulary-word-labor").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(
            "large-text word and meaning papers did not use the same full-width stack",
            wordPaper.right - wordPaper.left >= (paper.right - paper.left) - 1.dp,
        )
        org.junit.Assert.assertTrue("large-text word is split mid-word", word.bottom - word.top <= 96.dp)
        org.junit.Assert.assertTrue("large-text meanings overlap the word paper", paper.top >= wordPaper.bottom)
        entries.first().senses.forEach { sense ->
            val row = composeRule.onNodeWithTag("vocabulary-sense-${sense.senseId}").getUnclippedBoundsInRoot()
            val text = composeRule.onNodeWithTag("vocabulary-meaning-text-${sense.senseId}", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            org.junit.Assert.assertTrue("long text crosses row bottom", text.bottom <= row.bottom)
            org.junit.Assert.assertTrue("long text crosses paper bottom", text.bottom <= paper.bottom - 8.dp)
            org.junit.Assert.assertTrue("long text crosses paper width", text.left >= paper.left && text.right <= paper.right)
        }
        composeRule.onNodeWithTag("vocabulary-search-input").assertIsDisplayed()
        val ribbon = composeRule.onNodeWithTag("vocabulary-initial-ribbon").getUnclippedBoundsInRoot()
        val ribbonText = composeRule.onNodeWithText("L 开头", useUnmergedTree = true).getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("large-text initial ribbon clips its label", ribbonText.bottom <= ribbon.bottom - 3.dp)
        composeRule.onNodeWithTag("vocabulary-initial-L", useUnmergedTree = true).assertIsDisplayed()
        saveScreenshot("large-text")
    }

    private fun lEntries(): List<VocabularyEntry> {
        fun entry(word: String, phonetic: String, vararg senses: Pair<String, String>) = VocabularyEntry(word,
            senses.map { (pos, meaning) -> VocabularySenseRecord("$word-${pos.removeSuffix(".")}", word, meaning,
                KnowledgeStatus.NOT_STARTED, 0, setOf("CET_6"), 1, phonetic = phonetic,
                pronunciationText = word, partOfSpeech = pos) })
        return listOf(
            entry("lab", "/læb/ | læb", "n." to "实验室 研究室"),
            entry("label", "ˈleibl | /ˈleɪbl/ | [leibl]", "n." to "标签", "vt." to "贴标签于 分类 把…称为"),
            entry("labor", "/ˈleɪbər/", "adj." to "劳工的 劳动的", "n." to "工作；劳动；劳力",
                "vi." to "过分详尽阐述", "vt." to "苦干；劳动；分娩；阵痛；致力于"),
            entry("ladder", "ˈlædə | /ˈlædər/", "n." to "梯子；阶梯"),
            entry("lady", "ˈleidi | /ˈleɪdi/ | /ˈleɪdɪ/ | [ˈleidi]", "n." to "女士；夫人"),
            entry("lag", "/læɡ/", "adj." to "最后的", "n." to "落后；滞后；落后的人；（两事件之间的）时间间隔；耽搁；桶盖"),
        )
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        composeRule.waitForIdle()
        composeRule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "vocabulary-r11-$name-${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RealLanguage")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = instrumentation.targetContext.contentResolver
            val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            resolver.openOutputStream(uri)!!.use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            bitmap.recycle()
        }
    }
}
