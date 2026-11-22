package com.reallanguage.app.feature.settings.presentation

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Mic
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.platform.LocalDensity
import com.reallanguage.app.feature.billing.presentation.BillingAction
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import java.time.LocalTime
import java.util.Locale
import com.reallanguage.app.feature.settings.domain.SettingsSaveProgress
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SubsequentSettingsScreen(
    state: SubsequentSettingsUiState,
    onAction: (SubsequentSettingsAction) -> Unit,
    modifier: Modifier = Modifier,
    onReminderEnableRequested: (() -> Unit)? = null,
    reminderSchedulingMessage: String? = null,
) {
    var showUnsaved by remember { mutableStateOf(false) }
    val requestReturn: () -> Unit = {
        if (!state.saving) {
            if (state.changed) showUnsaved = true else onAction(SubsequentSettingsAction.ReturnRequested)
        }
    }
    BackHandler(onBack = requestReturn)
    if (showUnsaved) androidx.compose.material3.AlertDialog(
        onDismissRequest = { showUnsaved = false },
        title = { Text("保存修改？") }, text = { Text("当前设置尚未保存。") },
        confirmButton = { TextButton(onClick = { showUnsaved = false; onAction(SubsequentSettingsAction.SaveRequested) }) { Text("保存并返回") } },
        dismissButton = { Row { TextButton(onClick = { showUnsaved = false }) { Text("继续编辑") }; TextButton(onClick = { showUnsaved = false; onAction(SubsequentSettingsAction.ReturnRequested) }) { Text("放弃修改") } } },
    )
    val scrollState = rememberScrollState()
    var selectedGroup by remember { mutableStateOf(0) }
    val navigationEnabled = !state.loading && state.draft != null && !state.saving
    SubsequentSettingsFrame(
        modifier = modifier,
        scrollState = scrollState,
        onBack = requestReturn,
        navigation = {
            SubsequentSettingsNavigation(selectedGroup, navigationEnabled) { index ->
                selectedGroup = index

            }
        },
        note = {},
        footer = {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = RlColor.Border.copy(alpha = .5f), modifier = Modifier.padding(bottom = 6.dp))
                SettingsSaveStatus(state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selectedGroup == 3) "提醒设置保存后生效" else "新设置从下一份内容生效", Modifier.weight(1f), color = RlColor.TextSecondary, fontSize = 12.sp)
                    Button(
                        enabled = state.changed && !state.saving && !state.loading && state.error != SubsequentSettingsError.VERSION_CONFLICT,
                        onClick = { onAction(SubsequentSettingsAction.SaveRequested) },
                        modifier = Modifier.width(156.dp).heightIn(min = 36.dp).testTag("subsequent-settings-save"), shape = RlShape.Small,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAD583A)),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)) {
                        Text(stringResource(R.string.subsequent_settings_save), fontFamily = EditorialFont.Serif, fontSize = 15.sp, fontWeight = FontWeight.Normal)
                    }
                }
            }
        },    ) {
                when {
                    state.loading -> Text(stringResource(R.string.subsequent_settings_loading))
                    state.draft == null -> {
                        Text(stringResource(R.string.subsequent_settings_load_failed), color = MaterialTheme.colorScheme.error)
                        RlPrimaryButton(
                            text = stringResource(R.string.diary_retry),
                            enabled = true,
                            onClick = { onAction(SubsequentSettingsAction.RetryRequested) },
                        )
                    }
                    else -> CompositionLocalProvider(LocalSettingsEditorialPaper provides true, LocalContentColor provides RlColor.Ink) {
                        val typography = MaterialTheme.typography
                        MaterialTheme(typography = typography.copy(
                            titleMedium = typography.titleMedium.copy(fontFamily = EditorialFont.Serif, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
                            titleLarge = typography.titleLarge.copy(fontFamily = EditorialFont.Serif, fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
                            bodyMedium = typography.bodyMedium.copy(fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 21.sp),
                            bodyLarge = typography.bodyLarge.copy(fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 22.sp),
                            bodySmall = typography.bodySmall.copy(fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp),
                        )) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            val initial = InitialSettingsUiState(draft = state.draft, submitting = state.saving || state.error == SubsequentSettingsError.VERSION_CONFLICT)
                            val bridge: (InitialSettingsAction) -> Unit = { action -> action.toSubsequent()?.let(onAction) }
                            when (selectedGroup) {
                                0 -> EditorialExpressionBasics(initial, bridge)
                                1 -> {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("•", color = RlColor.ActionVivid)
                                        Text("学习单词池", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                        Surface(color = Color.Transparent, shape = RlShape.Small, border = BorderStroke(.6.dp, RlColor.Border)) {
                                            Box(Modifier.width(84.dp).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                                                Text(state.draft.basicVocabulary?.label() ?: "尚未选择", Modifier.testTag("settings-shared-vocabulary-value"))
                                            }
                                        }
                                        TextButton(onClick = { selectedGroup = 0 }, enabled = !state.saving, modifier = Modifier.testTag("settings-edit-shared-vocabulary")) { Text("在表达基础中调整 ›", fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = RlColor.Ink) }
                                    }
                                    HorizontalDivider(color = RlColor.Border.copy(alpha = .55f))
                                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("•", color = RlColor.ActionVivid)
                                        Text("表达风格／AI 角色参考", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                    }
                                    Text("选填。", color = RlColor.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 24.dp))
                                    OutlinedTextField(value = state.draft.styleReference, onValueChange = { onAction(SubsequentSettingsAction.StyleReferenceChanged(it)) },
                                        enabled = !initial.submitting, modifier = Modifier.fillMaxWidth().height(84.dp).testTag("settings-style-reference"),
                                        minLines = 2, maxLines = 6, shape = RlShape.Small,
                                        placeholder = { Text("例如：清楚、自然、直接，像同事间交流一样。", fontFamily = EditorialFont.Serif, fontSize = 14.sp) },
                                        textStyle = TextStyle(fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 21.sp, color = RlColor.Ink),
                                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = RlColor.Border, focusedBorderColor = SettingsOlive,
                                            unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent))
                                }
                                2 -> EditorialVoiceSettings(initial, bridge)
                                3 -> ReminderPreferences(state, onAction, onReminderEnableRequested, reminderSchedulingMessage)
                            }

                        }
                        }
                    }
                }
    }
}

@Composable
private fun SubsequentSettingsNavigation(selectedGroup: Int, enabled: Boolean, onSelected: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("subsequent-settings-navigation"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("表达基础", "表达风格", "朗读声音", "学习提醒").forEachIndexed { index, label ->
            Box(Modifier.fillMaxWidth().height(42.dp).settingsPaper(if (selectedGroup == index) SettingsOlive else SettingsPaper).testTag("subsequent-settings-navigation-$index")
                .selectable(selected = selectedGroup == index, enabled = enabled, role = Role.Tab, onClick = { onSelected(index) })) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(when(index) { 0 -> Icons.Outlined.Edit; 1 -> Icons.Outlined.Layers; 2 -> Icons.Outlined.Headphones; else -> Icons.Outlined.NotificationsNone }, null,
                        Modifier.size(20.dp), tint = if (selectedGroup == index) RlColor.OnPrimary else RlColor.Ink)
                    Text(label, color = if (selectedGroup == index) RlColor.OnPrimary else RlColor.Ink,
                        fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 22.sp)
                }
            }
        }
    }
}
@Composable
private fun ReminderPreferences(
    state: SubsequentSettingsUiState,
    onAction: (SubsequentSettingsAction) -> Unit,
    onReminderEnableRequested: (() -> Unit)?,
    reminderSchedulingMessage: String?,
) {
    val draft = state.reminderDraft ?: return
    val editable = !state.saving && state.error != SubsequentSettingsError.VERSION_CONFLICT
    val context = LocalContext.current
    val label = stringResource(R.string.settings_daily_reminder)
    val timeLabel = stringResource(R.string.settings_reminder_time)
    val time = String.format(Locale.ROOT, "%02d:%02d", draft.time.hour, draft.time.minute)
    var showTimePicker by remember { mutableStateOf(false) }
    if (showTimePicker && editable) ReminderWheelTimeDialog(draft.time,
        onDismiss = { showTimePicker = false },
        onConfirm = { onAction(SubsequentSettingsAction.ReminderTimeChanged(it)); showTimePicker = false })
    Box(Modifier.fillMaxWidth().testTag("settings-daily-reminder")) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space16)) {
                Text("•", color = RlColor.ActionVivid)
                Text(label, Modifier.weight(1f), color = RlColor.Ink, style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = draft.enabled,
                    onCheckedChange = { enabled ->
                        if (enabled && onReminderEnableRequested != null) onReminderEnableRequested()
                        else onAction(SubsequentSettingsAction.ReminderEnabledChanged(enabled))
                    },
                    enabled = editable,
                    modifier = Modifier.scale(.8f).testTag("settings-reminder-enabled").semantics { contentDescription = label },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = RlColor.Surface,
                        checkedTrackColor = SettingsSelected,
                        uncheckedThumbColor = RlColor.TextSecondary,
                        uncheckedTrackColor = RlColor.PaperWarm,
                        uncheckedBorderColor = RlColor.Border,
                    ),
                )
            }
            HorizontalDivider(color = RlColor.Border.copy(alpha = .55f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space16)) {
                Text("•", color = RlColor.ActionVivid)
                Text(timeLabel, Modifier.weight(1f), color = RlColor.Ink, style = MaterialTheme.typography.bodyLarge)
                TextButton(
                    enabled = editable,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("settings-reminder-time")
                        .semantics { contentDescription = "$timeLabel $time" },
                    onClick = { showTimePicker = true },
                ) { Text("$time  ›", color = RlColor.Ink, style = MaterialTheme.typography.titleMedium) }
            }
            Text(stringResource(R.string.settings_reminder_frequency), color = RlColor.TextSecondary,
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = RlColor.Border.copy(alpha = .55f))
        }
    }
}

@Composable
private fun SettingsSaveStatus(state: SubsequentSettingsUiState) {
    if (state.error != SubsequentSettingsError.SAVE_FAILED && state.error != SubsequentSettingsError.VERSION_CONFLICT) return
    val message = if (state.error == SubsequentSettingsError.VERSION_CONFLICT) R.string.settings_version_conflict
        else settingsSaveMessageResource(state.saveProgress, R.string.settings_save_failed)
    Text(stringResource(message),
        modifier = Modifier.fillMaxWidth().testTag("settings-save-status"),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

internal fun settingsSaveMessageResource(progress: SettingsSaveProgress?, fallback: Int): Int = when {
        progress?.partiallySaved == true && progress.settingsSaved -> R.string.settings_saved_reminder_unconfirmed
        progress?.reminderRequired == true && !progress.settingsRequired -> R.string.settings_reminder_save_unconfirmed
        progress?.reminderRequired == true && !progress.settingsSaved -> R.string.settings_mixed_save_unconfirmed
        else -> fallback
}

private fun InitialSettingsAction.toSubsequent(): SubsequentSettingsAction? = when (this) {
    is InitialSettingsAction.FormalityChanged -> SubsequentSettingsAction.FormalityChanged(level)
    is InitialSettingsAction.SlangChanged -> SubsequentSettingsAction.SlangChanged(enabled)
    is InitialSettingsAction.BasicVocabularyChanged -> SubsequentSettingsAction.BasicVocabularyChanged(vocabulary)
    is InitialSettingsAction.PurposeVocabularyChanged -> SubsequentSettingsAction.PurposeVocabularyChanged(vocabulary, selected)
    is InitialSettingsAction.StyleReferenceChanged -> SubsequentSettingsAction.StyleReferenceChanged(value)
    is InitialSettingsAction.VoiceChanged -> SubsequentSettingsAction.VoiceChanged(enabled)
    is InitialSettingsAction.AutoPlayChanged -> SubsequentSettingsAction.AutoPlayChanged(enabled)
    is InitialSettingsAction.SpeechSpeedChanged -> SubsequentSettingsAction.SpeechSpeedChanged(level)
    InitialSettingsAction.ContinueRequested,
    InitialSettingsAction.BackRequested,
    InitialSettingsAction.CompleteRequested -> null
}
