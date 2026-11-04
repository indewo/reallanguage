package com.reallanguage.app.feature.formation.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.feature.formation.domain.EnglishSentenceDraft

/**
 * Only the ledger scrolls. Each source sentence owns a single row, and alternate
 * English renderings are nested cards so candidates cannot look like new sentences.
 */
@Composable
fun EnglishConfirmationScreen(
    state: EnglishConfirmationUiState,
    onAction: (EnglishConfirmationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val adaptive = !wide || LocalDensity.current.fontScale >= 1.3f
        val compactHeight = maxHeight < 390.dp
        val horizontalInset = if (wide) maxWidth * .053f else 12.dp
        val verticalInset = if (compactHeight) 4.dp else 8.dp

        if (!adaptive) Image(painterResource(R.drawable.rl_v73_formation_frame), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        if (adaptive) Image(
            painter = painterResource(R.drawable.rl_editorial_corner_collage),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(.34f).fillMaxHeight(.42f),
            contentScale = ContentScale.Crop,
            alpha = .92f,
        )
        if (adaptive) Image(
            painter = painterResource(R.drawable.rl_english_confirmation_right_collage),
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(.96f).width(if (wide) 94.dp else 58.dp),
            contentScale = ContentScale.FillBounds,
        )

        EditorialSheet(
            modifier = Modifier.fillMaxSize().padding(
                start = horizontalInset,
                end = if (wide) horizontalInset * .88f else 10.dp,
                top = verticalInset,
                bottom = verticalInset,
            ),
            contentPadding = 0.dp,
            contentSpacing = 0.dp,
            showSpine = false,
            showTopAccent = false,
            renderPaper = adaptive,
        ) {
            Box(Modifier.fillMaxSize()) {
                if (adaptive) Image(
                    painter = painterResource(R.drawable.rl_focus_punch_holes_v2),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = (-6).dp).width(21.dp).fillMaxHeight(.92f),
                    contentScale = ContentScale.FillBounds,
                )
                Column(
                    modifier = Modifier.fillMaxSize().padding(
                        start = if (wide) 42.dp else 20.dp,
                        end = if (wide) 54.dp else 16.dp,
                    ),
                ) {
                    if (adaptive) {
                        Column(
                            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                                .testTag("english_confirmation_ledger"),
                        ) {
                            ConfirmationHeader(
                                chapterCount = state.chapters.size.coerceAtLeast(1),
                                onBack = { onAction(EnglishConfirmationAction.BackRequested) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
                                renderChapterPaper = true,
                            )
                            state.chapters.forEachIndexed { chapterIndex, chapter ->
                                if (state.chapters.size > 1) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                    ) { FormationChapterBadge("第 ${chapterIndex + 1} 章", compact = true) }
                                }
                                chapter.sentences.forEachIndexed { index, sentence ->
                                    ConfirmationLedgerRow(index + 1, sentence, state, onAction, wide = false)
                                }
                            }
                        }
                        ConfirmationFooter(state, onAction, wide = false, compactHeight = compactHeight)
                    } else {
                    ConfirmationHeader(
                        chapterCount = state.chapters.size.coerceAtLeast(1),
                        onBack = { onAction(EnglishConfirmationAction.BackRequested) },
                        modifier = Modifier.fillMaxWidth().height(if (compactHeight) 66.dp else 78.dp),
                        renderChapterPaper = false,
                    )
                    HorizontalDivider(color = RlColor.BorderStrong.copy(alpha = .42f), thickness = 1.dp)
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                            .testTag("english_confirmation_ledger"),
                    ) {
                        state.chapters.forEachIndexed { chapterIndex, chapter ->
                            if (state.chapters.size > 1) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                                    horizontalArrangement = Arrangement.Center,
                                ) { FormationChapterBadge("第 ${chapterIndex + 1} 章", compact = true) }
                            }
                            chapter.sentences.forEachIndexed { index, sentence ->
                                ConfirmationLedgerRow(index + 1, sentence, state, onAction, wide)
                            }
                        }
                    }
                    HorizontalDivider(color = RlColor.BorderStrong.copy(alpha = .32f), thickness = 1.dp)
                    ConfirmationFooter(state, onAction, wide, compactHeight)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmationHeader(
    chapterCount: Int,
    onBack: () -> Unit,
    modifier: Modifier,
    renderChapterPaper: Boolean,
) {
    Box(modifier.testTag("english_confirmation_header")) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回重点句选择",
                tint = RlColor.Ink,
                modifier = Modifier.size(30.dp),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "确认英语表达",
                color = RlColor.Ink,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                ),
            )
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.width(174.dp).height(7.dp),
                contentScale = ContentScale.FillBounds,
            )
        }
        FormationChapterBadge(
            text = if (chapterCount == 1) "第 1 章" else "共 $chapterCount 章",
            modifier = Modifier.align(if (renderChapterPaper) Alignment.CenterEnd else Alignment.TopEnd)
                .offset(x = (-50).dp),
            renderPaper = renderChapterPaper,
        )
    }
}

@Composable
private fun ConfirmationLedgerRow(
    index: Int,
    sentence: EnglishSentenceDraft,
    state: EnglishConfirmationUiState,
    onAction: (EnglishConfirmationAction) -> Unit,
    wide: Boolean,
) {
    val accent = sentence.isFocus
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .testTag("english_confirmation_sentence_${sentence.unitId}"),
        color = if (accent) RlColor.ActionVividContainer.copy(alpha = .58f) else Color.Transparent,
        shape = if (accent) RoundedCornerShape(2.dp) else RoundedCornerShape(0.dp),
        shadowElevation = if (accent) 2.dp else 0.dp,
    ) {
        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    index.toString(),
                    modifier = Modifier.width(32.dp),
                    color = if (accent) RlColor.ActionVivid else RlColor.Ink,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 16.sp,
                )
                ConfirmationEnglish(sentence, state, onAction, Modifier.weight(1.1f), wide = true)
                Box(Modifier.width(1.dp).heightIn(min = 40.dp).fillMaxHeight().background(RlColor.BorderStrong.copy(alpha = .55f)))
                Column(
                    modifier = Modifier.weight(.86f).padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (sentence.candidates.size > 1) {
                        Text("中文原句", color = RlColor.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        sentence.textZh,
                        color = if (accent) RlColor.ActionVivid else RlColor.Ink,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif),
                    )
                }
                if (accent && sentence.candidates.size == 1) {
                    Icon(Icons.Rounded.Check, contentDescription = "已采用", tint = RlColor.ActionVivid)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$index", color = if (accent) RlColor.ActionVivid else RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        if (sentence.candidates.size > 1) Text("中文原句", color = RlColor.TextSecondary, fontSize = 10.sp)
                        Text(sentence.textZh, color = if (accent) RlColor.ActionVivid else RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif)
                    }
                }
                ConfirmationEnglish(sentence, state, onAction, Modifier.fillMaxWidth(), wide = false)
            }
        }
    }
    HorizontalDivider(color = RlColor.BorderStrong.copy(alpha = .25f), thickness = 1.dp)
}

@Composable
private fun ConfirmationEnglish(
    sentence: EnglishSentenceDraft,
    state: EnglishConfirmationUiState,
    onAction: (EnglishConfirmationAction) -> Unit,
    modifier: Modifier,
    wide: Boolean,
) {
    val textColor = if (sentence.isFocus) RlColor.ActionVivid else RlColor.Ink
    if (sentence.candidates.size == 1) {
        Text(
            sentence.candidates.single().textEn,
            modifier = modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = textColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif),
        )
    } else {
        if (wide && sentence.candidates.size == 2) {
            BoxWithConstraints(modifier = modifier.padding(horizontal = 4.dp)) {
                // Use a whole-dp shared width so odd-pixel containers cannot round
                // the two equal options to different physical widths.
                val candidateWidth = kotlin.math.floor((maxWidth.value - 8f) / 2f).dp
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sentence.candidates.forEachIndexed { candidateIndex, candidate ->
                        CandidateChoiceCard(
                            sentence,
                            candidateIndex,
                            candidate.candidateId,
                            candidate.textEn,
                            state,
                            onAction,
                            textColor,
                            Modifier.width(candidateWidth).fillMaxHeight(),
                        )
                    }
                }
            }
        } else {
            Column(modifier = modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                sentence.candidates.forEachIndexed { candidateIndex, candidate ->
                    CandidateChoiceCard(sentence, candidateIndex, candidate.candidateId, candidate.textEn, state, onAction, textColor, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun CandidateChoiceCard(
    sentence: EnglishSentenceDraft,
    candidateIndex: Int,
    candidateId: String,
    candidateText: String,
    state: EnglishConfirmationUiState,
    onAction: (EnglishConfirmationAction) -> Unit,
    textColor: Color,
    modifier: Modifier,
) {
    val selected = state.selectedCandidateIds[sentence.unitId] == candidateId
    Surface(
        modifier = modifier.clickable {
            onAction(EnglishConfirmationAction.CandidateSelected(sentence.unitId, candidateId))
        }.testTag("english_candidate_${sentence.unitId}_$candidateId"),
        color = if (selected) RlColor.ActionVividContainer.copy(alpha = .95f) else RlColor.Background.copy(alpha = .72f),
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, if (selected) RlColor.ActionVivid.copy(alpha = .72f) else RlColor.BorderStrong.copy(alpha = .34f)),
        shadowElevation = if (selected) 1.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight()
                .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = { onAction(EnglishConfirmationAction.CandidateSelected(sentence.unitId, candidateId)) },
                colors = RadioButtonDefaults.colors(selectedColor = RlColor.ActionVivid, unselectedColor = RlColor.TextSecondary),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("表达 ${candidateIndex + 1}", color = if (selected) RlColor.ActionVivid else RlColor.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(candidateText, color = textColor, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun ConfirmationFooter(
    state: EnglishConfirmationUiState,
    onAction: (EnglishConfirmationAction) -> Unit,
    wide: Boolean,
    compactHeight: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .height(if (wide && !compactHeight) 66.dp else 58.dp)
            .padding(top = 6.dp)
            .testTag("english_confirmation_acceptance"),
        horizontalArrangement = Arrangement.spacedBy(if (wide) 14.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.rl_english_confirmation_accepted_stamp),
            contentDescription = "确认通过",
            modifier = Modifier.width(if (wide) 94.dp else 66.dp).height(if (wide) 48.dp else 40.dp)
                .offset(x = if (wide) (-40).dp else 0.dp)
                .testTag("english_confirmation_stamp"),
            contentScale = ContentScale.Fit,
            alpha = .9f,
        )
        if (wide) Spacer(Modifier.weight(1f))
        FormationActionPair(
            secondaryText = "返回重点句选择",
            secondaryEnabled = true,
            onSecondary = { onAction(EnglishConfirmationAction.BackRequested) },
            primaryText = "继续选择学习目标",
            primaryEnabled = state.continueEnabled && !state.submitting,
            onPrimary = { onAction(EnglishConfirmationAction.ContinueRequested) },
            modifier = if (wide) Modifier.width(390.dp) else Modifier.weight(1f),
            secondaryTag = "english_confirmation_back",
            primaryTag = "english_confirmation_continue",
        )
    }
}
