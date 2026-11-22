package com.reallanguage.app.feature.settings.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.settings.domain.*

@Composable
internal fun SettingsChoice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
    OutlinedButton(onClick, modifier.heightIn(min = 32.dp), enabled = enabled,
        shape = RlShape.Small, border = BorderStroke(.6.dp, RlColor.Border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) SettingsSelected else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (selected) RlColor.OnPrimary else RlColor.Ink), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp)) {
        Text(label, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
    }
    }
}

@Composable
private fun SettingsLine(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("•", color = RlColor.ActionVivid)
        Text(label, Modifier.weight(.55f), fontFamily = EditorialFont.Serif, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.weight(1.4f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
    HorizontalDivider(color = RlColor.Border.copy(alpha = .55f))
}

@Composable
internal fun EditorialExpressionBasics(state: InitialSettingsUiState, onAction: (InitialSettingsAction) -> Unit) {
    SettingsLine("正式程度") {
        Text("更正式", fontSize = 11.sp)
        (1..5).forEach { level -> SettingsChoice("$level", state.draft.formalityLevel == level, !state.submitting, Modifier.weight(1f)) { onAction(InitialSettingsAction.FormalityChanged(level)) } }
        Text("更随意", fontSize = 11.sp)
    }
    SettingsLine("俚语与口语表达") {
        SettingsChoice("关闭", state.draft.slangEnabled == false, !state.submitting) { onAction(InitialSettingsAction.SlangChanged(false)) }
        SettingsChoice("开启", state.draft.slangEnabled == true, !state.submitting) { onAction(InitialSettingsAction.SlangChanged(true)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("•", color = RlColor.ActionVivid, modifier = Modifier.padding(top = 10.dp))
        Text("基础词汇范围", Modifier.weight(.55f).padding(top = 10.dp), fontFamily = EditorialFont.Serif, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            selectableVocabularyPools.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { pool -> SettingsChoice(pool.label(), state.draft.basicVocabulary == pool, !state.submitting,
                        Modifier.testTag("settings-vocabulary-${pool.name.lowercase()}")) { onAction(InitialSettingsAction.BasicVocabularyChanged(pool)) } }
                }
            }
        }
    }
}

@Composable
internal fun EditorialVoiceSettings(state: InitialSettingsUiState, onAction: (InitialSettingsAction) -> Unit) {
    SettingsLine("语音开关") {
        Spacer(Modifier.weight(1f))
        Switch(state.draft.voiceEnabled == true, { onAction(InitialSettingsAction.VoiceChanged(it)) }, enabled = !state.submitting, modifier = Modifier.scale(.8f), colors = editorialSwitchColors())
    }
    SettingsLine("自动播放") {
        Spacer(Modifier.weight(1f))
        Switch(state.draft.autoPlayEnabled == true, { onAction(InitialSettingsAction.AutoPlayChanged(it)) }, enabled = !state.submitting && state.draft.voiceEnabled == true, modifier = Modifier.scale(.8f), colors = editorialSwitchColors())
    }
    SettingsLine("语速") {
        (1..5).forEach { level -> SettingsChoice("$level", state.draft.speechSpeedLevel == level, !state.submitting, Modifier.weight(1f)) { onAction(InitialSettingsAction.SpeechSpeedChanged(level)) } }
    }
    Text("默认使用美式英语声音", fontSize = 12.sp, color = RlColor.TextSecondary)
}

@Composable
private fun editorialSwitchColors() = SwitchDefaults.colors(checkedTrackColor = SettingsSelected, checkedThumbColor = RlColor.OnPrimary,
    uncheckedTrackColor = RlColor.PaperWarm, uncheckedThumbColor = RlColor.TextSecondary, uncheckedBorderColor = RlColor.Border)
