package com.reallanguage.app.feature.formation.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.EditorialCompactAction
import com.reallanguage.app.feature.learning.presentation.LearningRibbon
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType
import com.reallanguage.app.feature.formation.domain.QueryableExpression
import com.reallanguage.app.feature.formation.domain.wordExpressions
import kotlin.math.max
import kotlin.math.min

@Composable
fun LearningTargetScreen(
    state: LearningTargetUiState,
    onAction: (LearningTargetAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("learning_targets_page")) {
        val wide = maxWidth >= 600.dp
        val largeText = LocalDensity.current.fontScale >= 1.3f
        val shortScreen = maxHeight < 400.dp
        val pageWidth = maxWidth
        val pageHeight = maxHeight
        val horizontalContentInset = maxWidth * .092f
        val chapterNumber = state.chapters.indexOfFirst { chapter ->
            chapter.sentences.any { it.unitId == state.currentSentence?.unitId }
        }.plus(1).coerceAtLeast(1)
        key(state.currentSentence?.unitId, state.selectedCandidateIds[state.currentSentence?.unitId]) {
            Box(Modifier.fillMaxSize()) {
            if (wide && !largeText) Image(painterResource(R.drawable.rl_v73_formation_frame), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            EditorialSheet(
                modifier = Modifier.fillMaxSize().padding(if (wide && !shortScreen) 10.dp else 5.dp),
                contentPadding = if (wide && !shortScreen) 5.dp else 2.dp,
                contentSpacing = if (wide) 2.dp else 8.dp,
                showSpine = false,
                showTopAccent = false,
                contentModifier = Modifier.fillMaxSize(),
                renderPaper = !wide || largeText,
            ) {
                if (largeText || !wide) {
                    AccessibleLearningTargets(state, onAction, chapterNumber)
                } else {
                    LearningTargetHeader(chapterNumber, onAction, shortScreen = shortScreen)
                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                            .padding(top = if (shortScreen) 2.dp else 5.dp)
                            .padding(start = horizontalContentInset, end = horizontalContentInset),
                        verticalArrangement = Arrangement.spacedBy(if (shortScreen) 6.dp else 10.dp),
                    ) {
                        LearningSentencePanel(state, onAction, Modifier.fillMaxWidth().heightIn(min = if (shortScreen) 52.dp else 66.dp))
                        LearningTargetCards(state, onAction, wide = true)
                        AdditionalExpressionSection(state, onAction, wide = true)
                    }
                    LearningTargetFooter(state, onAction)
                }
            }
            if (wide && !largeText) LearningTargetTabs(Modifier.align(Alignment.CenterEnd).padding(end = 3.dp))
            if (wide && !largeText) FormationFrameChapterBadge("第 $chapterNumber 章", pageWidth, pageHeight)
            }
        }
    }
}

@Composable
private fun LearningTargetHeader(
    chapterNumber: Int,
    onAction: (LearningTargetAction) -> Unit,
    adaptiveHeight: Boolean = false,
    shortScreen: Boolean = false,
) {
    if (!adaptiveHeight) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(if (shortScreen) 54.dp else 68.dp).testTag("learning_targets_header")) {
            IconButton(onClick = { onAction(LearningTargetAction.BackRequested) }, modifier = Modifier.align(Alignment.CenterStart).padding(start = maxWidth * .09f)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink)
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("选择学习目标", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = if (shortScreen) 24.sp else 26.sp, lineHeight = if (shortScreen) 30.sp else 34.sp, letterSpacing = 3.sp)
                Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(182.dp).height(if (shortScreen) 5.dp else 7.dp), contentScale = ContentScale.FillBounds)
                Text("句子会继续学习，下面的词义与句式可以按需选择。", color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = if (shortScreen) 12.sp else 13.sp, lineHeight = if (shortScreen) 17.sp else 20.sp)
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp).testTag("learning_targets_header")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onAction(LearningTargetAction.BackRequested) },
            modifier = Modifier.size(48.dp),
        ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "选择学习目标", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 36.sp, letterSpacing = 3.sp,
            )
            Image(
                com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
                Modifier.width(182.dp).height(7.dp), contentScale = ContentScale.FillBounds,
            )
        }
        FormationChapterBadge("第 $chapterNumber 章", compact = true)
        }
        Text("句子会继续学习，下面的词义与句式可以按需选择。", Modifier.align(Alignment.CenterHorizontally).padding(top = 3.dp, bottom = 5.dp), color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun LearningSentencePanel(
    state: LearningTargetUiState,
    onAction: (LearningTargetAction) -> Unit,
    modifier: Modifier,
) {
    val sentence = state.currentSentence ?: return
    val english = sentence.candidates.firstOrNull { it.candidateId == state.selectedCandidateIds[sentence.unitId] }?.textEn.orEmpty()
    Box(modifier.testTag("learning_targets_sentence")) {
        Image(painterResource(R.drawable.rl_torn_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.currentSentenceNumber()}", Modifier.width(38.dp), color = RlColor.ActionVivid,
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 20.sp,
            )
            HighlightedEnglishSentence(
                english, sentence.unitId, state.currentTargets, state.selectedTargetIds,
                sentence.wordExpressions(state.selectedCandidateIds[sentence.unitId]),
                !state.generating && !state.submitting, onAction,
                Modifier.weight(1.22f).testTag("learning_targets_english"),
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(RlColor.BorderStrong.copy(alpha = .48f)))
            Text(
                sentence.textZh,
                Modifier.weight(.92f).padding(start = 18.dp).testTag("learning_targets_chinese"),
                color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 17.sp, lineHeight = 26.sp,
            )
            Icon(Icons.Rounded.Check, null, tint = RlColor.ActionVivid, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

@Composable
private fun HighlightedEnglishSentence(
    english: String,
    sentenceId: String,
    targets: List<LearningTarget>,
    selectedTargetIds: Set<String>,
    expressions: List<QueryableExpression>,
    enabled: Boolean,
    onAction: (LearningTargetAction) -> Unit,
    modifier: Modifier,
) {
    val marked = remember(english, sentenceId, targets, selectedTargetIds) {
        targets.filter { it.targetId in selectedTargetIds && it.type != LearningTargetType.PATTERN }.mapNotNull { target ->
            val literal = target.sourceEvidence?.surfaceForm
                ?: target.display.substringBefore(" + ").takeIf { english.contains(it, ignoreCase = true) }
                ?: target.structureParts.firstOrNull { english.contains(it, ignoreCase = true) }
                ?: return@mapNotNull null
            val evidence = target.sourceEvidence
            val start = if (evidence != null) {
                if (target.unitId != sentenceId || evidence.startUtf16 < 0 || evidence.endUtf16 > english.length ||
                    evidence.endUtf16 <= evidence.startUtf16 ||
                    !english.substring(evidence.startUtf16, evidence.endUtf16).equals(evidence.surfaceForm, ignoreCase = true)) return@mapNotNull null
                evidence.startUtf16
            } else english.indexOf(literal, ignoreCase = true)
            if (start < 0) null else Triple(start, start + literal.length, targetAccent(target.type))
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val text = buildAnnotatedString {
        append(english)
        expressions.forEach { expression ->
            val start = expression.expressionId.substringAfter("word:").toIntOrNull() ?: return@forEach
            val end = min(length, start + expression.display.length)
            if (start !in 0 until end) return@forEach
            addLink(
                LinkAnnotation.Clickable(
                    tag = expression.expressionId,
                    styles = TextLinkStyles(SpanStyle(color = RlColor.Ink, textDecoration = TextDecoration.None)),
                ) {
                    if (enabled) {
                        val existing = targets.firstOrNull { target ->
                            target.unitId == sentenceId && target.type != LearningTargetType.PATTERN &&
                                target.sourceEvidence?.let { it.startUtf16 == start && it.endUtf16 == end } == true
                        }
                        if (existing != null) onAction(LearningTargetAction.TargetToggled(existing.targetId))
                        else onAction(LearningTargetAction.ExpressionRequested(sentenceId, expression.expressionId))
                    }
                },
                start, end,
            )
        }
        marked.forEach { (start, end, color) ->
            addStyle(
                SpanStyle(
                    color = color,
                    background = color.copy(alpha = .09f),
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                ),
                start,
                end,
            )
        }
    }
    Text(
        text,
        modifier.drawBehind {
            val result = layout ?: return@drawBehind
            marked.forEach { (start, end, color) ->
                var current = start
                while (current < end && current < result.layoutInput.text.length) {
                    val line = result.getLineForOffset(current)
                    val lineEnd = min(end, result.getLineEnd(line, visibleEnd = true))
                    if (lineEnd > current) {
                        val first = result.getBoundingBox(current)
                        val last = result.getBoundingBox(lineEnd - 1)
                        drawRoundRect(
                            color = color.copy(alpha = .82f),
                            topLeft = androidx.compose.ui.geometry.Offset(first.left - 3.dp.toPx(), first.top - 1.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(last.right - first.left + 6.dp.toPx(), max(first.height, last.height) + 2.dp.toPx()),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                            style = Stroke(width = 1.15.dp.toPx()),
                        )
                    }
                    current = max(current + 1, lineEnd)
                }
            }
        },
        onTextLayout = { layout = it },
        color = RlColor.Ink,
        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        letterSpacing = (-.1).sp,
    )
}

@Composable
private fun LearningTargetCards(state: LearningTargetUiState, onAction: (LearningTargetAction) -> Unit, wide: Boolean) {
    if (state.currentTargets.isEmpty()) return
    if (wide) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 590.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.currentTargets.chunked(columns).forEach { rowTargets ->
            Row(Modifier.fillMaxWidth(if (rowTargets.size == 1) .48f else 1f).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTargets.forEach { target ->
                    LearningTargetCard(target, target.targetId in state.selectedTargetIds, onAction,
                        Modifier.weight(1f).fillMaxHeight(), independentRibbon = true)
                }
            }
        }
        }
        }
    } else {
        state.currentTargets.forEach { target ->
            LearningTargetCard(target, target.targetId in state.selectedTargetIds, onAction, Modifier.fillMaxWidth(), independentRibbon = true)
        }
    }
}

@Composable
private fun LearningTargetCard(
    target: LearningTarget,
    selected: Boolean,
    onAction: (LearningTargetAction) -> Unit,
    modifier: Modifier,
    independentRibbon: Boolean = false,
) {
    val accent = targetAccent(target.type)
    val fixedWide = target.type == LearningTargetType.FIXED_EXPRESSION && !independentRibbon
    val background = when (target.type) {
        LearningTargetType.WORD -> R.drawable.rl_learning_target_meaning_card
        LearningTargetType.FIXED_EXPRESSION -> R.drawable.rl_learning_target_collocation_card
        LearningTargetType.PATTERN -> R.drawable.rl_learning_target_pattern_card
    }
    Box(modifier.clickable { onAction(LearningTargetAction.TargetToggled(target.targetId)) }.testTag("learning_target_${target.targetId}")) {
        // The wide reference card contains its ribbon. A full-width stacked card must
        // measure the paper and ribbon separately so text never drifts off the color.
        Image(painterResource(if (independentRibbon) R.drawable.rl_vocabulary_card_paper_r11 else background),
            null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(
            Modifier.fillMaxWidth().padding(
                start = if (independentRibbon) 22.dp else when (target.type) {
                    LearningTargetType.WORD -> 21.dp
                    LearningTargetType.FIXED_EXPRESSION -> 34.dp
                    LearningTargetType.PATTERN -> 17.dp
                },
                end = if (independentRibbon) 22.dp else if (fixedWide) 4.dp else 10.dp,
                top = if (independentRibbon) 12.dp else 10.dp,
                bottom = if (independentRibbon) 14.dp else if (target.type == LearningTargetType.PATTERN) 11.dp else 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (target.type == LearningTargetType.PATTERN) 1.dp else 2.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(end = if (fixedWide) 10.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                LearningTargetCategoryRibbon(
                    target = target,
                    modifier = if (fixedWide) Modifier.padding(start = 25.dp) else Modifier,
                    renderPaper = !fixedWide,
                )
                Spacer(Modifier.weight(1f))
                SelectionMark(selected, accent)
            }
            if (fixedWide) Spacer(Modifier.height(5.dp))
            Column(
                // The tag's brass eyelet ends inside the first 48 dp. The body
                // shares a safe start beyond it, while the ribbon keeps its own
                // original header coordinate and the right gutter remains real.
                Modifier.fillMaxWidth().padding(start = if (fixedWide) 15.dp else 0.dp, end = if (fixedWide) 7.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(if (target.type == LearningTargetType.PATTERN) 0.dp else 2.dp),
            ) {
                Text(target.display, Modifier.testTag("learning_target_${target.targetId}_display"), color = accent, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                    fontSize = if (independentRibbon) 16.sp else 14.sp,
                    lineHeight = if (independentRibbon) 24.sp else 20.sp, letterSpacing = 0.sp)
                Text(
                    target.explanationZh,
                    Modifier.testTag("learning_target_${target.targetId}_explanation"),
                    color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = if (independentRibbon) 13.sp else 12.sp,
                    lineHeight = if (independentRibbon) 21.sp else 18.sp,
                )
                target.candidateExample?.let { example ->
                    if (target.type != LearningTargetType.PATTERN) Spacer(Modifier.height(2.dp))
                    Text(
                        "例  ${example.textEn}",
                        Modifier.fillMaxWidth().padding(start = if (fixedWide) 14.dp else 0.dp)
                            .testTag("learning_target_${target.targetId}_example_en"),
                        color = RlColor.Ink,
                        fontFamily = FontFamily.Serif,
                        fontSize = 12.sp,
                        lineHeight = if (independentRibbon) 19.sp else 16.sp,
                        letterSpacing = if (independentRibbon) 0.sp else (-.25).sp,
                    )
                    Text(
                        example.textZh, Modifier.testTag("learning_target_${target.targetId}_example_zh"),
                        color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontSize = 12.sp,
                        lineHeight = if (independentRibbon) 19.sp else 16.sp,
                    )
                } ?: Text("暂未提供候选例句。", color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp)
                if (target.structureParts.isNotEmpty()) {
                    Text(
                        "结构：${target.structureParts.joinToString(" + ")}",
                        Modifier.testTag("learning_target_${target.targetId}_structure"),
                        color = RlColor.TextSecondary, fontSize = if (independentRibbon) 11.sp else 10.sp,
                        lineHeight = if (independentRibbon) 18.sp else 15.sp,
                    )
                }
                if (target.relatedVocabulary.isNotEmpty()) {
                    Text(
                        "相关生词：${target.relatedVocabulary.joinToString("、")}",
                        Modifier.testTag("learning_target_${target.targetId}_related"),
                        color = RlColor.TextSecondary, fontSize = if (independentRibbon) 11.sp else 10.sp,
                        lineHeight = if (independentRibbon) 18.sp else 15.sp,
                    )
                    val items = target.relatedVocabularyItems.orEmpty().associateBy { it.itemRef }
                    val currentItems = target.relatedVocabularyItemRefs.orEmpty().mapNotNull { ref ->
                        items[ref]?.takeIf { it.unitId == target.unitId }
                    }
                    if (currentItems.isEmpty()) {
                        Text("暂未提供关联词的本句词义说明。", color = RlColor.TextSecondary,
                            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                            fontSize = if (independentRibbon) 12.sp else 10.5.sp,
                            lineHeight = if (independentRibbon) 19.sp else 15.sp)
                    } else currentItems.forEach { item ->
                        Text("${item.display}：${item.senseZh}", Modifier.fillMaxWidth(), color = RlColor.Ink,
                            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                            fontSize = if (independentRibbon) 12.sp else 10.5.sp,
                            lineHeight = if (independentRibbon) 19.sp else 15.sp)
                    }
                }
            }
        }
    }
}

/** A level, self-contained torn label keeps every category word fully on its color. */
@Composable
private fun LearningTargetCategoryRibbon(
    target: LearningTarget,
    modifier: Modifier = Modifier,
    renderPaper: Boolean = true,
) {
    LearningRibbon(targetLabel(target.type),
        modifier.width((if (target.type == LearningTargetType.FIXED_EXPRESSION) 100.dp else 76.dp) * LocalDensity.current.fontScale.coerceAtLeast(1f))
            .heightIn(min = 26.dp).testTag("learning_target_${target.targetId}_ribbon"), targetAccent(target.type),
        textModifier = Modifier.testTag("learning_target_${target.targetId}_category"))
}

@Composable
private fun SelectionMark(selected: Boolean, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(26.dp).then(
            if (selected) Modifier.background(accent, CircleShape)
            else Modifier.background(RlColor.Surface.copy(alpha = .76f), CircleShape).border(1.2.dp, accent, CircleShape),
        ),
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(Icons.Rounded.Check, "已选择", tint = Color.White, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun AdditionalExpressionSection(state: LearningTargetUiState, onAction: (LearningTargetAction) -> Unit, wide: Boolean) {
    val expressions = state.currentExpressions
    if (expressions.isEmpty() && !state.generationFailed) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("其他可添加词或词组", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("多选 · 点选后自动加入并显示 ✓", color = RlColor.Secondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp)
        }
        expressions.chunked(if (wide) 2 else 1).forEach { group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.forEach { expression ->
                    AdditionalExpressionCard(expression, state.generating, onAction, Modifier.weight(1f))
                }
                if (wide && group.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (state.generationFailed) {
            Text("学习目标生成失败", color = RlColor.Error, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp)
            Text(
                "未能取得该词组的中文语境释义，请重试。",
                color = RlColor.TextSecondary,
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun AdditionalExpressionCard(
    expression: QueryableExpression,
    generating: Boolean,
    onAction: (LearningTargetAction) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.heightIn(min = 58.dp).background(RlColor.Surface.copy(alpha = .72f), RoundedCornerShape(3.dp))
            .border(1.dp, RlColor.Border, RoundedCornerShape(3.dp))
            .clickable(enabled = !generating) { onAction(LearningTargetAction.ExpressionRequested(expression.unitId, expression.expressionId)) }
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("learning_expression_${expression.expressionId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).border(1.2.dp, RlColor.Secondary, CircleShape), contentAlignment = Alignment.Center) {
            if (generating) Icon(Icons.Rounded.HourglassTop, "正在生成", tint = RlColor.Secondary, modifier = Modifier.size(15.dp))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(expression.display, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(
                expression.explanationZh?.takeIf(String::isNotBlank) ?: "点选后生成本句中文释义与用法说明",
                color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp, lineHeight = 15.sp,
            )
        }
        Text("可多选", color = RlColor.Secondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 10.sp)
    }
}

@Composable
private fun LearningTargetFooter(state: LearningTargetUiState, onAction: (LearningTargetAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("learning_targets_footer")) {
        Row(
            Modifier.fillMaxWidth()
                .padding(start = maxWidth * .09f, end = maxWidth * .13f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            Spacer(Modifier.weight(1f))
            LearningFooterButton("全部取消", false, !state.submitting, { onAction(LearningTargetAction.ClearAllRequested) }, Modifier.width(110.dp))
            LearningFooterButton("返回", false, !state.generating && !state.submitting, { onAction(LearningTargetAction.BackRequested) }, Modifier.width(88.dp))
            LearningFooterButton(
                "继续", true, state.continueEnabled && !state.generating && !state.submitting,
                { onAction(LearningTargetAction.ContinueRequested) }, Modifier.width(110.dp),
            )
        }
    }
}

@Composable
private fun LearningFooterButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    EditorialCompactAction(text, onClick, modifier, enabled, primary)
}

@Composable
private fun ColumnScope.AccessibleLearningTargets(state: LearningTargetUiState, onAction: (LearningTargetAction) -> Unit, chapterNumber: Int) {
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LearningTargetHeader(chapterNumber, onAction, adaptiveHeight = true)
        LearningSentencePanel(
            state,
            onAction,
            Modifier.fillMaxWidth().heightIn(min = 118.dp),
        )
        LearningTargetCards(state, onAction, wide = false)
        AdditionalExpressionSection(state, onAction, wide = false)
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LearningFooterButton("全部取消", false, !state.submitting, { onAction(LearningTargetAction.ClearAllRequested) }, Modifier.weight(1f).fillMaxHeight())
        LearningFooterButton("返回", false, !state.generating && !state.submitting, { onAction(LearningTargetAction.BackRequested) }, Modifier.weight(.8f).fillMaxHeight())
        LearningFooterButton("继续", true, state.continueEnabled && !state.generating && !state.submitting, { onAction(LearningTargetAction.ContinueRequested) }, Modifier.weight(1f).fillMaxHeight())
    }
}

private fun targetAccent(type: LearningTargetType) = when (type) {
    LearningTargetType.WORD -> RlColor.Secondary
    LearningTargetType.FIXED_EXPRESSION -> RlColor.NoteAccent
    LearningTargetType.PATTERN -> RlColor.ActionVivid
}

private fun targetLabel(type: LearningTargetType) = when (type) {
    LearningTargetType.WORD -> "词义"
    LearningTargetType.FIXED_EXPRESSION -> "固定搭配"
    LearningTargetType.PATTERN -> "句式"
}

@Composable
private fun LearningTargetTabs(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
        listOf(
            Triple("词义", "01", RlColor.Secondary),
            Triple("搭配", "02", RlColor.NoteAccent),
            Triple("句式", "03", RlColor.ActionVivid),
        ).forEach { (label, number, color) ->
            LearningRibbon("$label\n$number", Modifier.width(49.dp).height(48.dp)
                .testTag("learning_target_tab_$number"), color)
        }
    }
}

private fun LearningTargetUiState.currentSentenceNumber(): Int {
    val unitId = currentSentence?.unitId ?: return focusSentenceIndex + 1
    chapters.forEach { chapter ->
        val position = chapter.sentences.indexOfFirst { it.unitId == unitId }
        if (position >= 0) return position + 1
    }
    return focusSentenceIndex + 1
}

