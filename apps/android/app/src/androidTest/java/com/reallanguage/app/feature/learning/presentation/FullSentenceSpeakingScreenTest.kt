package com.reallanguage.app.feature.learning.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import com.reallanguage.app.core.app.configureFocusedLearningWindow
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.reallanguage.app.core.designsystem.RlTheme
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.SpeakingEvaluationDecision
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackAspect
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackDetail
import com.reallanguage.app.feature.learning.domain.ReadingFeedbackEvidence
import org.junit.Rule
import org.junit.Test
import org.junit.Before

class FullSentenceSpeakingScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    @Before fun focusedLearningWindow() { composeRule.activityRule.scenario.onActivity {
        it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        configureFocusedLearningWindow(it.window)
    } }

    @Test fun pendingTechnicalRetryLocksEvidenceAndDispatchesOnlyRetry() {
        val actions = mutableListOf<FullSentenceSpeakingAction>()
        val state = FullSentenceSpeakingUiState(
            chapter = FixedLearningChapter("retry-chapter", 1, listOf(
                FixedLearningSentence("retry-focus", "Today is a wonderful day.", "今天是美好的一天。", true),
            )),
            currentFocusSentenceId = "retry-focus",
            attemptIndex = 2,
            technicalRetryPending = true,
            captureFailed = true,
        )
        var exitRequests = 0
        composeRule.setContent { RlTheme { RlPaperBackground {
            CompositionLocalProvider(LocalLearningExitAction provides { exitRequests += 1 }) {
                FullSentenceSpeakingScreen(state, actions::add)
            }
        } } }
        val heading = composeRule.onNodeWithTag("learning-heading")
            .fetchSemanticsNode().boundsInRoot
        val exit = composeRule.onNodeWithTag("learning-header-exit").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Exit must be contained by the shared heading, not float over the page: heading=$heading exit=$exit",
            exit.left >= heading.left && exit.right <= heading.right && exit.top >= heading.top && exit.bottom <= heading.bottom)
        composeRule.onNodeWithTag("learning-header-exit").performClick()
        org.junit.Assert.assertEquals(1, exitRequests)
        composeRule.onNodeWithText("播放").assertIsNotEnabled()
        composeRule.onAllNodesWithText("上次请求尚未取得结果。录音已保留，重试将继续同一次请求。")
            .assertCountEquals(0)
        composeRule.onAllNodesWithText("本次无法形成有效判断，请重试。").assertCountEquals(0)
        captureRetryFixtureIfRequested()
        composeRule.onNodeWithText("重试").assertIsDisplayed().assertIsEnabled().performClick()
        org.junit.Assert.assertEquals(listOf(FullSentenceSpeakingAction.RetryRequested), actions)
    }

    @Test fun readingAttemptLabelIsCenteredAndTheTrailingSentenceArrowIsInTheCurrentRow() {
        val state = FullSentenceSpeakingUiState(
            chapter = FixedLearningChapter("c1", 1, listOf(
                FixedLearningSentence("s1", "Today is a wonderful day.", "今天是美好的一天。", true),
            )),
            currentFocusSentenceId = "s1",
            attemptIndex = 2,
        )
        composeRule.setContent { RlTheme { RlPaperBackground { FullSentenceSpeakingScreen(state) {} } } }
        val title = composeRule.onNodeWithTag("full-sentence-attempt").assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        title.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
            org.junit.Assert.assertTrue(it(layouts))
        }
        val layout = layouts.single()
        val renderedCenter = (layout.getLineLeft(0) + layout.getLineRight(0)) / 2f
        org.junit.Assert.assertEquals("The attempt label must be centered inside its green module",
            layout.size.width / 2f, renderedCenter, 1f)
        composeRule.onNodeWithTag("learning-current-trailing-arrow").assertIsDisplayed()
    }

    @Test fun bilingualAttemptShowsExplicitEvidenceBoundAiFeedbackInsteadOfAnUnlabelledParagraph() {
        val state = FullSentenceSpeakingUiState(
            chapter = FixedLearningChapter("c1", 1, listOf(
                FixedLearningSentence("s1", "Today is a wonderful day.", "今天是美好的一天。", true),
            )),
            currentFocusSentenceId = "s1",
            attemptIndex = 0,
            feedbackZh = "整句内容完整，表达清楚。",
            feedbackConfidence = .88,
            feedbackDetails = listOf(
                ReadingFeedbackDetail(ReadingFeedbackAspect.WORD_OR_PHRASE, "wonderful day", "短语中的词音都可以辨认。", "保持两个词连贯。", ReadingFeedbackEvidence.AUDIO),
                ReadingFeedbackDetail(ReadingFeedbackAspect.RHYTHM, null, "句末停顿自然。", "继续按一个完整意群朗读。", ReadingFeedbackEvidence.AUDIO),
                ReadingFeedbackDetail(ReadingFeedbackAspect.COMPLETENESS, null, "识别文本覆盖当前重点句。", "保持整句完整。", ReadingFeedbackEvidence.TRANSCRIPT),
            ),
        )
        composeRule.setContent { RlTheme { RlPaperBackground { FullSentenceSpeakingScreen(state) {} } } }
        composeRule.onNodeWithText("AI 朗读反馈").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("基于本次录音与识别文本").assertIsDisplayed()
        composeRule.onNodeWithText("词/短语").assertIsDisplayed()
        composeRule.onNodeWithText("节奏").assertIsDisplayed()
        composeRule.onNodeWithText("完整度").assertIsDisplayed()
        composeRule.onNodeWithText("“wonderful day”  ").assertIsDisplayed()
        composeRule.onNodeWithText("AI 反馈置信度：较高 · 仅供本轮练习参考").assertIsDisplayed()
        composeRule.onNodeWithTag("full-sentence-attempt").assertTextEquals("中英跟读 · 第 1 次")
        composeRule.onNodeWithTag("learning-current-trailing-arrow").assertIsDisplayed()
        captureAiFeedbackFixtureIfRequested()
    }

    @Test fun terminalAttemptLabelsNewRecordingExplicitlyInsteadOfPretendingTechnicalRetry() {
        val actions = mutableListOf<FullSentenceSpeakingAction>()
        val state = savedPassState(progressFailed = false).copy(finalDecision = null,
            evaluationReceived = false, transcriptReadyForConfirmation = false,
            technicalRetryBlocked = true, captureFailed = true)
        composeRule.setContent { RlTheme { RlPaperBackground { FullSentenceSpeakingScreen(state, actions::add) } } }
        composeRule.onNodeWithText("本次请求不能安全重试，未形成学习结果。你可以重新录音，明确开始一次新练习。")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(state.transcript).assertIsNotEnabled()
        composeRule.onNodeWithText("播放").assertIsNotEnabled()
        composeRule.onNodeWithText("重新录音").assertIsDisplayed().assertIsEnabled().performClick()
        org.junit.Assert.assertEquals(listOf(FullSentenceSpeakingAction.StartRequested), actions)
    }

    @Test
    fun independentSceneUsesTheActualChapterAndNotTheSampleDiary() {
        val state = FullSentenceSpeakingUiState(
            chapter = FixedLearningChapter("c1", 1, listOf(
                FixedLearningSentence("s0", "The train was late.", "火车晚点了。", false),
                FixedLearningSentence("s1", "I asked for help.", "我寻求了帮助。", true),
            )), currentFocusSentenceId = "s1", attemptIndex = 4,
        )
        composeRule.setContent { RlTheme { FullSentenceSpeakingScreen(state) {} } }
        composeRule.onNodeWithTag("full-sentence-scene").assertTextEquals("场景：火车晚点了。\n我寻求了帮助。")
    }

    @Test
    fun retrySaveButtonDispatchesConfirmationAndNotAnotherRecording() {
        val actions = mutableListOf<FullSentenceSpeakingAction>()
        val state = FullSentenceSpeakingUiState(
            chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true))),
            currentFocusSentenceId = "s1", attemptIndex = 4, transcript = "I felt calm.",
            transcriptReadyForConfirmation = true, evaluationReceived = true, decisionSaveFailed = true,
        )
        composeRule.setContent { RlTheme { FullSentenceSpeakingScreen(state, actions::add) } }
        composeRule.onNodeWithText("重试保存").assertIsDisplayed().performClick()
        org.junit.Assert.assertEquals(listOf(FullSentenceSpeakingAction.ConfirmTranscriptRequested), actions)
    }

    @Test
    fun savedPassWithProgressFailureOffersOnlyRetrySavingAndNeverStartsAnotherRecording() {
        val actions = mutableListOf<FullSentenceSpeakingAction>()
        val state = savedPassState(progressFailed = true)
        composeRule.setContent { RlTheme { RlPaperBackground {
            FullSentenceSpeakingScreen(state, actions::add)
        } } }
        composeRule.onNodeWithText("学习进度未能保存或恢复，请重试；不会重置已保存轮次。").assertIsDisplayed()
        composeRule.onNodeWithText(state.transcript).assertExists()
        composeRule.onNodeWithText("播放").assertIsNotEnabled()
        captureSavedPassProgressFixtureIfRequested()

        composeRule.onNodeWithText("重试保存").assertIsDisplayed().assertIsEnabled().performClick()

        org.junit.Assert.assertEquals(listOf(FullSentenceSpeakingAction.RetryRequested), actions)
        composeRule.onNodeWithText(state.transcript).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun savedPassWithoutProgressFailureKeepsPrimaryActionDisabled() {
        val actions = mutableListOf<FullSentenceSpeakingAction>()
        val state = savedPassState(progressFailed = false)
        composeRule.setContent { RlTheme { RlPaperBackground {
            FullSentenceSpeakingScreen(state, actions::add)
        } } }

        composeRule.onNodeWithText("开始复述").assertIsDisplayed().assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("播放").assertIsNotEnabled()
        org.junit.Assert.assertTrue(actions.isEmpty())
    }

    @Test
    fun confirmedReadOnlyTranscriptKeepsReadableInkWithoutUnlockingEditingOrPlayback() {
        assertDisabledTranscriptReadable(savedPassState(progressFailed = false), "开始复述")
    }

    @Test
    fun submittingTranscriptKeepsReadableInkWithoutUnlockingEditingOrPlayback() {
        assertDisabledTranscriptReadable(savedPassState(progressFailed = false).copy(
            finalDecision = null,
            evaluationReceived = false,
            submitting = true,
        ), "确认转写并判断")
    }

    @Test
    fun shortLargeFontScreenKeepsBothFooterLabelsInsideTheirButtons() {
        val actions = mutableListOf<FullSentenceSpeakingAction>()
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(2f, 2f),
            ) { RlTheme { RlPaperBackground {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.requiredSize(640.dp, 360.dp)) {
                    FullSentenceSpeakingScreen(savedPassState(progressFailed = true), actions::add)
                }
            } } }
        }
        listOf("播放", "重试保存").forEach { label ->
            val text = composeRule.onNodeWithText(label, useUnmergedTree = true)
            text.assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            text.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                org.junit.Assert.assertTrue(it(layouts))
            }
            val layout = layouts.single()
            val coordinates = text.fetchSemanticsNode().layoutInfo.coordinates
            val origin = coordinates.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
            val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
                .intersect(composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot)
            val glyphs = label.indices.map { layout.getBoundingBox(it).translate(origin) }
            org.junit.Assert.assertTrue("The entire footer label must remain inside the button: label=$label clip=$clip glyphs=$glyphs",
                glyphs.all { box -> box.top >= clip.top - 1f && box.bottom <= clip.bottom + 1f &&
                    box.left >= clip.left - 1f && box.right <= clip.right + 1f })
        }
        composeRule.onNodeWithText("播放").assertIsNotEnabled()
        composeRule.onNodeWithText("重试保存").assertIsEnabled().performClick()
        org.junit.Assert.assertEquals(listOf(FullSentenceSpeakingAction.RetryRequested), actions)
    }

    @Test
    fun longConfirmedTranscriptCanBeTouchScrolledToItsLastGlyphOnShortLargeFontScreen() {
        val transcript = "I walked to the library and found a quiet place to read. " +
            "I made careful notes about what I learned and checked them before going home. " +
            "The last line is still part of my confirmed answer."
        val state = savedPassState(progressFailed = true).copy(transcript = transcript)
        val actions = mutableListOf<FullSentenceSpeakingAction>()
        var touchSlop = 0f
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(2f, 2f),
            ) { RlTheme { RlPaperBackground {
                touchSlop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.requiredSize(640.dp, 360.dp)
                    .testTag("full-speaking-long-transcript-viewport")) {
                    FullSentenceSpeakingScreen(state, actions::add)
                }
            } } }
        }
        val input = composeRule.onNodeWithText(transcript)
        input.assertIsNotEnabled()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        input.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
            org.junit.Assert.assertTrue(it(layouts))
        }
        val layout = layouts.single()
        org.junit.Assert.assertEquals(transcript, layout.layoutInput.text.text)
        org.junit.Assert.assertTrue("The fixture must genuinely wrap to multiple lines", layout.lineCount > 3)
        val paper = composeRule.onNode(androidx.compose.ui.test.hasScrollAction() and
            androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasTestTag("full-sentence-workspace")))
        paper.assertIsDisplayed()
        fun position() = paper.fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        var remainingTailDistance = Float.POSITIVE_INFINITY
        var readableTailSlack = 0f
        fun lastLineVisibility(): Pair<Boolean, String> {
            val fieldId = input.fetchSemanticsNode().id
            val field = composeRule.onNode(androidx.compose.ui.test.SemanticsMatcher("the same transcript's unmerged coordinates") {
                it.id == fieldId
            }, useUnmergedTree = true).fetchSemanticsNode()
            fun descendants(node: androidx.compose.ui.semantics.SemanticsNode): Sequence<androidx.compose.ui.semantics.SemanticsNode> =
                node.children.asSequence().flatMap { sequenceOf(it) + descendants(it) }
            val measuredNodes = (sequenceOf(field) + descendants(field)).toList()
            val textLayers = measuredNodes.drop(1).filter {
                // The paragraph's real drawing layer may be shorter than its
                // TextLayoutResult precisely because the field clips it. Do not
                // exclude that layer before checking its actual clip rectangle.
                it.children.isEmpty() && it.layoutInfo.width == layout.size.width &&
                    !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)
            }
            // This diagnostic contains only layout numbers and property names, never
            // Text / EditableText values or the contents of a semantics dump.
            val coordinateDiagnostic = measuredNodes.joinToString("; ") { node ->
                "id=${node.id},size=${node.layoutInfo.width}x${node.layoutInfo.height}," +
                    "children=${node.children.size},keys=${node.config.map { it.key.name }}," +
                    "bounds=${node.boundsInRoot},origin=${node.layoutInfo.coordinates.localToRoot(androidx.compose.ui.geometry.Offset.Zero)}"
            }
            org.junit.Assert.assertEquals(
                "Resolve the real paragraph origin, not the decorated field's padding: paragraph=${layout.size}, lines=${layout.lineCount}, nodes=[$coordinateDiagnostic]",
                1, textLayers.size,
            )
            val coordinates = textLayers.single().layoutInfo.coordinates
            val origin = coordinates.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
            val clip = coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = true)
                .intersect(paper.fetchSemanticsNode().boundsInRoot)
            val line = layout.lineCount - 1
            val boxes = (layout.getLineStart(line) until layout.getLineEnd(line, visibleEnd = true))
                .filter { !transcript[it].isWhitespace() }.map { layout.getBoundingBox(it).translate(origin) }
            val usable = if (clip.width > 0f && clip.height > 0f) clip else paper.fetchSemanticsNode().boundsInRoot
            remainingTailDistance = (boxes.maxOfOrNull { it.bottom } ?: Float.POSITIVE_INFINITY) - usable.bottom
            readableTailSlack = (usable.height - (boxes.firstOrNull()?.height ?: usable.height)).coerceAtLeast(0f)
            val visible = boxes.isNotEmpty() && !layout.isLineEllipsized(line) &&
                layout.getLineEnd(line) == transcript.length && boxes.all { box ->
                    box.top >= clip.top - 1f && box.bottom <= clip.bottom + 1f &&
                        box.left >= clip.left - 1f && box.right <= clip.right + 1f
                }
            return visible to "lastLine=$line paragraph=${layout.size} origin=$origin clip=$clip glyphs=$boxes scroll=${position()}"
        }
        val initialPosition = position()
        var visibility = lastLineVisibility()
        var gestures = 0
        while (!visibility.first && gestures < 180) {
            val range = paper.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            if (range.value() >= range.maxValue() - 1f) break
            // Move through distant content efficiently, then use small physical drags so the
            // last line cannot jump over a short viewport's narrow readable interval.
            paper.performTouchInput {
                val distance = (remainingTailDistance + readableTailSlack / 2f + touchSlop)
                    .coerceIn(touchSlop + 2f, height * .70f)
                swipe(androidx.compose.ui.geometry.Offset(centerX, height * .85f),
                    androidx.compose.ui.geometry.Offset(centerX, height * .85f - distance), durationMillis = 300)
            }
            composeRule.waitForIdle()
            gestures += 1
            visibility = lastLineVisibility()
        }
        captureLongTranscriptFixtureIfRequested()
        org.junit.Assert.assertTrue("The outer paper must respond to actual touch scrolling", gestures > 0 && position() > initialPosition)
        if (!visibility.first) {
            val range = paper.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            org.junit.Assert.assertTrue("Gesture budget exhausted before a product verdict: gestures=$gestures value=${range.value()} max=${range.maxValue()} touchSlop=$touchSlop ${visibility.second}",
                range.value() >= range.maxValue() - 1f)
        }
        org.junit.Assert.assertTrue("The last line's actual glyph boxes must fit the clipped paper, not just exist in semantics: ${visibility.second}", visibility.first)
        input.assertIsNotEnabled()
        composeRule.onNodeWithText("播放").assertIsNotEnabled()
        val retry = composeRule.onNodeWithText("重试保存").assertIsDisplayed().assertIsEnabled()
        org.junit.Assert.assertTrue("The retry control must remain a usable touch target", retry.fetchSemanticsNode().boundsInRoot.height >= 72f)
        retry.performClick()
        org.junit.Assert.assertEquals(listOf(FullSentenceSpeakingAction.RetryRequested), actions)
    }

    @Test
    fun progressFailureIsVisibleAndLongFeedbackCanBeReadWithoutHidingActions() {
        val state = FullSentenceSpeakingUiState(
            chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true))),
            currentFocusSentenceId = "s1", attemptIndex = 4, progressFailed = true,
            feedbackZh = "这是需要读完整的反馈。".repeat(80) + "反馈末尾",
        )
        composeRule.setContent { RlTheme { FullSentenceSpeakingScreen(state) {} } }
        composeRule.onNodeWithText("学习进度未能保存或恢复，请重试；不会重置已保存轮次。").assertIsDisplayed()
        composeRule.onNodeWithText(state.feedbackZh!!).performScrollTo()
        composeRule.onNodeWithText("开始复述").assertIsDisplayed()
    }

    @Test
    fun answerPlaybackIsDisabledWhileAnEvaluationIsBeingSubmitted() {
        val state = FullSentenceSpeakingUiState(
            chapter = FixedLearningChapter("c1", 1, listOf(FixedLearningSentence("s1", "I felt calm.", "我很平静。", true))),
            currentFocusSentenceId = "s1", attemptIndex = 4, submitting = true,
        )
        composeRule.setContent { RlTheme { FullSentenceSpeakingScreen(state) {} } }
        composeRule.onNodeWithText("播放").assertIsNotEnabled()
    }

    @Test
    fun showsIndependentRetellingWorkspaceAndDispatchesFinish() {
        val actions = mutableListOf<FullSentenceSpeakingAction>()
        val state = FullSentenceSpeakingUiState(
            chapter = FixedLearningChapter(
                "c1",
                1,
                listOf(
                    FixedLearningSentence(
                        "s1",
                        "But in the end, I worked up the courage to explain my idea clearly.",
                        "但最后还是鼓起勇气把想法讲清楚了。",
                        true,
                    ),
                ),
            ),
            currentFocusSentenceId = "s1",
            attemptIndex = 4,
            recording = true,
            transcript = "But in the end, I worked up the courage to explain my idea clearly.",
        )

        composeRule.setContent { RlTheme { FullSentenceSpeakingScreen(state, actions::add) } }

        composeRule.onNodeWithTag("full-sentence-workspace").assertIsDisplayed()
        composeRule.onNodeWithTag("full-sentence-attempt").assertIsDisplayed()
        composeRule.onNodeWithText("正在收音").assertIsDisplayed()
        composeRule.onNodeWithText("实时转写（可修改）").assertIsDisplayed()
        composeRule.onNodeWithText("复述完毕").assertIsDisplayed().performClick()
        assert(actions == listOf(FullSentenceSpeakingAction.FinishedRequested))
    }

    private fun savedPassState(progressFailed: Boolean) = FullSentenceSpeakingUiState(
        chapter = FixedLearningChapter("screen-only-chapter", 1, listOf(
            FixedLearningSentence("context-before", "I was a little nervous during today's meeting.", "今天开会时，我本来有点紧张。", false),
            FixedLearningSentence("screen-only-focus", "But in the end, I worked up the courage to explain my idea clearly.", "但最后还是鼓起勇气把想法讲清楚了。", true),
            FixedLearningSentence("context-after", "My colleague nodded after listening.", "同事听完后点了点头。", false),
        )),
        currentFocusSentenceId = "screen-only-focus",
        attemptIndex = 4,
        transcript = "But in the end, I worked up the courage to explain my idea clearly.",
        transcriptReadyForConfirmation = true,
        evaluationReceived = true,
        finalDecision = SpeakingEvaluationDecision.PASS,
        decisionSaveFailed = false,
        progressFailed = progressFailed,
    )

    private fun assertDisabledTranscriptReadable(state: FullSentenceSpeakingUiState, primaryText: String) {
        val actions = mutableListOf<FullSentenceSpeakingAction>()
        composeRule.setContent { RlTheme { RlPaperBackground {
            FullSentenceSpeakingScreen(state, actions::add)
        } } }
        val transcript = composeRule.onNodeWithText(state.transcript)
        transcript.performScrollTo().assertIsDisplayed().assertIsNotEnabled().performClick()
        composeRule.onNodeWithText("播放").assertIsNotEnabled().performClick()
        composeRule.onNodeWithText(primaryText).assertIsDisplayed().assertIsNotEnabled().performClick()
        org.junit.Assert.assertTrue("Readability must not be obtained by enabling a protected action", actions.isEmpty())
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        transcript.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
            org.junit.Assert.assertTrue(it(layouts))
        }
        val layout = layouts.single()
        org.junit.Assert.assertEquals(state.transcript, layout.layoutInput.text.text)
        val color = layout.layoutInput.style.color
        // An unresolved layout color is not a product-color failure; it requires pixel ROI evidence.
        org.junit.Assume.assumeTrue("Resolved text color unavailable: glyph ROI validation is required",
            color != androidx.compose.ui.graphics.Color.Unspecified)
        org.junit.Assert.assertTrue("Disabled transcript must retain opaque Ink or TextSecondary, actual=$color",
            color == com.reallanguage.app.core.designsystem.RlColor.Ink ||
                color == com.reallanguage.app.core.designsystem.RlColor.TextSecondary)
    }

    private fun captureLongTranscriptFixtureIfRequested() {
        val phase = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("speakingLongTranscriptSnapshot") ?: return
        require(phase in setOf("before", "after"))
        val bitmap = composeRule.onNodeWithTag("full-speaking-long-transcript-viewport")
            .captureToImage().asAndroidBitmap()
        try {
            val folder = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .targetContext.getExternalFilesDir(null), "visual-qa")
            check(folder.isDirectory || folder.mkdirs())
            val output = java.io.File(folder, "27-speaking-long-transcript-$phase.png")
            check(output.canonicalFile.parentFile == folder.canonicalFile)
            output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun captureRetryFixtureIfRequested() {
        val requested = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("speakingRetrySnapshot") ?: return
        require(requested == "after")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("full-sentence-attempt").assertTextEquals("英文朗读 · 第 3 次")
        composeRule.onNodeWithTag("learning-current-trailing-arrow").assertIsDisplayed()
        composeRule.onNodeWithTag("learning-header-exit").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val folder = java.io.File(instrumentation.targetContext.filesDir, "visual-qa")
        check(folder.isDirectory || folder.mkdirs())
        val output = java.io.File(folder, "full-speaking-retry-after.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("holdSpeakingRetrySnapshot") == "true") {
            android.os.SystemClock.sleep(15_000)
        }
    }

    private fun captureAiFeedbackFixtureIfRequested() {
        val requested = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("aiFeedbackSnapshot") ?: return
        require(requested == "after")
        composeRule.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(200, 5_000)
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
        check(folder.isDirectory || folder.mkdirs())
        val output = java.io.File(folder, "full-speaking-ai-feedback-after.png")
        val bitmap = composeRule.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        try {
            output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        android.os.SystemClock.sleep(15_000)
    }

    /** Rendered state fixture only: no recording, evaluation, saved result or credential is generated. */
    private fun captureSavedPassProgressFixtureIfRequested() {
        val phase = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("speakingProgressSnapshot") ?: return
        require(phase in setOf("before", "after"))
        composeRule.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val transcript = savedPassState(progressFailed = true).transcript
        val error = "学习进度未能保存或恢复，请重试；不会重置已保存轮次。"
        val primaryMatcher = androidx.compose.ui.test.hasText("开始复述") or androidx.compose.ui.test.hasText("重试保存")
        fun visibleStateBounds(text: String): Pair<androidx.compose.ui.geometry.Rect, androidx.compose.ui.geometry.Rect> {
            composeRule.onNodeWithText(text).assertIsDisplayed()
            composeRule.onNode(primaryMatcher).assertIsDisplayed()
            composeRule.onNodeWithTag("full-sentence-attempt").assertTextEquals("独立复述 · 第 5 次")
            return composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot to
                composeRule.onNode(primaryMatcher).fetchSemanticsNode().boundsInRoot
        }
        val view = java.util.concurrent.atomic.AtomicReference<android.view.View>()
        instrumentation.runOnMainSync {
            val activities = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            check(activities.size == 1) { "A single active test window is required for this screenshot" }
            val activity = activities.single()
            check(activity is androidx.activity.ComponentActivity)
            view.set(activity.window.decorView)
        }
        fun capture(suffix: String, visibleText: String) {
            val beforeDraw = visibleStateBounds(visibleText)
            val drawn = java.util.concurrent.CountDownLatch(1)
            val listener = android.view.ViewTreeObserver.OnDrawListener { drawn.countDown() }
            instrumentation.runOnMainSync {
                view.get().viewTreeObserver.addOnDrawListener(listener)
                view.get().postInvalidateOnAnimation()
            }
            try {
                check(drawn.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "The test window did not redraw" }
            } finally {
                instrumentation.runOnMainSync {
                    if (view.get().viewTreeObserver.isAlive) view.get().viewTreeObserver.removeOnDrawListener(listener)
                }
            }
            instrumentation.uiAutomation.waitForIdle(200, 5_000)
            org.junit.Assert.assertEquals("The local scroll position must not change while drawing", beforeDraw, visibleStateBounds(visibleText))
            val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "visual-qa")
            check(folder.isDirectory || folder.mkdirs())
            val output = java.io.File(folder, "27-speaking-progress-$phase$suffix.png")
            check(output.canonicalFile.parentFile == folder.canonicalFile)
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                org.junit.Assert.assertEquals("The captured frame must retain its text and main-button positions", beforeDraw, visibleStateBounds(visibleText))
                output.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        capture("", error)
        composeRule.onNodeWithText(transcript).performScrollTo().assertIsDisplayed()
        composeRule.waitForIdle()
        capture("-transcript", transcript)
    }
}
