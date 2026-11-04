package com.reallanguage.app.feature.learning.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.feature.learning.domain.FixedLearningChapter
import com.reallanguage.app.feature.learning.domain.FixedLearningSentence
import com.reallanguage.app.feature.learning.domain.normalizedEnglishTypography

/** Real-text-height content scrolls as one page; primary actions stay in a separate dock. */
@Composable
fun ChapterLearningFrame(
    chapter: FixedLearningChapter,
    currentFocusSentenceId: String,
    modifier: Modifier = Modifier,
    showCurrentFocusChinese: Boolean = true,
    showCurrentFocusEnglish: Boolean = true,
    hideAllFocusEnglish: Boolean = false,
    stageTitle: String = "真实表达",
    stageSupporting: String = "把熟悉的内容，变成真正会用的英语。",
    onBack: (() -> Unit)? = null,
    leftPaneWeight: Float = .88f,
    rightPaneWeight: Float = 1.36f,
    englishPaneWeight: Float = 1.18f,
    leftContentEndPadding: Dp = RlSpace.Space40,
    modulePaper: Boolean = true,
    modulePaperSafeContent: Boolean = false,
    showModuleSidebar: Boolean = true,
    leftArtworkRes: Int? = null,
    leftArtworkPreferredHeight: Dp = 0.dp,
    leftArtworkOverlayRes: Int? = null,
    leftArtworkOverlaySize: Dp = 0.dp,
    leftArtworkOverlayReserve: Dp = 0.dp,
    modulePaperMinHeight: Dp = 0.dp,
    contextTopAccent: Boolean = true,
    focusSentenceMinHeight: Dp = 0.dp,
    compactContextRowsOnShortScreen: Boolean = false,
    focusOnlyContext: Boolean = false,
    fingerReadingFocusStyle: Boolean = false,
    preserveLedgerHoleAspect: Boolean = false,
    leftPanelContent: (@Composable ColumnScope.() -> Unit)? = null,
    leftArtworkSupplement: (@Composable ColumnScope.() -> Unit)? = null,
    interactiveEnglishContent: (@Composable (FixedLearningSentence, Modifier) -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    moduleScrollState: ScrollState? = null,
    fixedPageWithModuleOverflow: Boolean = false,
    moduleContent: @Composable () -> Unit,
) {
    LearningPage(modifier) {
        val density = LocalDensity.current
        val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
        val compact = maxWidth < 600.dp * density.fontScale.coerceAtMost(1.5f)
        val compactContextRows = compactContextRowsOnShortScreen && maxHeight < 440.dp && !compact
        var headerHeight by remember { mutableStateOf(48.dp) }
        var ledgerHeight by remember { mutableStateOf(120.dp) }
        var footerHeight by remember { mutableStateOf(if (footer == null) 0.dp else 48.dp) }
        val rememberedPageScrollState = rememberScrollState()
        val pageScrollState = if (fixedPageWithModuleOverflow) rememberedPageScrollState
            else moduleScrollState ?: rememberedPageScrollState
        val bodyMinHeight = if (compact || density.fontScale > 1.2f) 160.dp
            else (maxHeight - headerHeight - ledgerHeight - footerHeight - 40.dp).coerceAtLeast(120.dp)
        val pageSectionSpacing = if (fixedPageWithModuleOverflow) 4.dp else 7.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 12.dp else 22.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(pageSectionSpacing)) {
        Column(Modifier.weight(1f).fillMaxWidth().testTag("learning-page-scroll-viewport")
            .then(if (fixedPageWithModuleOverflow) Modifier else Modifier.verticalScroll(pageScrollState)),
            verticalArrangement = Arrangement.spacedBy(pageSectionSpacing)) {
            LearningHeading("第 ${chapter.chapterNumber} 章 · $stageTitle",
                Modifier.testTag("learning-page-heading").onSizeChanged { headerHeight = with(density) { it.height.toDp() } }, onBack = onBack)
            LearningSentenceLedger(chapter, currentFocusSentenceId,
                modifier = Modifier.fillMaxWidth().testTag("learning-context-paper")
                    .onSizeChanged { ledgerHeight = with(density) { it.height.toDp() } }
                    .then(if (keyboardVisible) Modifier.testTag("learning-keyboard-context") else Modifier),
                showCurrentFocusChinese = showCurrentFocusChinese,
                showCurrentFocusEnglish = showCurrentFocusEnglish,
                hideAllFocusEnglish = hideAllFocusEnglish,
                englishPaneWeight = englishPaneWeight,
                focusSentenceMinHeight = if (fingerReadingFocusStyle) maxOf(focusSentenceMinHeight, 68.dp) else focusSentenceMinHeight,
                largeFocus = fingerReadingFocusStyle,
                compactRows = compactContextRows,
                focusOnly = focusOnlyContext,
                preserveHoleAspect = preserveLedgerHoleAspect,
                interactiveEnglishContent = interactiveEnglishContent)
            val sidebar: @Composable (Modifier) -> Unit = { pane ->
                val sidebarHeight = if (fixedPageWithModuleOverflow) pane.fillMaxHeight()
                    else pane.heightIn(min = bodyMinHeight)
                if (leftArtworkRes == null) Box(sidebarHeight) {
                    Image(painterResource(R.drawable.rl_v73_learning_expression_blue_paper), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    Column(Modifier.fillMaxSize().padding(
                        start = 24.dp,
                        end = leftContentEndPadding,
                        top = if (fixedPageWithModuleOverflow) 8.dp else 24.dp,
                        bottom = if (fixedPageWithModuleOverflow) 8.dp else 28.dp,
                    ), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (leftPanelContent != null) leftPanelContent()
                        else {
                            Text(stageTitle, color = Color.White, fontFamily = EditorialFont.Serif,
                                fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 35.sp)
                            Text(stageSupporting, color = Color.White.copy(alpha = .88f), fontSize = 15.sp, lineHeight = 22.sp)
                        }
                    }
                } else Column(pane, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val artwork = if (leftArtworkRes == R.drawable.rl_v73_learning_cassette)
                        BitmapPainter(ImageBitmap.imageResource(leftArtworkRes), srcOffset = IntOffset(30, 235), srcSize = IntSize(1065, 1035))
                        else painterResource(leftArtworkRes)
                    val artworkHeight = if (compact) 190.dp else maxOf(bodyMinHeight, leftArtworkPreferredHeight)
                    if (leftArtworkOverlayRes == null) {
                        Image(artwork, null, Modifier.fillMaxWidth().height(artworkHeight), contentScale = ContentScale.Fit)
                    } else {
                        Box(Modifier.fillMaxWidth().height(artworkHeight)) {
                            Image(
                                artwork,
                                null,
                                Modifier.fillMaxWidth().height((artworkHeight - leftArtworkOverlayReserve).coerceAtLeast(80.dp))
                                    .align(Alignment.TopCenter),
                                contentScale = ContentScale.Fit,
                            )
                            Image(
                                painterResource(leftArtworkOverlayRes),
                                null,
                                Modifier.size(leftArtworkOverlaySize).align(Alignment.BottomCenter),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                    leftArtworkSupplement?.invoke(this)
                    if (compact || footer == null) Box(Modifier.fillMaxWidth().background(RlColor.Secondary.copy(alpha = .95f))) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { leftPanelContent?.invoke(this) }
                    }
                }
            }
            val workspace: @Composable (Modifier) -> Unit = { pane ->
                val internalScroll = moduleScrollState ?: rememberScrollState()
                val workspaceHeight = if (fixedPageWithModuleOverflow) pane.fillMaxHeight()
                    else pane.heightIn(min = bodyMinHeight)
                Column(workspaceHeight.testTag("learning-page-workspace")) {
                    if (modulePaper) LearningPaper(
                        Modifier.fillMaxWidth().heightIn(min = modulePaperMinHeight).testTag("learning-module-paper"),
                        padding = PaddingValues(10.dp), ruled = true, contentSpacing = 6.dp,
                    ) { moduleContent() }
                    else if (fixedPageWithModuleOverflow) {
                        Box(Modifier.fillMaxSize().testTag("learning-module-scroll-window")) {
                            val overflowing by remember { derivedStateOf { internalScroll.maxValue > 0 } }
                            Column(
                                Modifier.fillMaxSize().padding(start = 8.dp, end = 14.dp, top = 2.dp, bottom = 2.dp)
                                    .verticalScroll(internalScroll, enabled = overflowing)
                                    .testTag("learning-module-scroll-content"),
                            ) { moduleContent() }
                            if (overflowing) LearningOverflowScrollbar(internalScroll, Modifier.align(Alignment.CenterEnd))
                        }
                    } else Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) { moduleContent() }
                }
            }
            val bodyModifier = if (fixedPageWithModuleOverflow) Modifier.fillMaxWidth().weight(1f) else Modifier
            if (showModuleSidebar) LearningTwoPane(modifier = bodyModifier,
                leftWeight = leftPaneWeight / (leftPaneWeight + rightPaneWeight),
                contentFirstWhenStacked = leftArtworkRes != null,
                // Landscape phones have about 596dp after safe-area padding. This
                // learning screen is designed as a two-pane sheet at that width;
                // stacking would let the left card consume all remaining height
                // and collapse the explanation pane to zero.
                minimumTwoPaneWidth = if (fixedPageWithModuleOverflow) 520.dp else 600.dp,
                fillAvailableHeight = fixedPageWithModuleOverflow,
                left = sidebar, right = workspace)
            else workspace(if (fixedPageWithModuleOverflow) bodyModifier else Modifier.fillMaxWidth())
            if (!fixedPageWithModuleOverflow) Spacer(Modifier.height(6.dp))
        }
        footer?.let { actions ->
            Row(Modifier.fillMaxWidth().testTag("learning-page-footer").onSizeChanged { footerHeight = with(density) { it.height.toDp() } },
                horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showModuleSidebar && !compact) {
                    if (leftArtworkRes == null) Spacer(Modifier.weight(leftPaneWeight))
                    else Box(Modifier.weight(leftPaneWeight).heightIn(min = 44.dp).testTag("learning-footer-status-paper")) {
                        Image(learningLabelPainter(), null, Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds, colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(RlColor.Secondary))
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("learning-footer-status-content"), verticalArrangement = Arrangement.spacedBy(4.dp)) { leftPanelContent?.invoke(this) }
                    }
                }
                Row(if (showModuleSidebar && !compact) Modifier.weight(rightPaneWeight) else Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
        }
    }
}

/** A quiet paper-ledger scrollbar appears only when the explanation is taller
 * than its viewport. Short explanations remain completely fixed. */
@Composable
private fun LearningOverflowScrollbar(state: ScrollState, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.width(5.dp).fillMaxHeight().padding(vertical = 5.dp)
            .background(RlColor.Border.copy(alpha = .38f), androidx.compose.foundation.shape.CircleShape)
            .testTag("learning-module-overflow-scrollbar"),
    ) {
        val viewportPx = with(LocalDensity.current) { maxHeight.toPx() }
        val totalPx = viewportPx + state.maxValue
        val thumbFraction = if (totalPx <= 0f) 1f else (viewportPx / totalPx).coerceIn(.2f, 1f)
        val thumbHeight = maxHeight * thumbFraction
        val progress = if (state.maxValue == 0) 0f else state.value.toFloat() / state.maxValue
        val thumbOffset = (maxHeight - thumbHeight) * progress
        Box(
            Modifier.offset(y = thumbOffset).fillMaxWidth().height(thumbHeight)
                .background(RlColor.ActionVivid.copy(alpha = .82f), androidx.compose.foundation.shape.CircleShape),
        )
    }
}

@Composable
internal fun LearningSentenceLedger(
    chapter: FixedLearningChapter,
    currentFocusSentenceId: String,
    modifier: Modifier = Modifier,
    showCurrentFocusChinese: Boolean = true,
    showCurrentFocusEnglish: Boolean = true,
    hideAllFocusEnglish: Boolean = false,
    englishPaneWeight: Float = 1.18f,
    focusSentenceMinHeight: Dp = 0.dp,
    largeFocus: Boolean = false,
    compactRows: Boolean = false,
    focusOnly: Boolean = false,
    preserveHoleAspect: Boolean = false,
    uniformColumnGrid: Boolean = false,
    overviewRowMinHeight: Dp = 0.dp,
    interactiveEnglishContent: (@Composable (FixedLearningSentence, Modifier) -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        val stacked = maxWidth < 520.dp * LocalDensity.current.fontScale.coerceAtMost(1.5f)
        Box(Modifier.fillMaxWidth()) {
            if (preserveHoleAspect) {
                // Stretch only the paper body. The separate punch-hole strip is
                // fitted at its source aspect ratio, so every hole remains circular.
                Image(BitmapPainter(ImageBitmap.imageResource(R.drawable.rl_v73_learning_context_paper),
                    srcOffset = IntOffset(96, 128), srcSize = IntSize(2048, 466)), null,
                    Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                Box(Modifier.matchParentSize()) {
                    Image(
                        painterResource(R.drawable.rl_focus_punch_holes_v2),
                        null,
                        Modifier.fillMaxHeight()
                            .aspectRatio(320f / 1536f, matchHeightConstraintsFirst = true)
                            .align(Alignment.CenterStart)
                            .testTag("learning-round-hole-strip"),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                Image(BitmapPainter(ImageBitmap.imageResource(R.drawable.rl_v73_learning_context_paper),
                    srcOffset = IntOffset(28, 128), srcSize = IntSize(2116, 466)), null,
                    Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            }
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 10.dp, top = 7.dp, bottom = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val visibleSentences = if (focusOnly) chapter.sentences.filter {
                    it.isFocus && it.sentenceId == currentFocusSentenceId
                } else chapter.sentences
                visibleSentences.forEach { sentence ->
                    val current = sentence.isFocus && sentence.sentenceId == currentFocusSentenceId
                    Box(Modifier.fillMaxWidth().heightIn(min = maxOf(overviewRowMinHeight, if (current) maxOf(if (compactRows) 30.dp else 40.dp, focusSentenceMinHeight) else if (compactRows) 28.dp else 33.dp))
                        .then(if (current) Modifier.testTag("learning-focus-${sentence.sentenceId}") else Modifier), contentAlignment = Alignment.Center) {
                        if (current) Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = if (compactRows) 3.dp else if (current) 8.dp else 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                null,
                                Modifier.size(16.dp).then(
                                    if (current) Modifier.testTag("learning-current-leading-arrow") else Modifier,
                                ),
                                tint = if (current) RlColor.ActionVivid else RlColor.TextMuted.copy(alpha = .55f),
                            )
                            val english: @Composable (Modifier) -> Unit = { textModifier ->
                                if ((current && !showCurrentFocusEnglish) || (hideAllFocusEnglish && sentence.isFocus)) {
                                    Text(stringResource(R.string.learning_answer_hidden), textModifier, color = RlColor.TextSecondary,
                                        fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 22.sp)
                                } else if (current && interactiveEnglishContent != null) interactiveEnglishContent(sentence, textModifier)
                                else Text(sentence.textEn.normalizedEnglishTypography(), textModifier, color = if (current) RlColor.Ink else RlColor.TextSecondary,
                                    fontFamily = EditorialFont.Serif, fontSize = if (overviewRowMinHeight > 0.dp) (if (compactRows) 17.sp else 19.sp) else if (largeFocus && current) 21.sp else 14.sp,
                                    lineHeight = if (overviewRowMinHeight > 0.dp) (if (compactRows) 23.sp else 27.sp) else if (largeFocus && current) 29.sp else 20.sp)
                            }
                            val chinese: @Composable (Modifier) -> Unit = { textModifier ->
                                if (!current || showCurrentFocusChinese) Text(sentence.textZh, textModifier,
                                    color = if (current) RlColor.Ink else RlColor.TextSecondary,
                                    fontFamily = EditorialFont.Serif, fontSize = if (overviewRowMinHeight > 0.dp) (if (compactRows) 14.sp else 16.sp) else 14.sp, lineHeight = if (overviewRowMinHeight > 0.dp) (if (compactRows) 21.sp else 24.sp) else 20.sp)
                            }
                            if (stacked) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                english(Modifier.fillMaxWidth().testTag("learning-english-${sentence.sentenceId}"))
                                chinese(Modifier.fillMaxWidth().testTag("learning-chinese-${sentence.sentenceId}"))
                            } else if (uniformColumnGrid) {
                                Box(
                                    Modifier.weight(englishPaneWeight).align(Alignment.CenterVertically),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    english(Modifier.fillMaxWidth().testTag("learning-english-${sentence.sentenceId}"))
                                }
                                Box(
                                    Modifier.width(1.dp).height(29.dp)
                                        .background(RlColor.BorderStrong.copy(alpha = .38f))
                                        .testTag("learning-divider-${sentence.sentenceId}"),
                                )
                                Box(
                                    Modifier.weight(.9f).align(Alignment.CenterVertically).padding(start = 12.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    chinese(Modifier.fillMaxWidth().testTag("learning-chinese-${sentence.sentenceId}"))
                                }
                                // Reserve an identical arrow slot in every row so the
                                // active row cannot move the shared divider to the left.
                                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                    if (current) Icon(
                                        Icons.Rounded.PlayArrow,
                                        null,
                                        Modifier.size(16.dp).graphicsLayer { scaleX = -1f }
                                            .testTag("learning-current-trailing-arrow"),
                                        tint = RlColor.ActionVivid,
                                    )
                                }
                            } else {
                                english(Modifier.weight(englishPaneWeight).testTag("learning-english-${sentence.sentenceId}"))
                                Box(Modifier.width(1.dp).height(29.dp).background(RlColor.BorderStrong.copy(alpha = .38f)))
                                chinese(Modifier.weight(.9f).padding(start = 12.dp).testTag("learning-chinese-${sentence.sentenceId}"))
                                if (current) Icon(
                                    Icons.Rounded.PlayArrow,
                                    null,
                                    Modifier.size(16.dp).graphicsLayer { scaleX = -1f }
                                        .testTag("learning-current-trailing-arrow"),
                                    tint = RlColor.ActionVivid,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Writing's physical canvas has a safe minimum; the containing page scrolls on short screens. */
@Composable
fun ChapterWritingFrame(
    chapter: FixedLearningChapter,
    currentFocusSentenceId: String,
    stageTitle: String,
    modifier: Modifier = Modifier,
    hideAllFocusEnglish: Boolean = false,
    hideCurrentFocusEnglish: Boolean = false,
    workspaceArtworkRes: Int? = null,
    workspaceMinHeight: Dp = 160.dp,
    centerLeftPanelContent: Boolean = false,
    onBack: (() -> Unit)? = null,
    leftPanelContent: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    moduleContent: @Composable ColumnScope.() -> Unit,
) {
    LearningPage(modifier) {
        val density = LocalDensity.current
        val compact = maxWidth < 600.dp * density.fontScale.coerceAtMost(1.5f)
        var headerHeight by remember { mutableStateOf(48.dp) }
        var ledgerHeight by remember { mutableStateOf(120.dp) }
        var footerHeight by remember { mutableStateOf(48.dp) }
        val calculatedWorkspaceHeight = if (compact || density.fontScale > 1.2f || maxHeight < 360.dp) {
            (maxHeight * .58f).coerceAtLeast(240.dp)
        } else (maxHeight - headerHeight - ledgerHeight - footerHeight - 44.dp).coerceAtLeast(160.dp)
        val workspaceHeight = maxOf(calculatedWorkspaceHeight, workspaceMinHeight)
        Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 12.dp else 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LearningHeading("第 ${chapter.chapterNumber} 章 · $stageTitle",
                Modifier.onSizeChanged { headerHeight = with(density) { it.height.toDp() } }, onBack = onBack)
            LearningSentenceLedger(chapter, currentFocusSentenceId, Modifier.fillMaxWidth().testTag("learning-context-paper")
                .onSizeChanged { ledgerHeight = with(density) { it.height.toDp() } },
                showCurrentFocusEnglish = !hideCurrentFocusEnglish, hideAllFocusEnglish = hideAllFocusEnglish,
                compactRows = true)
            LearningTwoPane(leftWeight = .18f,
                left = { pane -> LearningOliveNote(
                    modifier = if (centerLeftPanelContent) pane.height(workspaceHeight) else pane,
                    contentVerticalArrangement = if (centerLeftPanelContent) Arrangement.Center else Arrangement.spacedBy(8.dp),
                ) {
                        if (leftPanelContent != null) leftPanelContent() else Text(stageTitle, color = Color.White,
                            fontFamily = EditorialFont.Serif, fontSize = 20.sp)
                } },
                right = { pane -> Column(pane.height(workspaceHeight)) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Image(painterResource(R.drawable.rl_ruled_handwriting_paper), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                        Column(
                            Modifier.fillMaxSize().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            content = moduleContent,
                        )
                    }
                    workspaceArtworkRes?.let { artworkRes ->
                        Image(
                            painter = painterResource(artworkRes),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(76.dp)
                                .testTag("chapter-writing-workspace-artwork"),
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.BottomCenter,
                        )
                    }
                } })
            Spacer(Modifier.height(8.dp))
        }
            Box(Modifier.fillMaxWidth().onSizeChanged { footerHeight = with(density) { it.height.toDp() } }) { footer?.invoke() }
        }
    }
}
