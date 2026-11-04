package com.reallanguage.app.feature.formation.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.reallanguage.app.feature.learning.presentation.LearningRibbon
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.feature.formation.domain.LearningTarget
import com.reallanguage.app.feature.formation.domain.LearningTargetType

@Composable
fun FinalFixScreen(
    state: FinalFixUiState,
    onAction: (FinalFixAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().testTag("final_fix_page"),
    ) {
        val wide = maxWidth >= 600.dp
        val scrollHeader = LocalDensity.current.fontScale >= 1.3f || (!wide && maxHeight < 400.dp)
        val documentStart = if (wide) maxWidth * .045f else 0.dp
        val documentEnd = if (wide) maxWidth * .065f else 0.dp
        if (!scrollHeader && wide) Image(painterResource(R.drawable.rl_v73_final_frame), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        EditorialSheet(
            modifier = Modifier.fillMaxSize().padding(start = if (wide) maxWidth * .12f else 12.dp, end = if (wide) maxWidth * .11f else 12.dp, top = 12.dp, bottom = 12.dp),
            contentPadding = if (wide) 4.dp else 8.dp,
            contentSpacing = 6.dp,
            showSpine = false,
            showTopAccent = false,
            contentModifier = Modifier.fillMaxSize(),
            renderPaper = scrollHeader || !wide,
        ) {
            if (scrollHeader) {
                FinalFixAccessibleContent(state, onAction)
            } else {
            FinalFixHeader(
                state.submitting,
                { onAction(FinalFixAction.BackRequested) },
                Modifier.fillMaxWidth().height(if (wide) 66.dp else 80.dp),
                wide,
                state.chapters.size.coerceAtLeast(1),
            )
            FinalFixSummary(
                state.chapters.sumOf { chapter -> chapter.sentences.count { it.isFocus } },
                state.chapters.size,
                Modifier.fillMaxWidth().padding(start = documentStart, end = documentEnd)
                    .height(if (wide) 30.dp else 40.dp),
            )
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(start = documentStart, end = documentEnd),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FinalSentenceDocument(state, Modifier.weight(1f).fillMaxHeight(), scrollable = true)
                    FinalTargetDocument(state.selectedTargets, Modifier.weight(1f).fillMaxHeight(), scrollable = true)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FinalSentenceDocument(state, Modifier.fillMaxWidth())
                    FinalTargetDocument(state.selectedTargets, Modifier.fillMaxWidth())
                }
            }
            if (state.fixFailed) Text(stringResource(R.string.final_fix_failed), color = RlColor.Error)
            FinalFixFooter(state, onAction, wide)
            }
        }
        if (wide && !scrollHeader) FormationFrameChapterBadge(
            if (state.chapters.size <= 1) "第 1 章" else "共 ${state.chapters.size} 章", maxWidth, maxHeight)
    }
}

/** Content can scroll at large text sizes; the equal actions remain pinned. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.FinalFixAccessibleContent(
    state: FinalFixUiState,
    onAction: (FinalFixAction) -> Unit,
) {
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FinalFixHeader(
            submitting = state.submitting,
            onBack = { onAction(FinalFixAction.BackRequested) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp),
            wide = false,
            chapterCount = state.chapters.size.coerceAtLeast(1),
        )
        FinalFixSummary(state.chapters.sumOf { chapter -> chapter.sentences.count { it.isFocus } }, state.chapters.size,
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 8.dp))
        FinalSentenceDocument(state, Modifier.fillMaxWidth())
        FinalTargetDocument(state.selectedTargets, Modifier.fillMaxWidth())
        if (state.fixFailed) Text(stringResource(R.string.final_fix_failed), color = RlColor.Error)
    }
    FormationActionPair(
        secondaryText = "返回修改",
        secondaryEnabled = !state.submitting,
        onSecondary = { onAction(FinalFixAction.BackRequested) },
        primaryText = stringResource(if (state.submitting) R.string.final_fix_submitting else R.string.final_fix_confirm),
        primaryEnabled = state.confirmEnabled && !state.submitting,
        onPrimary = { onAction(FinalFixAction.ConfirmRequested) },
        modifier = Modifier.fillMaxWidth(),
        secondaryTag = "final_fix_back",
        primaryTag = "final_fix_confirm",
    )
}

@Composable
private fun FinalFixHeader(
    submitting: Boolean,
    onBack: () -> Unit,
    modifier: Modifier,
    wide: Boolean,
    chapterCount: Int,
) {
    Box(modifier) {
        IconButton(onClick = onBack, enabled = !submitting, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = RlColor.Ink)
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.final_fix_title),
                color = RlColor.Ink,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontSize = if (wide) 28.sp else 24.sp,
                ),
            )
            Box(Modifier.padding(top = 2.dp).width(112.dp).height(1.dp).background(RlColor.ActionVivid))
        }
        if (!wide) FormationChapterBadge(
            text = if (chapterCount == 1) "第 1 章" else "共 $chapterCount 章",
            modifier = Modifier.align(if (wide) Alignment.TopEnd else Alignment.CenterEnd)
                .offset(x = if (wide) (-50).dp else 0.dp),
            compact = !wide,
            renderPaper = !wide,
        )
    }
}

@Composable
private fun FinalFixSummary(focusCount: Int, chapterCount: Int, modifier: Modifier) {
    Row(
        modifier = modifier.testTag("final_fix_summary")
            .border(1.dp, RlColor.Border.copy(alpha = .65f), RoundedCornerShape(2.dp))
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Book, contentDescription = null, tint = RlColor.NoteAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            buildAnnotatedString {
                val focus = focusCount.toString()
                append(focus)
                addStyle(SpanStyle(color = RlColor.ActionVivid), 0, length)
                append(" 个重点句  ·  ")
                val chapterStart = length
                append(chapterCount.toString())
                addStyle(SpanStyle(color = RlColor.ActionVivid), chapterStart, length)
                append(" 个章节")
            },
            color = RlColor.Ink,
            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun FinalSentenceDocument(state: FinalFixUiState, modifier: Modifier, scrollable: Boolean = false) {
    val sentences = state.chapters.flatMap { it.sentences }
    FinalDocumentPanel("英语表达", "final_fix_sentences", modifier, scrollable) {
        sentences.forEachIndexed { index, sentence ->
            val english = sentence.candidates.firstOrNull {
                it.candidateId == state.selectedCandidateIds[sentence.unitId]
            }?.textEn.orEmpty()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier.size(22.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)).background(if (sentence.isFocus) RlColor.Primary else RlColor.DisabledContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("${index + 1}", color = Color.White, fontSize = 11.sp, lineHeight = 14.sp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        buildAnnotatedString {
                            append(english)
                            if (sentence.isFocus) state.selectedTargets.filter { it.unitId == sentence.unitId }.forEach { target ->
                                val expression = target.display.trim()
                                if (expression.isNotEmpty()) {
                                    // Only literal whole expressions are known here. Never infer an inflection or placeholder span.
                                    Regex("(?<![\\p{L}\\p{N}_'’\\-])${Regex.escape(expression)}(?![\\p{L}\\p{N}_'’\\-])", RegexOption.IGNORE_CASE)
                                        .findAll(english).forEach { match ->
                                            addStyle(SpanStyle(color = RlColor.ActionVivid), match.range.first, match.range.last + 1)
                                        }
                                }
                            }
                        },
                        color = RlColor.Ink,
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        letterSpacing = 0.sp,
                    )
                    Text(sentence.textZh, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
                }
            }
            if (index < sentences.lastIndex) DividerLine()
        }
    }
}

@Composable
private fun FinalTargetDocument(targets: List<LearningTarget>, modifier: Modifier, scrollable: Boolean = false) {
    FinalDocumentPanel("学习目标", "final_fix_targets", modifier, scrollable) {
        if (targets.isEmpty()) {
            Text(stringResource(R.string.final_fix_sentence_learning_only), color = RlColor.TextSecondary)
        } else {
            targets.forEachIndexed { index, target ->
                val targetColor = when (target.type) {
                    LearningTargetType.WORD -> RlColor.Primary
                    LearningTargetType.FIXED_EXPRESSION -> RlColor.NoteAccent
                    LearningTargetType.PATTERN -> RlColor.ActionVivid
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Book,
                        contentDescription = null,
                        tint = targetColor,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        target.display,
                        modifier = Modifier.weight(1f),
                        color = RlColor.Ink,
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        letterSpacing = 0.sp,
                    )
                }
                if (index < targets.lastIndex) DividerLine()
            }
        }
    }
}

@Composable
private fun DocumentLabel(text: String, color: Color) {
    LearningRibbon(text, Modifier.width(108.dp).heightIn(min = 25.dp), color)
}

@Composable
private fun FinalDocumentPanel(
    title: String, tag: String, modifier: Modifier, scrollable: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var viewport by remember { mutableIntStateOf(0) }
    Column(modifier.testTag(tag)
        .background(RlColor.Surface.copy(alpha = .72f), RoundedCornerShape(4.dp))
        .border(.6.dp, RlColor.Border.copy(alpha = .55f), RoundedCornerShape(4.dp))
        .padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DocumentLabel(title, RlColor.Secondary)
        Column(if (scrollable) Modifier.weight(1f).fillMaxWidth()
            .onSizeChanged { viewport = it.height }.verticalScroll(scroll).testTag("${tag}_scroll")
            else Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
        if (scrollable && (scroll.canScrollForward || scroll.canScrollBackward)) {
            Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.End) {
                if (scroll.canScrollBackward) IconButton(
                    onClick = { scope.launch { scroll.animateScrollBy(-viewport * .8f) } },
                    modifier = Modifier.width(44.dp).testTag("${tag}_up"),
                ) { Icon(Icons.Rounded.ArrowDropUp, "向上查看$title", tint = RlColor.Primary) }
                if (scroll.canScrollForward) IconButton(
                    onClick = { scope.launch { scroll.animateScrollBy(viewport * .8f) } },
                    modifier = Modifier.width(44.dp).testTag("${tag}_down"),
                ) { Icon(Icons.Rounded.ArrowDropDown, "向下查看$title", tint = RlColor.Primary) }
            }
        }
    }
}

@Composable
private fun DividerLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(RlColor.Border.copy(alpha = .55f)))
}

@Composable
private fun FinalFixFooter(state: FinalFixUiState, onAction: (FinalFixAction) -> Unit, wide: Boolean) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(if (wide) 48.dp else 56.dp)
            .padding(end = if (wide) 39.dp else 0.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        FormationActionPair(
            secondaryText = "返回修改",
            secondaryEnabled = !state.submitting,
            onSecondary = { onAction(FinalFixAction.BackRequested) },
            primaryText = if (state.submitting) stringResource(R.string.final_fix_submitting) else stringResource(R.string.final_fix_confirm),
            primaryEnabled = state.confirmEnabled && !state.submitting,
            onPrimary = { onAction(FinalFixAction.ConfirmRequested) },
            modifier = Modifier.width(minOf(maxWidth, 320.dp)),
            secondaryTag = "final_fix_back",
            primaryTag = "final_fix_confirm",
        )
    }
}
