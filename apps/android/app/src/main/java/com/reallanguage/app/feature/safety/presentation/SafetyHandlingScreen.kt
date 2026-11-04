package com.reallanguage.app.feature.safety.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSecondaryButton
import com.reallanguage.app.feature.safety.domain.SafetyEdit

@Composable
fun SafetyHandlingScreen(
    state: SafetyHandlingUiState,
    onAction: (SafetyHandlingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    RlPaperBackground(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 600.dp || maxWidth <= maxHeight * 1.35f || LocalDensity.current.fontScale > 1.2f
            if (compact) CompactSafetyHandling(state, onAction)
            else WideSafetyHandling(state, onAction)
        }
    }
}

@Composable
private fun WideSafetyHandling(state: SafetyHandlingUiState, onAction: (SafetyHandlingAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val pageWidth = maxWidth
        val pageHeight = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(pageHeight)) {
                com.reallanguage.app.feature.formation.presentation.EditorialHeader(
                    title = "内容安全处理",
                    onBack = if (!state.submitting && !state.sourceEditorUnavailable) {
                        { onAction(SafetyHandlingAction.ReturnToModifyRequested) }
                    } else null,
                )

                ReasonBanner(
                    reason = state.details.userReasonZh,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = pageHeight * .095f)
                        .width(pageWidth * .72f)
                        .height(pageHeight * .135f),
                )

                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = pageHeight * .205f)
                        .width(pageWidth * .91f)
                        .height(pageHeight * .37f),
                    horizontalArrangement = Arrangement.spacedBy(pageWidth * .02f),
                ) {
                    ContentPaper(
                        "原始内容",
                        state.details.originalSource.text,
                        state.details.edits,
                        true,
                        Modifier.weight(1f).fillMaxHeight().testTag("safety-handling-original"),
                        scrollContent = true,
                    )
                    Box(Modifier.width(pageWidth * .018f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward,
                            null,
                            tint = RlColor.Secondary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    ContentPaper(
                        stringResource(R.string.safety_deidentified_label),
                        state.details.deidentifiedSource.text,
                        state.details.edits,
                        false,
                        Modifier.weight(1f).fillMaxHeight().testTag("safety-handling-deidentified"),
                        scrollContent = true,
                    )
                }

                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = pageHeight * .585f)
                        .width(pageWidth * .82f)
                        .height(pageHeight * .22f),
                    horizontalArrangement = Arrangement.spacedBy(pageWidth * .035f),
                ) {
                    ChangesPaper(
                        state.details.edits,
                        Modifier.weight(1.45f).fillMaxHeight(),
                        useEditorialArtwork = true,
                    )
                    EditorNote(Modifier.weight(.72f).fillMaxHeight())
                }

                ActionBar(
                    state,
                    onAction,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = pageHeight * .012f)
                        .width(pageWidth * .63f),
                )
            }
        }
    }
}

@Composable
private fun CompactSafetyHandling(state: SafetyHandlingUiState, onAction: (SafetyHandlingAction) -> Unit) {
    ResponsiveSafetyHandling(state, onAction, true)
}

@Composable
private fun ResponsiveSafetyHandling(state: SafetyHandlingUiState, onAction: (SafetyHandlingAction) -> Unit, stacked: Boolean) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        com.reallanguage.app.feature.formation.presentation.EditorialHeader(
            title = "内容安全处理",
            onBack = if (!state.submitting && !state.sourceEditorUnavailable) {
                { onAction(SafetyHandlingAction.ReturnToModifyRequested) }
            } else null,
        )
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(if (stacked) 14.dp else 7.dp)) {
            ReasonBanner(state.details.userReasonZh, Modifier.fillMaxWidth(if (stacked) 1f else .92f).align(Alignment.CenterHorizontally), inline = !stacked)
            if (stacked) {
                ContentPaper("原始内容", state.details.originalSource.text, state.details.edits, true, Modifier.fillMaxWidth().testTag("safety-handling-original"))
                ContentPaper(stringResource(R.string.safety_deidentified_label), state.details.deidentifiedSource.text, state.details.edits, false, Modifier.fillMaxWidth().testTag("safety-handling-deidentified"))
                ChangesPaper(state.details.edits, Modifier.fillMaxWidth())
                EditorNote(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    ContentPaper("原始内容", state.details.originalSource.text, state.details.edits, true, Modifier.weight(1f).fillMaxHeight().testTag("safety-handling-original"), compactVertical = true)
                    ContentPaper(stringResource(R.string.safety_deidentified_label), state.details.deidentifiedSource.text, state.details.edits, false, Modifier.weight(1f).fillMaxHeight().testTag("safety-handling-deidentified"), compactVertical = true)
                }
                Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ChangesPaper(state.details.edits, Modifier.weight(1.4f).fillMaxHeight(), inlineExplanation = true)
                    EditorNote(Modifier.weight(.8f).fillMaxHeight(), compactVertical = true)
                }
            }
        }
        ActionBar(state, onAction, Modifier.fillMaxWidth(if (stacked) 1f else .76f).align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun SafetyTitle(editEnabled: Boolean, onBack: () -> Unit) {
    IconButton(onClick = onBack, enabled = editEnabled, modifier = Modifier.padding(start = 18.dp, top = 8.dp).size(42.dp)) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回修改", tint = RlColor.Ink, modifier = Modifier.size(27.dp))
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("内容安全处理", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(134.dp).height(7.dp), contentScale = ContentScale.FillBounds)
    }
}

@Composable
private fun ReasonBanner(reason: String, modifier: Modifier = Modifier, inline: Boolean = false) {
    Box(modifier.testTag("safety-handling-reason")) {
        Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (inline) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = RlColor.ActionVivid, modifier = Modifier.size(if (inline) 30.dp else 34.dp))
            if (inline) {
                Text("需要处理的内容", Modifier.padding(start = 12.dp, end = 16.dp), color = RlColor.ActionVivid,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
                Text(reason, Modifier.weight(1f), color = RlColor.Ink,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 21.sp)
            } else Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("需要处理的内容", color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
                Text(reason, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 21.sp)
            }
        }
    }
}

@Composable
private fun ContentPaper(
    title: String,
    text: String,
    edits: List<SafetyEdit>,
    original: Boolean,
    modifier: Modifier = Modifier,
    compactVertical: Boolean = false,
    scrollContent: Boolean = false,
) {
    Box(modifier) {
        Image(painterResource(R.drawable.rl_olive_torn_paper), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds, alpha = .25f)
        Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize().padding(4.dp), contentScale = ContentScale.FillBounds)
        val contentModifier = (if (scrollContent) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(start = 26.dp, end = 24.dp, top = if (compactVertical) 8.dp else 10.dp, bottom = if (compactVertical) 8.dp else 12.dp)
            .let { if (scrollContent) it.verticalScroll(rememberScrollState()) else it }
        Column(contentModifier) {
            Text(title, color = if (original) RlColor.Ink else RlColor.Secondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
            Image(
                com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                null, Modifier.width(96.dp).height(6.dp), contentScale = ContentScale.FillBounds,
            )
            Column(Modifier.fillMaxWidth()) {
                Text(
                    highlightedText(text, edits.map { if (original) it.originalText else it.replacementText }, original),
                    color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 15.sp, lineHeight = 26.sp,
                    modifier = Modifier.padding(vertical = if (compactVertical) 4.dp else 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (original) Icons.Outlined.Lock else Icons.Outlined.CheckCircle, null, tint = if (original) RlColor.ActionVivid else RlColor.Secondary, modifier = Modifier.size(19.dp))
                Text(
                    if (original) "含敏感信息" else "已隐藏可识别信息，其他原意保持不变。",
                    color = if (original) RlColor.ActionVivid else RlColor.TextSecondary,
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 7.dp),
                )
            }
        }
        if (!original) Image(painterResource(R.drawable.rl_safety_privacy_stamp), null, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp).size(62.dp))
    }
}

private fun highlightedText(text: String, terms: List<String>, original: Boolean) = buildAnnotatedString {
    var cursor = 0
    terms.filter { it.isNotBlank() }.mapNotNull { term ->
        val start = text.indexOf(term)
        if (start < 0) null else start to (start + term.length)
    }.sortedBy { it.first }.forEach { (start, end) ->
        if (start < cursor) return@forEach
        if (start > cursor) append(text.substring(cursor, start))
        withStyle(SpanStyle(
            color = if (original) RlColor.ActionVivid else RlColor.Secondary,
            background = if (original) Color(0x26B95A47) else Color(0x28607961),
            fontWeight = FontWeight.Bold,
        )) { append(text.substring(start, end)) }
        cursor = end
    }
    if (cursor < text.length) append(text.substring(cursor))
}

@Composable
private fun ChangesPaper(
    edits: List<SafetyEdit>,
    modifier: Modifier = Modifier,
    inlineExplanation: Boolean = false,
    useEditorialArtwork: Boolean = false,
) {
    Box(modifier.testTag("safety-handling-changes")) {
        Image(
            painterResource(if (useEditorialArtwork) R.drawable.rl_diary_processing_analysis_note else R.drawable.rl_vocabulary_card_paper_r11),
            null,
            Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            Modifier.fillMaxWidth().padding(
                start = if (useEditorialArtwork) 98.dp else 22.dp,
                end = if (useEditorialArtwork) 82.dp else 22.dp,
                top = if (useEditorialArtwork) 18.dp else if (inlineExplanation) 8.dp else 18.dp,
                bottom = if (useEditorialArtwork) 24.dp else if (inlineExplanation) 8.dp else 18.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (useEditorialArtwork) 2.dp else if (inlineExplanation) 6.dp else 10.dp),
        ) {
            Text(
                "变更说明",
                color = RlColor.Primary,
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = if (useEditorialArtwork) 14.sp else 16.sp,
                lineHeight = if (useEditorialArtwork) 17.sp else 23.sp,
                fontWeight = FontWeight.Bold,
            )
            edits.forEach { edit ->
                if (useEditorialArtwork) {
                    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            edit.originalText,
                            color = RlColor.ActionVivid,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 10.sp,
                            lineHeight = 16.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true)),
                            modifier = Modifier.weight(.72f).requiredHeight(18.dp).border(1.dp, RlColor.ActionVivid).padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = RlColor.Primary, modifier = Modifier.padding(horizontal = 8.dp).size(18.dp))
                        Text(edit.replacementText, color = RlColor.Secondary, fontFamily = FontFamily.SansSerif,
                            fontSize = 10.sp, lineHeight = 16.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = true)),
                            modifier = Modifier.weight(.72f).requiredHeight(18.dp))
                        Text(edit.explanationZh, color = RlColor.TextSecondary,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 9.sp, lineHeight = 13.sp, maxLines = 1,
                            modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Icon(Icons.Outlined.CheckCircle, null, tint = RlColor.Secondary, modifier = Modifier.size(17.dp))
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(edit.originalText, color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp,
                                lineHeight = 20.sp, modifier = Modifier.weight(1f).border(1.dp, RlColor.ActionVivid).padding(horizontal = 7.dp, vertical = if (inlineExplanation) 3.dp else 4.dp))
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = RlColor.Primary, modifier = Modifier.padding(horizontal = 10.dp).size(18.dp))
                            Text(edit.replacementText, color = RlColor.Secondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp,
                                lineHeight = 20.sp, modifier = Modifier.weight(1f))
                            if (inlineExplanation) Text(edit.explanationZh, color = RlColor.TextSecondary,
                                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                                fontSize = 12.sp, lineHeight = 19.sp,
                                modifier = Modifier.weight(1.4f).padding(start = 12.dp))
                        }
                        if (!inlineExplanation) Text(edit.explanationZh, color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
        if (useEditorialArtwork) {
            Image(
                painterResource(R.drawable.rl_black_binder_clip),
                null,
                Modifier.align(Alignment.TopStart).offset(x = 23.dp, y = (-12).dp).size(43.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun EditorNote(modifier: Modifier = Modifier, compactVertical: Boolean = false) {
    Box(modifier.testTag("safety-handling-editor-note")) {
        Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (compactVertical) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("编辑批注", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
            Text("已按隐私保护规范脱敏，\n不影响语义表达与学习理解。", color = RlColor.Ink,
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 22.sp,
                modifier = Modifier.padding(end = 14.dp, bottom = if (compactVertical) 4.dp else 12.dp))
        }
        Image(painterResource(R.drawable.rl_diary_botanical_sprig), null,
            Modifier.align(Alignment.BottomEnd).width(54.dp).height(48.dp), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun ActionBar(state: SafetyHandlingUiState, onAction: (SafetyHandlingAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.testTag("safety-handling-actions"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.sourceEditorUnavailable) Text(
            stringResource(R.string.safety_source_editor_unavailable), color = RlColor.Error, fontSize = 10.sp,
            modifier = Modifier.testTag("safety-source-editor-unavailable"),
        )
        if (state.commandFailed) Text(stringResource(R.string.safety_action_failed), color = RlColor.Error, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RlSecondaryButton(stringResource(R.string.safety_return_modify), !state.submitting && !state.sourceEditorUnavailable, { onAction(SafetyHandlingAction.ReturnToModifyRequested) }, Modifier.weight(1f).heightIn(min = 48.dp))
            RlSecondaryButton(stringResource(R.string.safety_submit_review), !state.submitting, { onAction(SafetyHandlingAction.SubmitManualReviewRequested) }, Modifier.weight(1.08f).heightIn(min = 48.dp))
            RlPrimaryButton(stringResource(R.string.safety_use_deidentified), !state.submitting, { onAction(SafetyHandlingAction.UseDeidentifiedRequested) }, Modifier.weight(1.18f).heightIn(min = 48.dp), containerColor = RlColor.Secondary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, tint = RlColor.TextMuted, modifier = Modifier.size(11.dp))
            Text("只有你主动提交时，人工审核人员才会收到这份内容。", color = RlColor.TextMuted, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 5.dp))
        }
    }
}
