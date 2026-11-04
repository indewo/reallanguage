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
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Mic
import com.reallanguage.app.core.designsystem.EditorialFont
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
    BackHandler { if (!state.saving) onAction(SubsequentSettingsAction.ReturnRequested) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val groupOffsets = remember { mutableStateListOf<Int?>(null, null, null) }
    var navigationAnchor by remember { mutableStateOf<SettingsNavigationAnchor?>(null) }
    fun SettingsNavigationAnchor.matchesViewport(): Boolean =
        scrollOffset == scrollState.value && scrollMaximum == scrollState.maxValue && groupOffset == groupOffsets[index]
    LaunchedEffect(scrollState.value, scrollState.maxValue, groupOffsets.toList()) {
        if (navigationAnchor?.matchesViewport() == false) navigationAnchor = null
    }
    val selectedGroup by remember(scrollState) {
        derivedStateOf {
            // Several groups can fit together at the bottom of a tall viewport. The requested
            // visible group stays active there, but any real scroll or reflow invalidates it.
            val requested = navigationAnchor?.takeIf { it.matchesViewport() }
            if (requested != null) requested.index
            else if (scrollState.maxValue > 0 && scrollState.value == scrollState.maxValue) 2
            else groupOffsets.indexOfLast { it != null && it <= scrollState.value + 1 }.coerceAtLeast(0)
        }
    }
    val navigationEnabled = !state.loading && state.draft != null && !state.saving
    SubsequentSettingsFrame(
        modifier = modifier,
        scrollState = scrollState,
        onBack = { if (!state.saving) onAction(SubsequentSettingsAction.ReturnRequested) },
        navigation = {
            SubsequentSettingsNavigation(selectedGroup, navigationEnabled) { index ->
                groupOffsets[index]?.let { offset ->
                    navigationAnchor = null
                    scope.launch {
                        scrollState.scrollTo(offset.coerceAtMost(scrollState.maxValue))
                        navigationAnchor = SettingsNavigationAnchor(index, scrollState.value, scrollState.maxValue, offset)
                    }
                }
            }
        },
        note = { SettingsEffectNote(it) },
        footer = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RlSpace.Space4)) {
                SettingsSaveStatus(state)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compactActions = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.35f
                Row(Modifier.fillMaxWidth().padding(end = if (compactActions) 0.dp else 38.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onAction(SubsequentSettingsAction.ReturnRequested) },
                        enabled = !state.saving,
                        modifier = (if (compactActions) Modifier.weight(.6f) else Modifier.widthIn(min = 90.dp)).heightIn(min = 48.dp).testTag("subsequent-settings-return"),
                    ) { Text(stringResource(R.string.settings_back)) }
                    BillingAction(
                        text = stringResource(R.string.subsequent_settings_save),
                        enabled = state.changed && !state.saving && !state.loading && state.error != SubsequentSettingsError.VERSION_CONFLICT,
                        onClick = { onAction(SubsequentSettingsAction.SaveRequested) },
                        modifier = (if (compactActions) Modifier.weight(1f) else Modifier.width(156.dp)).heightIn(min = 48.dp).testTag("subsequent-settings-save"),
                        color = RlColor.ActionVivid,
                    )
                }
                }
            }
        },
    ) {
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
                    else -> CompositionLocalProvider(LocalSettingsEditorialPaper provides true) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 20.sp)) {
                            SettingsGroups(state, onAction, onReminderEnableRequested, reminderSchedulingMessage) { index, offset ->
                                if (groupOffsets[index] != offset) groupOffsets[index] = offset
                            }
                        }
                    }
                }
    }
}

private data class SettingsNavigationAnchor(val index: Int, val scrollOffset: Int, val scrollMaximum: Int, val groupOffset: Int)

@Composable
private fun SubsequentSettingsNavigation(selectedGroup: Int, enabled: Boolean, onSelected: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    if (maxWidth >= 250.dp) {
        FlowRow(Modifier.fillMaxWidth().testTag("subsequent-settings-navigation"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("表达基础", "表达风格", "朗读声音").forEachIndexed { index, label ->
                FilterChip(index == selectedGroup, { onSelected(index) }, enabled = enabled, label = { Text(label) }, modifier = Modifier.testTag("subsequent-settings-navigation-$index"))
            }
        }
        return@BoxWithConstraints
    }
    val compact = maxWidth < 140.dp
    Column(
        modifier = Modifier.fillMaxWidth().testTag("subsequent-settings-navigation"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        listOf("表达基础", "表达风格", "朗读声音").forEachIndexed { index, label ->
            Box(
                modifier = Modifier.fillMaxWidth().padding(start = if (index == 0) 0.dp else 7.dp)
                    .heightIn(min = if (index == 0) 64.dp else 58.dp)
                    .testTag("subsequent-settings-navigation-$index")
                    .selectable(selected = index == selectedGroup, enabled = enabled, role = Role.Button,
                        onClick = { onSelected(index) }),
                contentAlignment = Alignment.CenterStart,
            ) {
                Image(
                    painter = painterResource(R.drawable.rl_vocabulary_card_paper_r11),
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds,
                )
                if (!compact) Icon(when (index) { 0 -> Icons.Outlined.Explore; 1 -> Icons.Outlined.Eco; else -> Icons.Outlined.Mic }, null,
                    Modifier.padding(start = 15.dp).size(28.dp), tint = if (index == selectedGroup) RlColor.ActionVivid else RlColor.Secondary)
                Text(
                    text = label,
                    modifier = Modifier.padding(start = if (compact) 12.dp else if (index == 0) 60.dp else 53.dp,
                        end = 16.dp, top = 12.dp, bottom = 12.dp),
                    color = RlColor.Ink,
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = EditorialFont.Serif, fontSize = 17.sp, lineHeight = 24.sp),
                )
                if (index == selectedGroup) {
                    Box(
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = RlSpace.Space8)
                            .size(12.dp).background(RlColor.ActionVivid, CircleShape),
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun SettingsEffectNote(modifier: Modifier) {
        Box(
            modifier = modifier.testTag("subsequent-settings-effect-note"),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.rl_registration_age_note),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            Text(
                text = "新设置会\n从下一份内容生效",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = RlColor.Ink,
                style = MaterialTheme.typography.bodySmall,
            )
        }
}

@Composable
private fun SettingsGroups(
    state: SubsequentSettingsUiState,
    onAction: (SubsequentSettingsAction) -> Unit,
    onReminderEnableRequested: (() -> Unit)?,
    reminderSchedulingMessage: String?,
    onGroupPositionChanged: (Int, Int) -> Unit,
) {
    val draft = checkNotNull(state.draft)
    val editable = !state.saving && state.error != SubsequentSettingsError.VERSION_CONFLICT
    val initialState = InitialSettingsUiState(draft = draft, submitting = !editable)
    val bridge: (InitialSettingsAction) -> Unit = { action ->
        if (editable) action.toSubsequent()?.let(onAction)
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("subsequent-settings-group")
            .onGloballyPositioned { onGroupPositionChanged(0, it.positionInParent().y.roundToInt()) },
        verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
    ) {
        Text("①  ${stringResource(R.string.settings_expression_basics)}", style = MaterialTheme.typography.titleMedium)
        ExpressionBasics(initialState, bridge)
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("subsequent-settings-group")
            .onGloballyPositioned { onGroupPositionChanged(1, it.positionInParent().y.roundToInt()) },
        verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
    ) {
        Text("②  ${stringResource(R.string.settings_usage_preferences)}", style = MaterialTheme.typography.titleMedium)
        UsagePreferences(initialState, bridge)
        ReminderPreferences(state, onAction, onReminderEnableRequested, reminderSchedulingMessage)
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag("subsequent-settings-group")
            .onGloballyPositioned { onGroupPositionChanged(2, it.positionInParent().y.roundToInt()) },
        verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
    ) {
        Text("③  ${stringResource(R.string.settings_voice)}", style = MaterialTheme.typography.titleMedium)
        VoiceSettings(initialState, bridge)
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
    var picker by remember { mutableStateOf<TimePickerDialog?>(null) }
    val currentState by rememberUpdatedState(state)
    val currentAction by rememberUpdatedState(onAction)
    DisposableEffect(Unit) { onDispose { picker?.dismiss() } }
    DisposableEffect(editable) {
        if (!editable) picker?.dismiss()
        onDispose { }
    }
    Box(Modifier.fillMaxWidth().testTag("settings-daily-reminder")) {
        Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RlSpace.Space16, vertical = RlSpace.Space8),
            verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space16)) {
                Text(label, Modifier.weight(1f), color = RlColor.Ink, style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = draft.enabled,
                    onCheckedChange = { enabled ->
                        if (enabled && onReminderEnableRequested != null) onReminderEnableRequested()
                        else onAction(SubsequentSettingsAction.ReminderEnabledChanged(enabled))
                    },
                    enabled = editable,
                    modifier = Modifier.testTag("settings-reminder-enabled").semantics { contentDescription = label },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = RlColor.Surface,
                        checkedTrackColor = RlColor.Secondary,
                        uncheckedThumbColor = RlColor.TextSecondary,
                        uncheckedTrackColor = RlColor.PaperWarm,
                        uncheckedBorderColor = RlColor.Border,
                    ),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RlSpace.Space16)) {
                Text(timeLabel, Modifier.weight(1f), color = RlColor.Ink, style = MaterialTheme.typography.bodyLarge)
                TextButton(
                    enabled = editable,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("settings-reminder-time")
                        .semantics { contentDescription = "$timeLabel $time" },
                    onClick = {
                        if (picker?.isShowing == true) return@TextButton
                        picker = TimePickerDialog(context, { _, hour, minute ->
                            if (!currentState.saving && currentState.error != SubsequentSettingsError.VERSION_CONFLICT && currentState.reminderDraft != null) {
                                currentAction(SubsequentSettingsAction.ReminderTimeChanged(LocalTime.of(hour, minute)))
                            }
                        }, draft.time.hour, draft.time.minute, true).apply {
                            setTitle(timeLabel)
                            setOnDismissListener { picker = null }
                            show()
                        }
                    },
                ) { Text(time, color = RlColor.Secondary, style = MaterialTheme.typography.titleMedium) }
            }
            Text(stringResource(R.string.settings_reminder_frequency), color = RlColor.TextSecondary,
                style = MaterialTheme.typography.bodySmall)
            reminderSchedulingMessage?.let { ReminderSchedulingStatusText(it) }
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
