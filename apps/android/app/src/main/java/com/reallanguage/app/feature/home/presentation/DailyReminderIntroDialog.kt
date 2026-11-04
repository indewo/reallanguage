package com.reallanguage.app.feature.home.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlPaperPanel
import com.reallanguage.app.core.designsystem.RlImmersiveDialogWindow

@Composable
fun DailyReminderIntroDialog(
    state: DailyReminderIntroUiState,
    onAction: (DailyReminderIntroAction) -> Unit,
) {
    if (!state.visible) return
    Dialog(
        onDismissRequest = { onAction(DailyReminderIntroAction.NotNowRequested) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        RlImmersiveDialogWindow()
        DailyReminderIntroContent(state, onAction)
    }
}

@Composable
internal fun DailyReminderIntroContent(
    state: DailyReminderIntroUiState,
    onAction: (DailyReminderIntroAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    RlPaperBackground(modifier.testTag("daily-reminder-sheet"), showCornerCollage = false) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val compact = maxWidth < 700.dp || maxHeight < 400.dp || LocalDensity.current.fontScale > 1.3f || state.error != null
            if (maxWidth < 700.dp || maxHeight < 340.dp || LocalDensity.current.fontScale > 1.35f) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ onAction(DailyReminderIntroAction.NotNowRequested) }, enabled = !state.saving) {
                            Image(painterResource(R.drawable.ic_arrow_forward), "返回", Modifier.size(28.dp).rotate(180f))
                        }
                        Text(stringResource(R.string.daily_reminder_page_title), color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 36.sp)
                    }
                    ReminderCalendar(Modifier.fillMaxWidth().height(260.dp), true)
                    com.reallanguage.app.feature.billing.presentation.BillingPaper(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.daily_reminder_intro_explanation), color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 15.sp, lineHeight = 24.sp)
                        ReminderTimeline()
                        ReminderPermissionNote(true)
                        if (state.error != null) Text(stringResource(R.string.daily_reminder_intro_failed), color = RlColor.Error, fontSize = 14.sp, lineHeight = 22.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ReminderChoiceButton(stringResource(if (state.error != null) R.string.diary_retry else R.string.daily_reminder_intro_enable),
                                { onAction(if (state.error != null) DailyReminderIntroAction.RetryRequested else DailyReminderIntroAction.EnableRequested) }, !state.saving, true, Modifier.weight(1f).testTag("daily-reminder-enable"))
                            ReminderChoiceButton(stringResource(R.string.daily_reminder_intro_not_now), { onAction(DailyReminderIntroAction.NotNowRequested) }, !state.saving, false, Modifier.weight(1f).testTag("daily-reminder-not-now"))
                        }
                        Text("你可以随时在设置中关闭", Modifier.fillMaxWidth().testTag("daily-reminder-settings-assurance"), color = RlColor.TextSecondary, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                    }
                }
                return@BoxWithConstraints
            }
            Column(Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    IconButton(onClick = { onAction(DailyReminderIntroAction.NotNowRequested) }, enabled = !state.saving,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp)) {
                        Image(painterResource(R.drawable.ic_arrow_forward), stringResource(R.string.daily_reminder_intro_not_now),
                            Modifier.size(28.dp).rotate(180f), contentScale = ContentScale.Fit)
                    }
                    Column(Modifier.align(Alignment.Center).padding(horizontal = 64.dp), horizontalAlignment = Alignment.Start) {
                        Text(stringResource(R.string.daily_reminder_page_title), color = RlColor.Ink,
                            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp)
                        Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
                            Modifier.padding(start = 4.dp).width(56.dp).height(8.dp).testTag("daily-reminder-title-underline"),
                            contentScale = ContentScale.FillBounds)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().weight(1f).padding(end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReminderCalendar(Modifier.weight(.44f).fillMaxHeight(), compact)
                    Box(Modifier.weight(.56f).fillMaxHeight().testTag("daily-reminder-main-panel")) {
                        RlPaperPanel(Modifier.fillMaxSize(), contentPadding = 0.dp, contentSpacing = 0.dp,
                            showSpine = false, showTopAccent = false, contentModifier = Modifier.fillMaxSize()) {
                            // A display cutout can reduce the usable height without
                            // crossing the compact breakpoint. Measure complete text
                            // and scroll only the right paper when it does not fit.
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(horizontal = if (compact) 4.dp else 20.dp, vertical = 0.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(Modifier.height(if (compact) 12.dp else 32.dp))
                                Text(stringResource(R.string.daily_reminder_intro_explanation), color = RlColor.Ink,
                                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(if (compact) 12.dp else 28.dp))
                                ReminderTimeline()
                                Spacer(Modifier.height(if (compact) 12.dp else 4.dp))
                                ReminderPermissionNote(compact)
                                Spacer(Modifier.height(if (compact) 12.dp else 6.dp))
                                if (state.error != null) {
                                    Text(stringResource(R.string.daily_reminder_intro_failed), color = MaterialTheme.colorScheme.error,
                                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                                    Spacer(Modifier.height(12.dp))
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    ReminderChoiceButton(stringResource(if (state.error != null) R.string.diary_retry else R.string.daily_reminder_intro_enable),
                                        { onAction(if (state.error != null) DailyReminderIntroAction.RetryRequested else DailyReminderIntroAction.EnableRequested) },
                                        !state.saving, true, Modifier.weight(1f).testTag("daily-reminder-enable"))
                                    ReminderChoiceButton(stringResource(R.string.daily_reminder_intro_not_now),
                                        { onAction(DailyReminderIntroAction.NotNowRequested) }, !state.saving, false,
                                        Modifier.weight(1f).testTag("daily-reminder-not-now"))
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center) {
                                    if (!compact) Box(Modifier.width(38.dp).height(.5.dp).background(RlColor.TextSecondary.copy(alpha = .45f)))
                                    Icon(Icons.Outlined.Security, null, Modifier.padding(horizontal = 10.dp).size(16.dp), tint = RlColor.Primary)
                                    Text("你可以随时在设置中关闭", Modifier.weight(1f).testTag("daily-reminder-settings-assurance"),
                                        color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp,
                                        textAlign = TextAlign.Center)
                                    if (!compact) Box(Modifier.padding(start = 12.dp).width(38.dp).height(.5.dp).background(RlColor.TextSecondary.copy(alpha = .45f)))
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        Icon(Icons.Filled.Bookmark, null, Modifier.align(Alignment.TopEnd).offset(y = (-8).dp)
                            .padding(end = 16.dp).width(38.dp).height(52.dp), tint = RlColor.Primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderCalendar(modifier: Modifier, compact: Boolean) {
    BoxWithConstraints(modifier) {
        Image(painterResource(R.drawable.rl_daily_reminder_calendar_collage_v2), null,
            Modifier.fillMaxWidth().height(maxHeight + 28.dp).offset(y = (-28).dp), contentScale = ContentScale.FillBounds)
        Box(Modifier.align(Alignment.BottomStart).padding(start = maxWidth * .10f, bottom = 28.dp)
            .width(maxWidth * .68f).heightIn(min = if (compact) 108.dp else 116.dp)) {
            Image(painterResource(R.drawable.rl_ruled_handwriting_paper), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                val slogan = stringResource(R.string.daily_reminder_slogan).split('\n', limit = 2)
                Text(slogan.first(), color = RlColor.ActionVivid, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = if (compact) 20.sp else 28.sp, lineHeight = if (compact) 26.sp else 34.sp)
                Text(slogan.getOrElse(1) { "" }, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = if (compact) 20.sp else 26.sp, lineHeight = if (compact) 26.sp else 34.sp)
            }
        }
    }
}

@Composable
private fun ReminderPermissionNote(compact: Boolean) {
    Box(Modifier.width(if (compact) 180.dp else 148.dp).heightIn(min = 64.dp).rotate(3f)
        .testTag("daily-reminder-permission-note"), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.rl_registration_age_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Text(stringResource(R.string.daily_reminder_intro_permission_timing).replace("，", "，\n"),
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp), color = RlColor.Ink,
            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Start)
    }
}

@Composable
private fun ReminderChoiceButton(text: String, onClick: () -> Unit, enabled: Boolean, primary: Boolean, modifier: Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(7.dp),
        border = if (primary) null else BorderStroke(.8.dp, RlColor.Ink),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) RlColor.ActionVivid else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (primary) RlColor.OnPrimary else RlColor.Ink,
            disabledContainerColor = RlColor.DisabledContainer, disabledContentColor = RlColor.TextSecondary),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Text(text, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ReminderTimeline() {
    val ink = RlColor.Ink
    val marker = RlColor.Secondary
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().height(if (fontScale > 1.3f) 100.dp else 72.dp).testTag("daily-reminder-timeline")) {
        val width = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val axisY = 36.dp.toPx()
            drawLine(ink.copy(alpha = .75f), Offset(size.width * .05f, axisY), Offset(size.width * .93f, axisY),
                strokeWidth = .7.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())))
            listOf(.12f, .38f, .83f).forEach { drawCircle(ink, 2.5.dp.toPx(), Offset(size.width * it, axisY)) }
        }
        listOf(Triple(Icons.Outlined.WbTwilight, "早晨", .12f), Triple(Icons.Filled.WbSunny, "白天", .38f), Triple(Icons.Filled.NightsStay, "晚上", .83f))
            .forEachIndexed { index, (icon, label, fraction) ->
                Column(Modifier.offset(x = width * fraction - 28.dp).width(56.dp).testTag("daily-reminder-time"), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, null, Modifier.size(30.dp).then(when (index) {
                        1 -> Modifier.testTag("daily-reminder-daytime-icon")
                        2 -> Modifier.testTag("daily-reminder-evening-icon")
                        else -> Modifier
                    }), tint = marker)
                    Spacer(Modifier.height(18.dp))
                    Text(label, color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        Surface(Modifier.offset(x = width * .595f - 18.dp, y = 18.dp).size(36.dp).testTag("daily-reminder-notification-marker"),
            shape = CircleShape, color = RlColor.ActionVivid, border = BorderStroke(.7.dp, RlColor.OnPrimary), shadowElevation = 1.dp) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Notifications, null, Modifier.size(22.dp), tint = RlColor.OnPrimary) }
        }
    }
}
