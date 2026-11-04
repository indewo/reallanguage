package com.reallanguage.app.feature.settings.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.feature.billing.presentation.BillingPaper
import com.reallanguage.app.feature.billing.presentation.BillingAction
import com.reallanguage.app.feature.billing.presentation.BillingLabel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlLedgerWorkspace
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.feature.settings.domain.SettingsSourceContext

@Composable
fun SettingsEffectScopeScreen(
    state: SettingsEffectScopeUiState,
    onAction: (SettingsEffectScopeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { if (!state.applying) onAction(SettingsEffectScopeAction.CancelRequested) }
    val choices = effectChoices(state.sourceContext)
    var selectedIndex by remember(state.sourceContext) { mutableIntStateOf(0) }
    val conflicted = state.error == SettingsEffectScopeError.VERSION_CONFLICT
    val canChoose = !state.applying && state.saveProgress == null && !conflicted

    val controls: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RlSpace.Space4)) {
                if (state.error != null) {
                    Text(
                        stringResource(if (conflicted) R.string.settings_version_conflict
                            else settingsSaveMessageResource(state.saveProgress, R.string.settings_effect_failed)),
                        modifier = Modifier.fillMaxWidth().testTag("settings-effect-save-status"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RlSpace.Space8)) {
                    BillingAction(
                        text = stringResource(if (state.saveProgress?.partiallySaved == true || conflicted) R.string.settings_back else R.string.settings_effect_cancel),
                        onClick = { onAction(SettingsEffectScopeAction.CancelRequested) },
                        enabled = !state.applying,
                        modifier = Modifier.weight(.8f).testTag("settings-effect-cancel"),
                        secondary = true,
                    )
                    BillingAction(
                        text = if (state.error != null && !conflicted) stringResource(R.string.diary_retry) else "确认范围",
                        enabled = choices.isNotEmpty() && !state.applying && state.saveProgress?.completed != true && !conflicted,
                        onClick = {
                            if (state.error != null) onAction(SettingsEffectScopeAction.RetryRequested)
                            else choices.getOrNull(selectedIndex)?.let { onAction(it.action) }
                        },
                        modifier = Modifier.weight(1f).testTag("settings-effect-confirm"),
                        color = RlColor.Primary,
                    )
                }
            }
    }
    RlPaperBackground(modifier, showCornerCollage = false) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 720.dp && LocalDensity.current.fontScale <= 1.35f
            val panelHeight = (maxHeight - 16.dp).coerceAtLeast(350.dp)
            val guide: @Composable (Modifier) -> Unit = { guideModifier ->
                Box(guideModifier) {
                    Image(painterResource(R.drawable.rl_editorial_blue_panel), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = if (wide) 12.dp else 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton({ if (!state.applying) onAction(SettingsEffectScopeAction.CancelRequested) }, Modifier.testTag("ledger-back"), enabled = !state.applying) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.PaperWarm) }
                        SettingsEffectGuide(wide)
                    }
                }
            }
            val form: @Composable (Modifier, Boolean) -> Unit = { formModifier, includeControls ->
                Column(formModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("设置生效范围", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 27.sp, lineHeight = 36.sp)
                            Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(150.dp).height(5.dp), contentScale = ContentScale.FillBounds)
                            Text("选择这次修改如何应用", color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 22.sp)
                        }
                        if (wide) Box(Modifier.width(125.dp).height(68.dp).testTag("settings-effect-confirm-note"), contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.rl_registration_age_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                            Text("确认后再继续", Modifier.padding(12.dp), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 20.sp)
                        }
                    }
                    BillingPaper(Modifier.fillMaxWidth(), PaddingValues(20.dp), spacing = 16.dp) {
                        choices.forEachIndexed { index, choice -> EffectScopeOptionCard(choice, selectedIndex == index, canChoose) { if (canChoose) selectedIndex = index } }
                        if (includeControls) controls()
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                guide(Modifier.weight(.31f).heightIn(min = panelHeight))
                form(Modifier.weight(.69f).padding(top = 8.dp), true)
            } else Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    guide(Modifier.fillMaxWidth())
                    form(Modifier.fillMaxWidth(), false)
                }
                controls()
            }
        }
    }
}

@Composable
private fun SettingsEffectGuide(wide: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("settings-effect-guide")) {
    val labelsInsideDiagram = wide && maxWidth >= 200.dp && LocalDensity.current.fontScale <= 1.15f
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
    ) {
        Text("如何生效", color = RlColor.PaperWarm, fontFamily = EditorialFont.Serif, fontSize = 22.sp, lineHeight = 30.sp)
        Text(
            "根据你选择的范围，新设置将以不同方式应用到学习内容。",
            color = RlColor.PaperWarm,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 22.sp),
        )
        if (labelsInsideDiagram) Box(Modifier.fillMaxWidth().height(150.dp)) {
            Image(painterResource(R.drawable.rl_v73_settings_effect_diagram), null,
                Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    EffectDiagramLabel("已固定内容", R.drawable.rl_olive_torn_paper)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    EffectDiagramLabel("新内容", R.drawable.rl_home_scene_torn_blue_transparent)
                }
            }
        } else {
            Image(painterResource(R.drawable.rl_v73_settings_effect_diagram), null,
                Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Fit)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EffectDiagramLabel("已固定内容", R.drawable.rl_olive_torn_paper, Modifier.weight(1f))
                EffectDiagramLabel("新内容", R.drawable.rl_home_scene_torn_blue_transparent, Modifier.weight(1f))
            }
        }
        // Keep the note on unobstructed blue paper, away from the lower-left
        // newspaper collage. Large text uses the natural-height compact branch.
        Row(Modifier.fillMaxWidth().then(if (wide) Modifier.padding(start = 49.dp, end = 2.dp)
            else Modifier.background(RlColor.Primary.copy(alpha = .94f)).padding(10.dp)),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = RlColor.PaperWarm)
            Text(
                "已固定的学习内容不会被直接覆盖",
                Modifier.weight(1f),
                color = RlColor.PaperWarm,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 20.sp),
            )
        }
    }
    }
}

@Composable
private fun EffectDiagramLabel(text: String, paper: Int, modifier: Modifier = Modifier) {
    Box(modifier.widthIn(min = 70.dp), contentAlignment = Alignment.Center) {
        Image(painterResource(paper), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = RlColor.PaperWarm,
            fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun SettingsEffectConfirmNote() {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f))
        Box(
            modifier = Modifier.width(150.dp).height(54.dp)
                .testTag("settings-effect-confirm-note"),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.rl_registration_age_note),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            Text("确认后再继续", color = RlColor.Ink, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class EffectChoice(
    val title: String,
    val supporting: String,
    val action: SettingsEffectScopeAction,
)

private fun effectChoices(context: SettingsSourceContext): List<EffectChoice> = when (context) {
    is SettingsSourceContext.UnfixedContent -> listOf(
        EffectChoice(
            "按新设置重新处理",
            "重新处理当前内容，可能产生新的 AI 费用",
            SettingsEffectScopeAction.ReprocessRequested,
        ),
        EffectChoice(
            "从下一份内容生效",
            "当前流程不变，新设置用于之后的内容",
            SettingsEffectScopeAction.NextContentRequested,
        ),
    )
    is SettingsSourceContext.FixedContent -> listOf(
        EffectChoice(
            "基于固定来源新开流程",
            "保留现有版本，从相同来源重新开始",
            SettingsEffectScopeAction.ReprocessRequested,
        ),
        EffectChoice(
            "从下一份内容生效",
            "当前流程不变，新设置用于之后的内容",
            SettingsEffectScopeAction.NextContentRequested,
        ),
    )
    SettingsSourceContext.NoCurrentContent -> listOf(
        EffectChoice(
            "从下一份内容生效",
            "新设置用于之后创建的内容",
            SettingsEffectScopeAction.NextContentRequested,
        ),
    )
}

@Composable
private fun EffectScopeOptionCard(
    choice: EffectChoice,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp).testTag("settings-effect-option"),
        color = RlColor.PaperWarm,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) RlColor.ActionVivid else RlColor.Border,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp)
                .padding(horizontal = RlSpace.Space12, vertical = RlSpace.Space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space12),
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Icon(if (choice.action == SettingsEffectScopeAction.NextContentRequested) Icons.Outlined.CalendarMonth else Icons.Outlined.AutoFixHigh, null, Modifier.width(30.dp).height(32.dp), tint = if (selected) RlColor.ActionVivid else RlColor.Secondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(choice.title, Modifier.fillMaxWidth(), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 17.sp, lineHeight = 26.sp)
                Text(choice.supporting, Modifier.fillMaxWidth(), color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 21.sp)
            }
        }
    }
}
