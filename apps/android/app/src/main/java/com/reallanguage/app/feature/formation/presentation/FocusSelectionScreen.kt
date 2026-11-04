package com.reallanguage.app.feature.formation.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.feature.formation.domain.SemanticUnit

/** Header, scrollable sentences, fixed footer, and rail never compete for space. */
@Composable
fun FocusSelectionScreen(
    state: FocusSelectionUiState,
    onAction: (FocusSelectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (LocalDensity.current.fontScale >= 1.3f || maxWidth < 700.dp || maxHeight < 340.dp) {
            AccessibleFocusSelection(state, onAction)
            return@BoxWithConstraints
        }
        val wide = maxWidth >= 700.dp && maxWidth / maxHeight >= 1.35f
        val horizontalInset = if (wide) maxWidth * .018f else 12.dp
        val verticalInset = if (wide) maxHeight * .015f else 8.dp
        val headerHeight = if (wide) maxHeight * .16f else 94.dp
        val wideHeaderHeight = maxHeight * .20f

        Image(
            painter = painterResource(R.drawable.rl_paper_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (wide) {
            // Independent binding and paper layers remain beneath every native control.
            Image(painterResource(R.drawable.rl_olive_torn_paper), null,
                Modifier.align(Alignment.CenterStart).width(46.dp).fillMaxHeight(.82f),
                contentScale = ContentScale.FillBounds)
            Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null,
                Modifier.fillMaxSize().padding(start = 16.dp, top = 9.dp, end = 12.dp, bottom = 7.dp),
                contentScale = ContentScale.FillBounds)
            Image(painterResource(R.drawable.rl_v73_focus_lower_corner), null,
                Modifier.align(Alignment.BottomEnd).size(width = 145.dp, height = 180.dp),
                contentScale = ContentScale.FillBounds)
        }
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = horizontalInset, vertical = verticalInset)) {
            FocusHeader(
                wide = wide,
                modifier = Modifier.fillMaxWidth().height(if (wide) wideHeaderHeight else headerHeight),
                onBack = { onAction(FocusSelectionAction.BackRequested) },
            )
            if (wide) {
                WideFocusBody(
                    state,
                    onAction,
                    Modifier.fillMaxWidth().weight(1f).padding(end = 140.dp),
                )
            } else {
                CompactFocusBody(state, onAction, Modifier.fillMaxWidth().weight(1f))
            }
        }
        if (wide) {
            FocusSelectionRail(
                state,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 19.dp, end = horizontalInset)
                    .width(150.dp)
                    .height(320.dp),
            )
            Image(
                painterResource(R.drawable.rl_editorial_bottom_left_collage), null,
                modifier = Modifier.align(Alignment.BottomStart).size(width = 116.dp, height = 104.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun AccessibleFocusSelection(state: FocusSelectionUiState, onAction: (FocusSelectionAction) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EditorialHeader(stringResource(R.string.focus_selection_title), modifier = Modifier.testTag("focus_rules"), subtitle = "从完整内容中选择 1—3 句，作为这次学习重点。", onBack = { onAction(FocusSelectionAction.BackRequested) })
            Text("已选 ${state.selectedUnitIds.size} / 3", color = RlColor.Secondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 18.sp)
            state.units.forEachIndexed { index, unit ->
                val selected = unit.unitId in state.selectedUnitIds
                EditorialSheet(Modifier.fillMaxWidth().clickable(enabled = (selected || !state.selectionLimitReached) && !state.submitting) { onAction(FocusSelectionAction.UnitToggled(unit.unitId)) }.testTag("focus_sentence_${unit.unitId}"), contentPadding = 20.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${index + 1}", color = RlColor.Ink, fontSize = 18.sp)
                        Box(Modifier.size(30.dp).border(1.dp, if (selected) RlColor.ActionVivid else RlColor.BorderStrong, CircleShape).then(if (selected) Modifier.background(RlColor.ActionVivid, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                            if (selected) Icon(Icons.Rounded.Check, "已选择", tint = Color.White)
                        }
                        unit.recommendationReasonZh?.let { Text("AI 推荐", color = RlColor.ActionVivid, fontSize = 13.sp) }
                    }
                    Text(unit.textZh, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 17.sp, lineHeight = 28.sp)
                }
            }
            FocusInfo(Modifier.fillMaxWidth())
        }
        FocusConfirmButton(state, onAction, Modifier.fillMaxWidth().heightIn(min = 52.dp))
    }
}

@Composable
private fun FocusHeader(wide: Boolean, modifier: Modifier, onBack: () -> Unit) {
    Box(modifier) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).size(if (wide) 56.dp else 48.dp).testTag("focus_back"),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = null,
                tint = RlColor.Ink,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).testTag("focus_header"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (wide) 4.dp else 2.dp),
        ) {
            Text(
                stringResource(R.string.focus_selection_title),
                color = RlColor.Ink,
                style = (if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall).copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                ),
            )
            Box(Modifier.width(if (wide) 132.dp else 106.dp).height(1.5.dp).background(RlColor.Ink.copy(alpha = .75f)))
            Text(
                "从完整内容中选择 1—3 句，作为这次学习重点。",
                color = RlColor.TextSecondary,
                style = (if (wide) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
                    .copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif),
            )
        }
    }
}

@Composable
private fun WideFocusBody(
    state: FocusSelectionUiState,
    onAction: (FocusSelectionAction) -> Unit,
    modifier: Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier.fillMaxHeight().width(48.dp).padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.rl_focus_punch_holes_v2),
                contentDescription = null,
                modifier = Modifier.fillMaxHeight().aspectRatio(.22f),
                contentScale = ContentScale.FillBounds,
            )
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            FocusSentenceList(state, onAction, Modifier.fillMaxWidth().weight(1f).padding(end = 16.dp), 44.dp)
            FocusFooter(state, onAction, Modifier.fillMaxWidth().height(56.dp))
        }
    }
}

@Composable
private fun CompactFocusBody(
    state: FocusSelectionUiState,
    onAction: (FocusSelectionAction) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(108.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FocusRules(Modifier.weight(1f).fillMaxHeight(), compact = true)
            FocusCounter(state.selectedUnitIds.size, Modifier.weight(.65f).fillMaxHeight(), compact = true)
        }
        FocusSentenceList(state, onAction, Modifier.fillMaxWidth().weight(1f), 72.dp)
        FocusFooter(state, onAction, Modifier.fillMaxWidth().height(108.dp), compact = true)
    }
}

@Composable
private fun FocusSentenceList(
    state: FocusSelectionUiState,
    onAction: (FocusSelectionAction) -> Unit,
    modifier: Modifier,
    cardMinHeight: Dp,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag("focus_sentence_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.units.forEachIndexed { index, unit ->
            val selected = unit.unitId in state.selectedUnitIds
            val enabled = (selected || !state.selectionLimitReached) && !state.submitting
            FocusSentenceCard(
                index = index + 1,
                unit = unit,
                selected = selected,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = cardMinHeight).testTag("focus_sentence_${unit.unitId}"),
                onClick = { onAction(FocusSelectionAction.UnitToggled(unit.unitId)) },
            )
        }
        if (state.units.isEmpty()) Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun FocusSentenceCard(
    index: Int,
    unit: SemanticUnit,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(modifier = modifier.clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)) {
        Image(
            painter = painterResource(R.drawable.rl_ruled_handwriting_paper),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
        )
        if (selected) {
            Box(
                Modifier.matchParentSize().padding(horizontal = 2.dp, vertical = 1.dp)
                    .border(1.2.dp, RlColor.ActionVivid.copy(alpha = .72f), RoundedCornerShape(2.dp)),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                .padding(start = 20.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                index.toString(),
                modifier = Modifier.width(28.dp),
                color = RlColor.Ink,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Cursive, fontSize = 18.sp),
            )
            Box(
                modifier = Modifier.size(28.dp).then(
                    if (selected) Modifier.background(RlColor.ActionVivid, CircleShape)
                    else Modifier.border(1.5.dp, RlColor.BorderStrong, CircleShape),
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(21.dp))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(RlColor.ActionVivid.copy(alpha = .25f)))
            Text(
                unit.textZh,
                modifier = Modifier.weight(1f),
                color = RlColor.Ink,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    letterSpacing = 1.sp,
                ),
            )
            if (unit.recommendationReasonZh != null) {
                Surface(
                    modifier = Modifier.rotate(-5f).semantics { contentDescription = "AI 推荐：${unit.recommendationReasonZh}" },
                    color = Color.Transparent,
                    border = BorderStroke(2.dp, RlColor.ActionVivid),
                    shape = RoundedCornerShape(2.dp),
                ) {
                    Text(
                        "AI 推荐",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = RlColor.ActionVivid,
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold),
                    )
                }
            }
            FocusLineSprig(
                color = if (selected) RlColor.ActionVivid.copy(alpha = .58f) else RlColor.TextSecondary.copy(alpha = .45f),
                modifier = Modifier.size(width = 38.dp, height = 44.dp),
            )
        }
    }
}

@Composable
private fun FocusLineSprig(color: Color, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.rl_v73_focus_sprig), null, modifier,
        contentScale = ContentScale.Fit,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color))
}

@Composable
private fun FocusFooter(
    state: FocusSelectionUiState,
    onAction: (FocusSelectionAction) -> Unit,
    modifier: Modifier,
    compact: Boolean = false,
) {
    if (compact) {
        Column(modifier = modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusInfo(Modifier.fillMaxWidth())
            FocusConfirmButton(state, onAction, Modifier.fillMaxWidth().height(48.dp))
        }
    } else {
        Row(
            modifier = modifier.padding(start = 34.dp, end = 2.dp, top = 8.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            FocusInfo(Modifier.weight(1f))
            FocusConfirmButton(state, onAction, Modifier.fillMaxHeight().width(228.dp))
        }
    }
}

@Composable
private fun FocusInfo(modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Info, null, tint = RlColor.Secondary, modifier = Modifier.size(27.dp))
        Text(
            "推荐只作参考，所有句子都可以选择。",
            color = RlColor.TextSecondary,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif),
        )
    }
}

@Composable
private fun FocusConfirmButton(
    state: FocusSelectionUiState,
    onAction: (FocusSelectionAction) -> Unit,
    modifier: Modifier,
) {
    com.reallanguage.app.core.designsystem.EditorialCompactAction(
        text = stringResource(R.string.focus_confirm),
        onClick = { onAction(FocusSelectionAction.ConfirmRequested) },
        enabled = state.confirmEnabled && !state.submitting,
        modifier = modifier.testTag("focus_confirm"),
        primary = true,
    )
}

@Composable
private fun FocusSelectionRail(state: FocusSelectionUiState, modifier: Modifier) {
    Box(modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(top = 30.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FocusRules(Modifier.fillMaxWidth().height(150.dp))
            Spacer(Modifier.height(16.dp))
            FocusCounter(state.selectedUnitIds.size, Modifier.fillMaxWidth().heightIn(min = 100.dp))
        }
        Image(
            painterResource(R.drawable.rl_home_scene_paperclip), null,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-10).dp, y = (-12).dp)
                .size(width = 27.dp, height = 63.dp).rotate(18f),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun FocusRules(modifier: Modifier, compact: Boolean = false) {
    FocusRailPaper(
        modifier.testTag("focus_rules"),
        if (compact) 16.dp else 14.dp,
        verticalPadding = if (compact) 12.dp else 14.dp,
    ) {
        Text(
            "选择规则",
            color = RlColor.Ink,
            style = (if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
                .copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold),
        )
        Text(
            if (compact) "从完整内容中选择 1—3 句，作为这次学习重点。" else "从完整内容中\n选择 1—3 句\n作为这次学习重点。",
            color = RlColor.TextSecondary,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = if (compact) 13.sp else 10.sp,
                lineHeight = if (compact) 22.sp else 20.sp,
            ),
            softWrap = compact,
            maxLines = if (compact) Int.MAX_VALUE else 3,
        )
        if (!compact) {
            Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(60.dp).height(8.dp), contentScale = ContentScale.FillBounds, alpha = .6f)
        }
    }
}

@Composable
private fun FocusCounter(count: Int, modifier: Modifier, compact: Boolean = false) {
    FocusRailPaper(
        modifier.testTag("focus_counter"),
        if (compact) 12.dp else 20.dp,
        verticalPadding = if (compact) 10.dp else 8.dp,
    ) {
        Text(
            "已选",
            color = Color.White,
            modifier = Modifier.background(RlColor.Secondary).padding(horizontal = 18.dp, vertical = 5.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif),
        )
        Text(
            "$count / 3",
            color = if (count > 0) RlColor.ActionVivid else RlColor.Secondary,
            style = (if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium)
                .copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 26.sp, lineHeight = 34.sp),
        )
    }
}

@Composable
private fun FocusRailPaper(
    modifier: Modifier,
    horizontalPadding: Dp,
    verticalPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        Image(
            painterResource(R.drawable.rl_vertical_torn_paper_card), null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}
