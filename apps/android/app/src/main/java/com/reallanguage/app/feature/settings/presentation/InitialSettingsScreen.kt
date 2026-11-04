package com.reallanguage.app.feature.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalDensity
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.feature.billing.presentation.BillingAction
import com.reallanguage.app.feature.billing.presentation.BillingPaper
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlKeyboardAwareBasicTextField
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlPaperPanel
import com.reallanguage.app.core.designsystem.RlEditorialHeader
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.core.designsystem.RlTextField
import com.reallanguage.app.feature.settings.domain.BasicVocabulary
import com.reallanguage.app.feature.settings.domain.selectableVocabularyPools

internal val LocalSettingsEditorialPaper = staticCompositionLocalOf { false }

/** Source 04–06: lightly ruled torn paper, not the opaque handwriting-canvas export. */
@Composable
private fun SettingsIntroPaper(modifier: Modifier) {
    Box(modifier.graphicsLayer(rotationZ = -2f)) {
        Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.matchParentSize().padding(horizontal = 11.dp, vertical = 16.dp)) {
            val gap = 25.dp.toPx()
            var y = gap
            while (y < size.height) {
                drawLine(RlColor.Primary.copy(alpha = .18f), Offset(0f, y), Offset(size.width, y), .45.dp.toPx())
                y += gap
            }
        }
    }
}

@Composable
fun InitialSettingsScreen(
    state: InitialSettingsUiState,
    onAction: (InitialSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        // The illustrated landscape sheets need a real minimum vertical canvas.
        // On short phones or with enlarged text, use the scrollable stacked
        // composition instead of squeezing/overlapping the reference layout.
        val wide = maxWidth >= 600.dp && maxHeight >= 390.dp &&
            maxWidth > maxHeight * 1.35f && LocalDensity.current.fontScale <= 1.35f
        if (wide) InitialSettingsWideScreen(state, onAction)
        else RlPaperBackground(showCornerCollage = false) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${state.step.ordinal + 1} / 3 · ${state.step.title()}", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 25.sp, lineHeight = 34.sp)
                InitialSettingsCompactIntro(state.step)
                BillingPaper(Modifier.fillMaxWidth(), PaddingValues(18.dp)) {
                    when (state.step) {
                        InitialSettingsStep.EXPRESSION_BASICS -> ExpressionBasics(state, onAction)
                        InitialSettingsStep.USAGE_PREFERENCES -> UsagePreferences(state, onAction)
                        InitialSettingsStep.VOICE_SETTINGS -> VoiceSettings(state, onAction)
                    }
                    if (state.error != null) Text(stringResource(R.string.settings_save_failed), color = RlColor.Error)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.step != InitialSettingsStep.EXPRESSION_BASICS) BillingAction(stringResource(R.string.settings_back), { onAction(InitialSettingsAction.BackRequested) }, Modifier.weight(1f), enabled = !state.submitting, secondary = true)
                        BillingAction(stringResource(if (state.step == InitialSettingsStep.VOICE_SETTINGS) R.string.settings_complete else R.string.settings_continue),
                            { onAction(if (state.step == InitialSettingsStep.VOICE_SETTINGS) InitialSettingsAction.CompleteRequested else InitialSettingsAction.ContinueRequested) }, Modifier.weight(1f), enabled = state.continueEnabled && !state.submitting)
                    }
                }
            }
        }
    }
}

@Composable
private fun InitialSettingsCompactIntro(step: InitialSettingsStep) {
    BillingPaper(Modifier.fillMaxWidth(), PaddingValues(20.dp)) {
        Text("RealLanguage", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 34.sp)
        Text(when (step) {
            InitialSettingsStep.EXPRESSION_BASICS -> "告诉我们，你希望英语听起来是什么样子。"
            InitialSettingsStep.USAGE_PREFERENCES -> "选择适合你的表达方式与使用偏好。"
            InitialSettingsStep.VOICE_SETTINGS -> "选择喜欢的声音，让英语更自然地进入生活。"
        }, color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 17.sp, lineHeight = 27.sp)
        Image(painterResource(if (step == InitialSettingsStep.VOICE_SETTINGS) R.drawable.rl_voice_reel_unit else R.drawable.rl_v73_auth_collage), null, Modifier.fillMaxWidth().height(130.dp), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun InitialSettingsWideScreen(state: InitialSettingsUiState, onAction: (InitialSettingsAction) -> Unit, modifier: Modifier = Modifier) {
    if (state.step == InitialSettingsStep.EXPRESSION_BASICS) {
        ExpressionBasicsScreen(state = state, onAction = onAction, modifier = modifier)
        return
    }
    if (state.step == InitialSettingsStep.USAGE_PREFERENCES) {
        UsagePreferencesScreen(state = state, onAction = onAction, modifier = modifier)
        return
    }
    if (state.step == InitialSettingsStep.VOICE_SETTINGS) {
        VoiceSettingsScreen(state = state, onAction = onAction, modifier = modifier)
        return
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RlColor.Background.copy(alpha = 0f))
            .padding(horizontal = RlSpace.Space24, vertical = RlSpace.Space8),
        contentAlignment = Alignment.Center,
    ) {
        val voiceStep = state.step == InitialSettingsStep.VOICE_SETTINGS
        Row(
            modifier = Modifier
                .widthIn(max = 1480.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space16),
        ) {
            SettingsEditorialIntro(
                step = state.step,
                modifier = Modifier.weight(if (voiceStep) 1.04f else 0.72f).fillMaxHeight(),
            )
            RlPaperPanel(
                modifier = Modifier.weight(if (voiceStep) 1.16f else 1.48f).fillMaxHeight(),
                contentPadding = RlSpace.Space8,
                contentSpacing = 6.dp,
                showSpine = false,
            ) {
                when (state.step) {
                    InitialSettingsStep.EXPRESSION_BASICS -> Unit
                    InitialSettingsStep.USAGE_PREFERENCES -> UsagePreferences(state, onAction)
                    InitialSettingsStep.VOICE_SETTINGS -> VoiceSettings(state, onAction)
                }
                state.error?.let {
                    Text(
                        stringResource(R.string.settings_save_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.step != InitialSettingsStep.EXPRESSION_BASICS) {
                        TextButton(
                            onClick = { onAction(InitialSettingsAction.BackRequested) },
                            enabled = !state.submitting,
                            modifier = Modifier.weight(0.55f),
                        ) {
                            Text(stringResource(R.string.settings_back))
                        }
                    } else {
                        Box(Modifier.weight(0.55f))
                    }
                    RlPrimaryButton(
                        text = stringResource(
                            if (state.step == InitialSettingsStep.VOICE_SETTINGS) {
                                R.string.settings_complete
                            } else {
                                R.string.settings_continue
                            },
                        ),
                        enabled = state.continueEnabled && !state.submitting,
                        onClick = {
                            onAction(
                                if (state.step == InitialSettingsStep.VOICE_SETTINGS) {
                                    InitialSettingsAction.CompleteRequested
                                } else {
                                    InitialSettingsAction.ContinueRequested
                                },
                            )
                        },
                        modifier = Modifier.weight(1f),
                        containerColor = RlColor.ActionVivid,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpressionBasicsScreen(
    state: InitialSettingsUiState,
    onAction: (InitialSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        val compact = maxWidth < 760.dp
        val leftWeight = if (compact) .36f else .34f
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExpressionSettingsEditorialIntro(
                modifier = Modifier.weight(leftWeight).fillMaxHeight(),
            )
            ExpressionBasicsPaperSheet(
                state = state,
                onAction = onAction,
                modifier = Modifier.weight(1f - leftWeight).fillMaxHeight(),
                compact = compact,
            )
        }
    }
}

@Composable
private fun ExpressionSettingsEditorialIntro(modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag("settings-editorial-intro")) {
        Text(
            text = "RealLanguage",
            modifier = Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 14.dp),
            color = RlColor.Ink,
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = 32.sp,
                lineHeight = 37.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Image(
            painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 58.dp)
                .width(38.dp).height(8.dp),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painter = painterResource(R.drawable.rl_editorial_bottom_left_collage),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(.94f).fillMaxHeight(.55f),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painter = painterResource(R.drawable.rl_home_scene_torn_blue_transparent),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 38.dp, bottom = 25.dp)
                .fillMaxWidth(.6f).fillMaxHeight(.17f).graphicsLayer(rotationZ = -13f),
            contentScale = ContentScale.FillBounds,
        )
        SettingsIntroPaper(
            modifier = Modifier.align(Alignment.Center).padding(top = 34.dp, bottom = 47.dp)
                .fillMaxWidth(.91f).fillMaxHeight(.72f),
        )
        Image(
            painter = painterResource(R.drawable.rl_home_scene_paperclip),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 21.dp, top = 82.dp)
                .size(31.dp).graphicsLayer(rotationZ = -24f),
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier.align(Alignment.Center).padding(
                start = 49.dp,
                end = 29.dp,
                top = 35.dp,
                bottom = 52.dp,
            ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Box(
                modifier = Modifier.width(134.dp).height(27.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.rl_torn_terracotta),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Text(
                    "首次设置 · 1 / 3",
                    color = RlColor.OnActionVivid,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                )
            }
            Text(
                "表达基础",
                modifier = Modifier.padding(top = 14.dp),
                color = RlColor.Ink,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 42.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).width(181.dp).height(9.dp),
                contentScale = ContentScale.FillBounds,
            )
            Text(
                "告诉我们，\n你希望英语听起来是什么样子。",
                modifier = Modifier.padding(top = 16.dp),
                color = RlColor.Ink,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.padding(top = 4.dp).width(133.dp).height(8.dp),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

@Composable
private fun ExpressionBasicsPaperSheet(
    state: InitialSettingsUiState,
    onAction: (InitialSettingsAction) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.testTag("settings-expression-paper-sheet")) {
        Image(
            painter = painterResource(R.drawable.rl_torn_note),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
                start = if (compact) 30.dp else 34.dp,
                end = if (compact) 28.dp else 34.dp,
                top = 21.dp,
                bottom = 17.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EditorialSectionHeading("正式／随意程度")
            FormalityScale(
                selected = state.draft.formalityLevel,
                onSelected = { onAction(InitialSettingsAction.FormalityChanged(it)) },
                modifier = Modifier.fillMaxWidth().height(61.dp),
            )
            EditorialSectionHeading("俚语")
            SlangChoiceSheet(
                selected = state.draft.slangEnabled,
                onSelected = { onAction(InitialSettingsAction.SlangChanged(it)) },
                modifier = Modifier.fillMaxWidth().height(70.dp),
            )
            EditorialSectionHeading("学习单词池")
            VocabularyDifficultyCards(
                selected = state.draft.basicVocabulary,
                onSelected = { onAction(InitialSettingsAction.BasicVocabularyChanged(it)) },
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = RlColor.TextSecondary,
                        modifier = Modifier.size(21.dp),
                    )
                    Text(
                        "关闭应用后，本次未完成设置不会保存。",
                        color = RlColor.TextSecondary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    )
                }
                EditorialSettingsButton(
                    text = stringResource(R.string.settings_continue),
                    enabled = state.continueEnabled && !state.submitting,
                    onClick = { onAction(InitialSettingsAction.ContinueRequested) },
                    modifier = Modifier.width(if (compact) 168.dp else 184.dp).heightIn(min = 48.dp),
                )
            }
            state.error?.let {
                Text(
                    stringResource(R.string.settings_save_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun EditorialSectionHeading(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text,
            color = RlColor.Ink,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = 15.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Image(
            painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
            contentDescription = null,
            modifier = Modifier.width(73.dp).height(5.dp),
            contentScale = ContentScale.FillBounds,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FormalityScale(
    selected: Int?,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.testTag("settings-formality-scale")) {
        Image(
            painter = painterResource(R.drawable.rl_torn_note),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Text(
            "更正式",
            modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 6.dp),
            color = RlColor.Ink,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp),
        )
        Text(
            "自然平衡",
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 5.dp),
            color = RlColor.ActionVivid,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            "更随意",
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 6.dp),
            color = RlColor.Ink,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp),
        )
        Slider(
            value = (selected ?: 3).toFloat(),
            onValueChange = { onSelected(it.toInt().coerceIn(1, 5)) },
            valueRange = 1f..5f,
            steps = 3,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp)
                .fillMaxWidth().height(37.dp),
            colors = SliderDefaults.colors(
                thumbColor = RlColor.ActionVivid,
                activeTrackColor = RlColor.TextSecondary,
                inactiveTrackColor = RlColor.TextSecondary,
                activeTickColor = RlColor.TextSecondary,
                inactiveTickColor = RlColor.TextSecondary,
            ),
            thumb = {
                Box(
                    Modifier.size(20.dp).background(RlColor.PaperWarm, CircleShape)
                        .border(1.dp, RlColor.ActionVivid.copy(alpha = .55f), CircleShape)
                        .padding(2.dp).background(RlColor.ActionVivid, CircleShape),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(1.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = RlColor.TextSecondary,
                        inactiveTrackColor = RlColor.TextSecondary,
                        activeTickColor = RlColor.TextSecondary,
                        inactiveTickColor = RlColor.TextSecondary,
                    ),
                    thumbTrackGapSize = 0.dp,
                )
            },
        )
    }
}

@Composable
private fun SlangChoiceSheet(
    selected: Boolean?,
    onSelected: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.testTag("settings-slang-sheet")) {
        Image(
            painter = painterResource(R.drawable.rl_ruled_handwriting_paper),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 5.dp)) {
            SlangChoice(
                title = "不使用",
                example = "I don’t know.",
                selected = selected == false,
                onClick = { onSelected(false) },
                modifier = Modifier.weight(1f).testTag("settings-slang-disabled"),
            )
            SlangChoice(
                title = "适量使用",
                example = "I have no idea.\nThat’s awesome!",
                selected = selected == true,
                onClick = { onSelected(true) },
                modifier = Modifier.weight(1f).testTag("settings-slang-enabled"),
            )
            Box(Modifier.width(66.dp).fillMaxHeight()) {
                Image(
                    painter = painterResource(R.drawable.rl_settings_slang_doodle),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(53.dp),
                    contentScale = ContentScale.Fit,
                )
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = null,
                    tint = RlColor.Ink,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp).size(27.dp),
                )
            }
        }
    }
}

@Composable
private fun SlangChoice(
    title: String,
    example: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxHeight().clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.size(33.dp))
        Column(modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            Text(
                title,
                color = if (selected) RlColor.ActionVivid else RlColor.Ink,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
            )
            Text(
                example,
                modifier = Modifier.padding(top = 3.dp),
                color = RlColor.TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun VocabularyDifficultyCards(
    selected: BasicVocabulary?,
    onSelected: (BasicVocabulary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.testTag("settings-vocabulary-cards"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        selectableVocabularyPools.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { vocabulary ->
                    VocabularyDifficultyCard(
                        title = vocabulary.label(),
                        supporting = "",
                        selected = selected == vocabulary,
                        onClick = { onSelected(vocabulary) },
                        modifier = Modifier.weight(1f).testTag("settings-vocabulary-${vocabulary.name.lowercase()}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun VocabularyDifficultyCard(
    title: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxHeight().clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.rl_torn_note),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null, modifier = Modifier.size(31.dp))
                Text(
                    title,
                    color = if (selected) RlColor.ActionVivid else RlColor.Ink,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            if (supporting.isNotBlank()) {
                Text(
                    supporting,
                    color = RlColor.TextSecondary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun EditorialSettingsButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.testTag("settings-continue-action")
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.rl_torn_terracotta),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds,
            alpha = if (enabled) 1f else .64f,
        )
        Image(
            painter = painterResource(R.drawable.rl_button_masking_tape),
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = 8.dp)
                .width(27.dp).height(36.dp),
            contentScale = ContentScale.FillBounds,
            alpha = .76f,
        )
        Text(
            text,
            color = RlColor.OnActionVivid,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
        )
    }
}

@Composable
private fun UsagePreferencesScreen(
    state: InitialSettingsUiState,
    onAction: (InitialSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreferencesEditorialIntro(Modifier.weight(.34f).fillMaxHeight())
        PreferencesPaperSheet(
            state = state,
            onAction = onAction,
            modifier = Modifier.weight(.66f).fillMaxHeight(),
        )
    }
}

@Composable
private fun PreferencesEditorialIntro(modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag("settings-preferences-editorial-intro")) {
        Text(
            "RealLanguage",
            modifier = Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 14.dp),
            color = RlColor.Ink,
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = 32.sp,
                lineHeight = 37.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Image(
            painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 58.dp)
                .width(38.dp).height(8.dp),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painter = painterResource(R.drawable.rl_editorial_bottom_left_collage),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(.94f).fillMaxHeight(.55f),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painter = painterResource(R.drawable.rl_editorial_blue_collage),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(.62f).fillMaxHeight(.25f),
            contentScale = ContentScale.FillBounds,
        )
        SettingsIntroPaper(
            modifier = Modifier.align(Alignment.Center).padding(top = 34.dp, bottom = 47.dp)
                .fillMaxWidth(.91f).fillMaxHeight(.72f),
        )
        Image(
            painter = painterResource(R.drawable.rl_home_scene_paperclip),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 21.dp, top = 82.dp)
                .size(31.dp).graphicsLayer(rotationZ = -24f),
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier.align(Alignment.Center).padding(
                start = 49.dp,
                end = 29.dp,
                top = 35.dp,
                bottom = 52.dp,
            ),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.width(134.dp).height(27.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.rl_torn_terracotta),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Text(
                    "首次设置 · 2 / 3",
                    color = RlColor.OnActionVivid,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                )
            }
            Text(
                "表达风格",
                modifier = Modifier.padding(top = 14.dp),
                color = RlColor.Ink,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 42.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).width(181.dp).height(9.dp),
                contentScale = ContentScale.FillBounds,
            )
            Text(
                "单词池已经选好，\n这里可以补充希望参考的表达气质。",
                modifier = Modifier.padding(top = 16.dp),
                color = RlColor.Ink,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.padding(top = 4.dp).width(133.dp).height(8.dp),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

@Composable
private fun PreferencesPaperSheet(
    state: InitialSettingsUiState,
    onAction: (InitialSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Image(
            painter = painterResource(R.drawable.rl_torn_note),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 26.dp, end = 29.dp, top = 16.dp, bottom = 15.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TornSectionLabel("当前学习单词池", Modifier.width(177.dp).height(33.dp))
                Text(
                    "8 个词表平级单选，随时可以切换。",
                    color = RlColor.Primary,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Cursive,
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                    ),
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth().height(86.dp).testTag("settings-selected-vocabulary-pool"),
                color = RlColor.PaperWarm,
                border = BorderStroke(1.dp, RlColor.Border.copy(alpha = .72f)),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        state.draft.basicVocabulary?.label() ?: "尚未选择",
                        color = RlColor.Ink,
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif),
                    )
                    Text(
                        "当前池的词义会优先用于内容生成和学习目标标注。",
                        color = RlColor.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TornSectionLabel("表达风格 / AI 角色参考", Modifier.width(205.dp).height(34.dp))
            Box(modifier = Modifier.fillMaxWidth().height(76.dp)) {
                Image(
                    painter = painterResource(R.drawable.rl_ruled_handwriting_paper),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                RlKeyboardAwareBasicTextField(
                    value = state.draft.styleReference,
                    onValueChange = { onAction(InitialSettingsAction.StyleReferenceChanged(it)) },
                    modifier = Modifier.fillMaxSize().padding(start = 34.dp, end = 91.dp, top = 26.dp, bottom = 15.dp)
                        .testTag("settings-style-reference-input"),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = RlColor.Ink,
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontSize = 13.sp,
                    ),
                )
                Image(
                    painter = painterResource(R.drawable.rl_button_masking_tape),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 66.dp).width(53.dp).height(20.dp),
                    contentScale = ContentScale.FillBounds,
                )
                Image(
                    painter = painterResource(R.drawable.rl_settings_tone_doodle),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(55.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Lock, null, tint = RlColor.TextSecondary, modifier = Modifier.size(20.dp))
                    Text(
                        "只保存你明确填写的设置，不自动建立长期画像。",
                        color = RlColor.TextSecondary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    )
                }
                TornNeutralButton(
                    text = stringResource(R.string.settings_back),
                    onClick = { onAction(InitialSettingsAction.BackRequested) },
                    enabled = !state.submitting,
                    modifier = Modifier.width(100.dp).height(43.dp),
                )
                EditorialSettingsButton(
                    text = stringResource(R.string.settings_continue),
                    enabled = state.continueEnabled && !state.submitting,
                    onClick = { onAction(InitialSettingsAction.ContinueRequested) },
                    modifier = Modifier.padding(start = 18.dp).width(165.dp).height(43.dp),
                )
            }
        }
    }
}

@Composable
private fun TornSectionLabel(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Image(
            painter = painterResource(R.drawable.rl_torn_heading_label),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Column(modifier = Modifier.padding(start = 21.dp)) {
            Text(
                text,
                color = RlColor.Ink,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.width(74.dp).height(4.dp),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

@Composable
private fun TornNeutralButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.rl_torn_heading_label),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            alpha = if (enabled) 1f else .6f,
        )
        Text(
            text,
            color = RlColor.Ink,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
        )
    }
}

@Composable
private fun VoiceSettingsScreen(
    state: InitialSettingsUiState,
    onAction: (InitialSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        VoiceEditorialIntro(Modifier.weight(.32f).fillMaxHeight())
        VoiceUnitCollage(Modifier.weight(.21f).fillMaxHeight())
        VoiceSettingsPaperSheet(
            state = state,
            onAction = onAction,
            modifier = Modifier.weight(.47f).fillMaxHeight(),
        )
    }
}

@Composable
private fun VoiceEditorialIntro(modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag("settings-voice-editorial-intro")) {
        Text(
            "RealLanguage",
            modifier = Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 14.dp),
            color = RlColor.Ink,
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = 32.sp,
                lineHeight = 37.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Image(
            painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 32.dp, top = 58.dp)
                .width(38.dp).height(8.dp),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painter = painterResource(R.drawable.rl_editorial_bottom_left_collage),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(.55f),
            contentScale = ContentScale.FillBounds,
        )
        SettingsIntroPaper(
            modifier = Modifier.align(Alignment.Center).padding(top = 34.dp, bottom = 47.dp)
                .fillMaxWidth(.91f).fillMaxHeight(.72f),
        )
        Image(
            painter = painterResource(R.drawable.rl_home_scene_paperclip),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 21.dp, top = 82.dp).size(31.dp).graphicsLayer(rotationZ = -24f),
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier.align(Alignment.Center).offset(y = (-30).dp).padding(
                start = 49.dp,
                end = 23.dp,
                top = 35.dp,
                bottom = 52.dp,
            ),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.width(134.dp).height(27.dp), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.rl_torn_terracotta),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Text(
                    "首次设置 · 3 / 3",
                    color = RlColor.OnActionVivid,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                )
            }
            Text(
                "语音设置",
                modifier = Modifier.padding(top = 8.dp),
                color = RlColor.Ink,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 42.sp,
                    lineHeight = 48.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).width(181.dp).height(9.dp),
                contentScale = ContentScale.FillBounds,
            )
            Text(
                "决定讲解与练习如何为你播放。",
                modifier = Modifier.padding(top = 16.dp),
                color = RlColor.Ink,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

@Composable
private fun VoiceUnitCollage(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Image(
            painter = painterResource(R.drawable.rl_voice_reel_unit),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp)
                .fillMaxWidth().fillMaxHeight(.84f),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 102.dp)
                .fillMaxWidth(.92f).height(72.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.rl_ruled_handwriting_paper),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            Icon(
                Icons.Outlined.Mic,
                contentDescription = null,
                tint = RlColor.Ink,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp).size(27.dp),
            )
            Column(modifier = Modifier.align(Alignment.Center).padding(start = 26.dp)) {
                Text(
                    "美式英语",
                    color = RlColor.Ink,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    "American English",
                    color = RlColor.Ink,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 7.5.sp,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(3) {
                        Icon(
                            Icons.Outlined.Star,
                            contentDescription = null,
                            tint = RlColor.ActionVivid,
                            modifier = Modifier.size(8.dp),
                        )
                    }
                }
            }
            Icon(
                Icons.Outlined.AttachFile,
                null,
                tint = RlColor.Ink,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-5).dp).size(26.dp),
            )
        }
    }
}

@Composable
private fun VoiceSettingsPaperSheet(
    state: InitialSettingsUiState,
    onAction: (InitialSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        // Reserve the full-size controls/footer first. Short landscape windows
        // take space from the decorative top gap, never from touch targets.
        val controlsTop = (maxHeight - 307.dp).coerceIn(0.dp, 116.dp)
        val waveformTop = (controlsTop - 70.dp).coerceAtLeast(3.dp)
        Image(
            painter = painterResource(R.drawable.rl_torn_note),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painter = voiceWaveformPaperPainter(),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopCenter).padding(start = 7.dp, end = 43.dp, top = waveformTop)
                .fillMaxWidth().height(60.dp),
            contentScale = ContentScale.FillBounds,
        )
        Icon(
            Icons.Outlined.MusicNote,
            null,
            tint = RlColor.Primary,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 16.dp).size(24.dp),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 20.dp, top = controlsTop, bottom = 15.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            VoiceTogglePaperRow(
                title = "语音",
                supporting = "关闭后仍保留全部文字内容。",
                checked = state.draft.voiceEnabled == true,
                enabled = !state.submitting,
                icon = Icons.Outlined.VolumeUp,
                onChecked = { onAction(InitialSettingsAction.VoiceChanged(it)) },
                modifier = Modifier.fillMaxWidth().height(57.dp).testTag("settings-voice-toggle"),
            )
            VoiceTogglePaperRow(
                title = "自动播放",
                supporting = "进入讲解或练习时自动播放。",
                checked = state.draft.autoPlayEnabled == true,
                enabled = state.draft.voiceEnabled == true && !state.submitting,
                icon = Icons.Outlined.PlayArrow,
                onChecked = { onAction(InitialSettingsAction.AutoPlayChanged(it)) },
                modifier = Modifier.fillMaxWidth().height(57.dp).testTag("settings-autoplay-toggle"),
            )
            VoiceSpeedPaperRow(
                selected = state.draft.speechSpeedLevel,
                onSelected = { onAction(InitialSettingsAction.SpeechSpeedChanged(it)) },
                modifier = Modifier.fillMaxWidth().height(65.dp).testTag("settings-speech-speed"),
            )
            Box(Modifier.fillMaxWidth().height(45.dp)) {
                Image(
                    painter = painterResource(R.drawable.rl_torn_heading_label),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Text(
                    "默认使用美式英语声音，当前不提供声线选择。",
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp, end = 56.dp),
                    color = RlColor.TextSecondary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.5.sp),
                )
                Icon(
                    Icons.Outlined.Headphones,
                    contentDescription = null,
                    tint = RlColor.Primary,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 22.dp).size(23.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Bottom,
            ) {
                TornNeutralButton(
                    text = stringResource(R.string.settings_back),
                    onClick = { onAction(InitialSettingsAction.BackRequested) },
                    enabled = !state.submitting,
                    modifier = Modifier.width(100.dp).height(48.dp),
                )
                EditorialSettingsButton(
                    text = stringResource(R.string.settings_complete),
                    enabled = state.continueEnabled && !state.submitting,
                    onClick = { onAction(InitialSettingsAction.CompleteRequested) },
                    modifier = Modifier.padding(start = 18.dp).width(165.dp).height(48.dp),
                )
            }
        }
    }
}

@Composable
private fun VoiceTogglePaperRow(
    title: String,
    supporting: String,
    checked: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.clickable(enabled = enabled, role = Role.Switch) {
            onChecked(!checked)
        },
    ) {
        Image(
            painter = voiceSettingPaperPainter(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Icon(
            icon,
            null,
            tint = RlColor.Primary,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(29.dp),
        )
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = 54.dp).width(1.dp).height(35.dp)
                .background(RlColor.Border),
        )
        Column(modifier = Modifier.align(Alignment.CenterStart).padding(start = 66.dp, end = 112.dp)) {
            Text(
                title,
                color = RlColor.Ink,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                supporting,
                color = RlColor.TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 8.5.sp),
            )
        }
        Text(
            if (checked) "开启" else "关闭",
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 76.dp),
            color = if (checked) RlColor.ActionVivid else RlColor.TextSecondary,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = RlColor.PaperWarm,
                checkedTrackColor = RlColor.ActionVivid,
                uncheckedThumbColor = RlColor.TextSecondary,
                uncheckedTrackColor = RlColor.DisabledContainer,
                disabledCheckedThumbColor = RlColor.PaperWarm,
                disabledCheckedTrackColor = RlColor.ActionVivid.copy(alpha = .55f),
            ),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VoiceSpeedPaperRow(
    selected: Int?,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Image(
            painter = voiceSettingPaperPainter(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Icon(
            Icons.Outlined.Speed,
            null,
            tint = RlColor.Primary,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(29.dp),
        )
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = 54.dp).width(1.dp).height(42.dp)
                .background(RlColor.Border),
        )
        Text(
            "语速",
            modifier = Modifier.align(Alignment.TopStart).padding(start = 66.dp, top = 8.dp),
            color = RlColor.Ink,
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Slider(
            value = (selected ?: 3).toFloat(),
            onValueChange = { onSelected(it.toInt().coerceIn(1, 5)) },
            valueRange = 1f..5f,
            steps = 3,
            modifier = Modifier.align(Alignment.Center).padding(start = 130.dp, end = 30.dp, top = 4.dp)
                .fillMaxWidth().height(35.dp),
            colors = SliderDefaults.colors(
                thumbColor = RlColor.ActionVivid,
                activeTrackColor = RlColor.TextSecondary,
                inactiveTrackColor = RlColor.TextSecondary,
            ),
            thumb = {
                Box(
                    Modifier.size(17.dp).background(RlColor.PaperWarm, CircleShape)
                        .border(1.dp, RlColor.ActionVivid.copy(alpha = .55f), CircleShape)
                        .padding(2.dp).background(RlColor.ActionVivid, CircleShape),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(1.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = RlColor.TextSecondary,
                        inactiveTrackColor = RlColor.TextSecondary,
                        activeTickColor = RlColor.TextSecondary,
                        inactiveTickColor = RlColor.TextSecondary,
                    ),
                    thumbTrackGapSize = 0.dp,
                )
            },
        )
        Text(
            "更慢",
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 143.dp, bottom = 6.dp),
            color = RlColor.TextSecondary,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
        )
        Text(
            "标准",
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 70.dp, bottom = 6.dp),
            color = if (selected == 3) RlColor.ActionVivid else RlColor.TextSecondary,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
        )
        Text(
            "更快",
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 34.dp, bottom = 6.dp),
            color = RlColor.TextSecondary,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
        )
    }
}

@Composable
private fun SettingsEditorialIntro(
    step: InitialSettingsStep,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Image(
            painter = painterResource(R.drawable.rl_editorial_corner_collage),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.62f),
            contentScale = ContentScale.Crop,
        )
        Image(
            painter = painterResource(R.drawable.rl_torn_note),
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterStart)
                .fillMaxWidth(if (step == InitialSettingsStep.VOICE_SETTINGS) .54f else .92f)
                .fillMaxHeight(.64f),
            contentScale = ContentScale.FillBounds,
        )
        if (step == InitialSettingsStep.VOICE_SETTINGS) {
            Image(
                painter = painterResource(R.drawable.rl_recording_collage),
                contentDescription = null,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(.54f).fillMaxHeight(.9f),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(
                start = RlSpace.Space24,
                end = if (step == InitialSettingsStep.VOICE_SETTINGS) RlSpace.Space64 else RlSpace.Space24,
                top = RlSpace.Space24,
                bottom = RlSpace.Space24,
            ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                "初次设置 · ${step.number()} / 3",
                color = RlColor.OnActionVivid,
                modifier = Modifier.background(RlColor.ActionVivid).padding(
                    horizontal = RlSpace.Space12,
                    vertical = RlSpace.Space4,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                step.sectionTitle(),
                modifier = Modifier.padding(top = RlSpace.Space20),
                color = RlColor.Ink,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                ),
            )
            Text(
                step.editorialPrompt(),
                modifier = Modifier.padding(top = RlSpace.Space20),
                color = RlColor.TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

private fun InitialSettingsStep.number(): Int = when (this) {
    InitialSettingsStep.EXPRESSION_BASICS -> 1
    InitialSettingsStep.USAGE_PREFERENCES -> 2
    InitialSettingsStep.VOICE_SETTINGS -> 3
}

private fun InitialSettingsStep.sectionTitle(): String = when (this) {
    InitialSettingsStep.EXPRESSION_BASICS -> "表达基础"
    InitialSettingsStep.USAGE_PREFERENCES -> "表达风格"
    InitialSettingsStep.VOICE_SETTINGS -> "语音设置"
}

private fun InitialSettingsStep.supportingText(): String = when (this) {
    InitialSettingsStep.EXPRESSION_BASICS -> "先告诉我们，你希望英语听起来是什么样子。"
    InitialSettingsStep.USAGE_PREFERENCES -> "补充你偏好的表达气质。"
    InitialSettingsStep.VOICE_SETTINGS -> "设定朗读方式，随时可以再调整。"
}

private fun InitialSettingsStep.editorialPrompt(): String = when (this) {
    InitialSettingsStep.EXPRESSION_BASICS -> "告诉我们，\n你希望英语听起来是什么样子。"
    InitialSettingsStep.USAGE_PREFERENCES -> "告诉我们，\n你希望表达呈现怎样的气质。"
    InitialSettingsStep.VOICE_SETTINGS -> "选择一种，\n让你愿意反复聆听的声音。"
}

@Composable
internal fun ExpressionBasics(
    state: InitialSettingsUiState,
    onAction: (InitialSettingsAction) -> Unit,
) {
    Text(
        stringResource(R.string.settings_formality),
        color = RlColor.Ink,
        style = MaterialTheme.typography.titleMedium,
    )
    SettingsPaperCard {
        Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("更正式", style = MaterialTheme.typography.bodyMedium)
            Text("更随意", style = MaterialTheme.typography.bodyMedium)
        }
        FiveLevelChoices(selected = state.draft.formalityLevel, onSelected = { onAction(InitialSettingsAction.FormalityChanged(it)) })
        }
    }
    Text(
        stringResource(R.string.settings_slang),
        color = RlColor.Ink,
        style = MaterialTheme.typography.titleMedium,
    )
    SettingsPaperCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space16),
        ) {
            BooleanChoice(
                title = "不使用",
                selected = state.draft.slangEnabled == false,
                onClick = { onAction(InitialSettingsAction.SlangChanged(false)) },
                modifier = Modifier.weight(1f).testTag("settings-slang-disabled"),
            )
            BooleanChoice(
                title = "适量使用",
                selected = state.draft.slangEnabled == true,
                onClick = { onAction(InitialSettingsAction.SlangChanged(true)) },
                modifier = Modifier.weight(1f).testTag("settings-slang-enabled"),
            )
        }
    }
    Text(
        stringResource(R.string.settings_basic_vocabulary),
        color = RlColor.Ink,
        style = MaterialTheme.typography.titleMedium,
    )
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 26.dp) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
        ) {
            selectableVocabularyPools.forEach { vocabulary ->
                FilterChip(
                    modifier = Modifier.heightIn(min = 48.dp).testTag("settings-vocabulary-${vocabulary.name.lowercase()}"),
                    selected = state.draft.basicVocabulary == vocabulary,
                    onClick = {
                        onAction(InitialSettingsAction.BasicVocabularyChanged(vocabulary))
                    },
                    label = { Text(vocabulary.label(), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
internal fun UsagePreferences(
    state: InitialSettingsUiState,
    onAction: (InitialSettingsAction) -> Unit,
) {
    Text(
        "学习单词池",
        color = RlColor.Ink,
        style = MaterialTheme.typography.titleMedium,
    )
    SettingsPaperCard {
        Text(
            state.draft.basicVocabulary?.label() ?: "请在表达基础中选择",
            color = RlColor.Ink,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "词汇难度与使用偏好已合并为一个单词池，不再重复选择。",
            color = RlColor.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    RlTextField(
        value = state.draft.styleReference,
        label = stringResource(R.string.settings_style_reference),
        onValueChange = { onAction(InitialSettingsAction.StyleReferenceChanged(it)) },
        modifier = Modifier.testTag("settings-style-reference-input"),
    )
}

@Composable
internal fun VoiceSettings(
    state: InitialSettingsUiState,
    onAction: (InitialSettingsAction) -> Unit,
) {
    SettingsToggleRow(
        label = stringResource(R.string.settings_voice_enabled),
        value = state.draft.voiceEnabled,
        onChanged = { onAction(InitialSettingsAction.VoiceChanged(it)) },
    )
    SettingsToggleRow(
        label = stringResource(R.string.settings_auto_play),
        value = state.draft.autoPlayEnabled,
        enabled = state.draft.voiceEnabled == true,
        onChanged = { onAction(InitialSettingsAction.AutoPlayChanged(it)) },
    )
    SettingsPaperCard {
        Text(stringResource(R.string.settings_speech_speed), style = MaterialTheme.typography.titleMedium)
        FiveLevelChoices(
            selected = state.draft.speechSpeedLevel,
            onSelected = { onAction(InitialSettingsAction.SpeechSpeedChanged(it)) },
        )
    }
    SettingsPaperCard {
        Text(
            stringResource(R.string.settings_default_accent),
            color = RlColor.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SettingsPaperCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    if (LocalSettingsEditorialPaper.current) {
        Box(Modifier.fillMaxWidth()) {
            Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null,
                Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontFamily = EditorialFont.Serif,
                    fontSize = 13.sp, lineHeight = 20.sp)) { content() }
            }
        }
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = RlColor.PaperWarm,
        border = BorderStroke(1.dp, RlColor.Border.copy(alpha = .72f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = RlSpace.Space16,
                vertical = RlSpace.Space8,
            ),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
            content = content,
        )
    }
}

@Composable
private fun BooleanChoice(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalSettingsEditorialPaper.current) {
        Row(modifier.clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = selected, onClick = null)
            Text(title, color = if (selected) RlColor.ActionVivid else RlColor.Ink,
                fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 21.sp)
        }
        return
    }
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = if (selected) RlColor.ActionVividContainer else RlColor.Surface,
        border = BorderStroke(1.dp, if (selected) RlColor.ActionVivid else RlColor.Border),
    ) {
        Row(
            modifier = Modifier.padding(RlSpace.Space12),
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(title, color = RlColor.Ink)
        }
    }
}

@Composable
private fun FiveLevelChoices(selected: Int?, onSelected: (Int) -> Unit) {
    if (LocalSettingsEditorialPaper.current) {
        val numberDiameter = maxOf(27.dp, 23.dp * LocalDensity.current.fontScale)
        val targetDiameter = maxOf(48.dp, numberDiameter + 12.dp)
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(.7.dp).background(RlColor.Border))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..5).forEach { level ->
                    Box(Modifier.size(targetDiameter).clickable(role = Role.RadioButton) { onSelected(level) }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(numberDiameter).background(if (selected == level) RlColor.ActionVivid else RlColor.PaperWarm, CircleShape)
                            .border(.7.dp, if (selected == level) RlColor.ActionVivid else RlColor.Border, CircleShape), contentAlignment = Alignment.Center) {
                            Text(level.toString(), color = if (selected == level) RlColor.OnPrimary else RlColor.TextSecondary,
                                fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
            }
        }
        return
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 26.dp) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..5).forEach { level ->
                FilterChip(
                    selected = selected == level,
                    onClick = { onSelected(level) },
                    label = { Text(level.toString(), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    value: Boolean?,
    onChanged: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    SettingsPaperCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f).padding(end = 12.dp), color = RlColor.Ink, style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = value == true,
                onCheckedChange = onChanged,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun BooleanSetting(
    label: String,
    value: Boolean?,
    onChanged: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RlSpace.Space8)) {
        Text(label)
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 26.dp) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8)) {
                FilterChip(
                    selected = value == true,
                    onClick = { onChanged(true) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.settings_enabled), style = MaterialTheme.typography.labelSmall) },
                )
                FilterChip(
                    selected = value == false,
                    onClick = { onChanged(false) },
                    enabled = enabled,
                    label = { Text(stringResource(R.string.settings_disabled), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
private fun FiveLevelSelector(
    label: String,
    selected: Int?,
    onSelected: (Int) -> Unit,
) {
    Column {
        Text(label)
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 26.dp) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8)) {
                (1..5).forEach { level ->
                    FilterChip(
                        selected = selected == level,
                        onClick = { onSelected(level) },
                        label = { Text(level.toString(), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InitialSettingsStep.title(): String = stringResource(
    when (this) {
        InitialSettingsStep.EXPRESSION_BASICS -> R.string.settings_expression_basics
        InitialSettingsStep.USAGE_PREFERENCES -> R.string.settings_usage_preferences
        InitialSettingsStep.VOICE_SETTINGS -> R.string.settings_voice
    },
)

@Composable
private fun BasicVocabulary.label(): String = stringResource(
    when (this) {
        BasicVocabulary.PRIMARY_SCHOOL -> R.string.settings_retired_vocabulary_pool
        BasicVocabulary.JUNIOR_HIGH -> R.string.settings_junior_high
        BasicVocabulary.SENIOR_HIGH -> R.string.settings_senior_high
        BasicVocabulary.CET_4 -> R.string.settings_cet_4
        BasicVocabulary.CET_6 -> R.string.settings_cet_6
        BasicVocabulary.POSTGRADUATE -> R.string.settings_postgraduate
        BasicVocabulary.TOEFL -> R.string.settings_toefl
        BasicVocabulary.IELTS -> R.string.settings_ielts
        BasicVocabulary.GRE -> R.string.settings_gre
        BasicVocabulary.TEM_8 -> R.string.settings_retired_vocabulary_pool
    },
)
