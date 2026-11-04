package com.reallanguage.app.feature.auth.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlPaperPanel
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.R

@Composable
fun AgeIneligibleScreen(
    onReturnToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shortLandscape = LocalConfiguration.current.screenHeightDp < 390
    AuthResponsivePage(modifier, sheetModifier = Modifier.testTag("age-paper-sheet"), illustration = { AgeEditorialIntro(it.testTag("age-editorial-intro")) }) { wide ->
      Column(Modifier.fillMaxWidth().offset(x = if (wide) (-23).dp else 0.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.widthIn(max = 310.dp).fillMaxWidth().padding(top = if (wide && !shortLandscape) 52.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f).height(.7.dp).background(RlColor.Border))
            Image(painterResource(R.drawable.rl_mustard_botanical_seal), null, Modifier.size(39.dp))
            Box(Modifier.weight(1f).height(.7.dp).background(RlColor.Border))
        }
        Text(stringResource(R.string.auth_age_ineligible), color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = if (shortLandscape) 29.sp else if (wide) 36.sp else 34.sp, lineHeight = if (shortLandscape) 36.sp else if (wide) 46.sp else 44.sp)
        Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(124.dp).height(8.dp), contentScale = ContentScale.FillBounds)
        Text(stringResource(R.string.auth_age_ineligible_explanation), color = RlColor.Ink, fontSize = 14.sp, lineHeight = 23.sp)
        Text(stringResource(R.string.auth_age_ineligible_follow_up), color = RlColor.TextSecondary, fontSize = 14.sp, lineHeight = 23.sp)
        EditorialRoundedAction(stringResource(R.string.auth_return_to_login), true, onReturnToLogin,
            Modifier.padding(top = if (wide && !shortLandscape) 16.dp else 4.dp).widthIn(max = 272.dp).fillMaxWidth().heightIn(min = 48.dp).testTag("age-return-action"))
      }
    }
}

@Composable
private fun LegacyAgeIneligibleScreen(onReturnToLogin: () -> Unit, modifier: Modifier = Modifier) {
    RlPaperBackground(modifier) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = RlSpace.Space16, vertical = RlSpace.Space4),
        ) {
            val introWidth = maxWidth * .33f
            Row(Modifier.fillMaxSize()) {
                AgeEditorialIntro(
                    modifier = Modifier.width(introWidth).fillMaxHeight()
                        .testTag("age-editorial-intro"),
                )
                AgePaperSheet(
                    onReturnToLogin = onReturnToLogin,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .testTag("age-paper-sheet"),
                )
            }
            Image(
                painter = painterResource(R.drawable.rl_editorial_top_right_collage),
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-1).dp)
                    .width(160.dp).height(65.dp),
                contentScale = ContentScale.Fit,
            )
            Image(
                painter = painterResource(R.drawable.rl_editorial_bottom_right_grid_botanical),
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp)
                    .width(140.dp).height(120.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun AgeEditorialIntro(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        Image(
            painter = painterResource(R.drawable.rl_v73_age_collage),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .fillMaxHeight(.74f),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 24.dp, top = 24.dp),
        ) {
            Text(
                text = "RealLanguage",
                color = RlColor.Ink,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 34.sp,
                    lineHeight = 38.sp,
                ),
            )
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.padding(top = 5.dp).width(38.dp).height(5.dp),
                contentScale = ContentScale.FillBounds,
            )
        }
        Box(
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 24.dp, y = (-38).dp)
                .width(minOf(240.dp * fontScale, maxWidth * .85f)).height(238.dp * fontScale).testTag("age-admission-guide").graphicsLayer(rotationZ = -4f),
        ) {
            Image(
                painter = painterResource(R.drawable.rl_vertical_torn_paper_card),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
            Column(
                modifier = Modifier.align(Alignment.Center).padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RlSpace.Space8),
            ) {
                Text(
                    text = "ADMISSION GUIDE",
                    color = RlColor.TextSecondary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 17.sp, letterSpacing = 1.sp),
                )
                Box(Modifier.width(146.dp).height(1.dp).background(RlColor.Border))
                Text(
                    text = "适用年龄",
                    color = RlColor.Ink,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontSize = 18.sp,
                        lineHeight = 25.sp,
                    ),
                )
                Text(
                    text = "14+",
                    color = RlColor.ActionVivid,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontSize = 68.sp,
                        lineHeight = 70.sp,
                    ),
                )
                Text(
                    text = "岁及以上",
                    color = RlColor.Ink,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 17.sp, lineHeight = 24.sp),
                )
            }
            Image(
                painter = painterResource(R.drawable.rl_black_binder_clip),
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-24).dp)
                    .width(60.dp).height(70.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun AgePaperSheet(
    onReturnToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.rl_torn_note),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleY = 1.12f),
            contentScale = ContentScale.FillBounds,
        )
        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 78.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space16),
        ) {
            Box(Modifier.width(112.dp).height(1.dp).background(RlColor.BorderStrong).alpha(.55f))
            Image(
                painter = painterResource(R.drawable.rl_mustard_botanical_seal),
                contentDescription = null,
                modifier = Modifier.size(39.dp),
                contentScale = ContentScale.Fit,
            )
            Box(Modifier.width(112.dp).height(1.dp).background(RlColor.BorderStrong).alpha(.55f))
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 126.dp)
                .fillMaxWidth(.60f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.auth_age_ineligible),
                color = RlColor.Ink,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 36.sp,
                    lineHeight = 42.sp,
                ),
            )
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.padding(top = 6.dp).width(112.dp).height(8.dp),
                contentScale = ContentScale.FillBounds,
            )
            Text(
                text = stringResource(R.string.auth_age_ineligible_explanation),
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                color = RlColor.Ink,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
            )
            Text(
                text = stringResource(R.string.auth_age_ineligible_follow_up),
                modifier = Modifier.padding(top = 7.dp).fillMaxWidth(),
                color = RlColor.TextSecondary,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            )
            RlPrimaryButton(
                text = stringResource(R.string.auth_return_to_login),
                enabled = true,
                onClick = onReturnToLogin,
                modifier = Modifier.padding(top = 27.dp).width(274.dp).height(50.dp)
                    .testTag("age-return-action"),
                containerColor = RlColor.ActionVivid,
            )
        }
    }
}

enum class EmailVerificationStatus {
    WAITING,
    VERIFIED,
    FAILED,
    RESEND_COOLDOWN,
}

data class EmailVerificationUiState(
    val email: String,
    val status: EmailVerificationStatus,
    val resendCooldownSeconds: Int? = null,
    val resending: Boolean = false,
    val submitError: AuthSubmitError? = null,
)

sealed interface EmailVerificationAction {
    data object ResendRequested : EmailVerificationAction
    data object ChangeEmailRequested : EmailVerificationAction
}

sealed interface EmailVerificationEffect {
    data object OpenRegistration : EmailVerificationEffect
    data object CompleteRegistration : EmailVerificationEffect
}

@Composable
fun EmailVerificationScreen(
    state: EmailVerificationUiState,
    onAction: (EmailVerificationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shortLandscape = LocalConfiguration.current.screenHeightDp < 390
    AuthResponsivePage(modifier, leftFraction = .41f, sheetModifier = Modifier.testTag("email-verification-paper-sheet"), sheetArtwork = { wide ->
        if (wide) {
            Image(painterResource(R.drawable.rl_email_paper_plane_path), null, Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 80.dp).width(96.dp).height(130.dp), contentScale = ContentScale.Fit)
            Image(painterResource(R.drawable.rl_email_dried_flower), null, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 15.dp).width(60.dp).height(150.dp), contentScale = ContentScale.Fit)
        }
    }, illustration = { EmailVerificationEditorial(state, it.testTag("email-verification-editorial")) }) { wide ->
      Column(Modifier.widthIn(max = 255.dp).fillMaxWidth().padding(top = if (wide && !shortLandscape) 28.dp else 0.dp).offset(x = if (wide) (-21).dp else 0.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (wide) 0.dp else 10.dp)) {
        Image(painterResource(R.drawable.rl_email_postal_header), null, Modifier.width(105.dp).height(40.dp), contentScale = ContentScale.Fit)
        Text("验证你的邮箱", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = if (shortLandscape) 25.sp else if (wide) 30.sp else 27.sp, lineHeight = if (shortLandscape) 32.sp else if (wide) 40.sp else 36.sp)
        Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(118.dp).height(7.dp), contentScale = ContentScale.FillBounds)
        Text(state.email, Modifier.padding(top = if (wide) 4.dp else 0.dp), color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
        Text(state.status.primaryMessage(state.resendCooldownSeconds), color = state.status.messageColor(), fontSize = 13.sp, lineHeight = 22.sp)
        Box(Modifier.fillMaxWidth().padding(vertical = if (wide) 9.dp else 0.dp).height(.7.dp).background(RlColor.Border))
        Text("没有收到？你可以重新发送，或更换邮箱。", color = RlColor.TextSecondary, fontSize = 12.sp, lineHeight = 20.sp)
        state.submitError?.let { Text(it.message(), color = RlColor.Error) }
        EditorialRoundedAction(stringResource(R.string.auth_resend),
            state.status != EmailVerificationStatus.RESEND_COOLDOWN && state.status != EmailVerificationStatus.VERIFIED && !state.resending,
            { onAction(EmailVerificationAction.ResendRequested) }, Modifier.padding(top = if (wide) 11.dp else 0.dp).widthIn(max = 214.dp).fillMaxWidth().testTag("email-verification-resend"))
        TextButton({ onAction(EmailVerificationAction.ChangeEmailRequested) }, enabled = !state.resending, modifier = Modifier.testTag("email-verification-change-email")) {
            Text(stringResource(R.string.auth_change_email), color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 16.sp, lineHeight = 22.sp)
        }
      }
    }
}

@Composable
private fun LegacyEmailVerificationScreen(state: EmailVerificationUiState, onAction: (EmailVerificationAction) -> Unit, modifier: Modifier = Modifier) {
    RlPaperBackground(modifier) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = RlSpace.Space16, vertical = RlSpace.Space4),
        ) {
            val editorialWidth = maxWidth * .41f
            Row(Modifier.fillMaxSize()) {
                EmailVerificationEditorial(
                    state = state,
                    modifier = Modifier.width(editorialWidth).fillMaxHeight().zIndex(2f)
                        .testTag("email-verification-editorial"),
                )
                EmailVerificationPaperSheet(
                    state = state,
                    onAction = onAction,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .testTag("email-verification-paper-sheet"),
                )
            }
            Image(
                painter = painterResource(R.drawable.rl_editorial_top_right_collage),
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-1).dp)
                    .width(160.dp).height(65.dp),
                contentScale = ContentScale.Fit,
            )
            Image(
                painter = painterResource(R.drawable.rl_editorial_bottom_right_grid_botanical),
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp)
                    .width(140.dp).height(120.dp),
                contentScale = ContentScale.Fit,
            )
            Image(
                painter = painterResource(R.drawable.rl_email_dried_flower),
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-32).dp, y = 8.dp)
                    .width(76.dp).height(156.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun EmailVerificationEditorial(
    state: EmailVerificationUiState,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        Image(
            painter = painterResource(R.drawable.rl_editorial_bottom_left_collage),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(.72f)
                .fillMaxHeight(.67f),
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 24.dp, top = 23.dp),
        ) {
            Text(
                text = "RealLanguage",
                color = RlColor.Ink,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 34.sp,
                    lineHeight = 38.sp,
                ),
            )
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.padding(top = 5.dp).width(38.dp).height(5.dp),
                contentScale = ContentScale.FillBounds,
            )
            Text(
                text = "A new step\ntoward real fluency.",
                modifier = Modifier.padding(start = 24.dp, top = 18.dp),
                color = RlColor.Ink,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Cursive,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                ),
            )
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.padding(start = 24.dp, top = 2.dp).width(88.dp).height(6.dp),
                contentScale = ContentScale.FillBounds,
            )
        }
        Image(
            painter = painterResource(R.drawable.rl_email_envelope_collage),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomCenter).offset(x = 36.dp, y = (-29).dp)
                .width(326.dp).height(292.dp),
            contentScale = ContentScale.Fit,
        )
        EmailVerificationTicket(
            state = state,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = if (fontScale > 1.35f || maxWidth > 500.dp) 0.dp else 79.dp, y = (-65).dp)
                .width(minOf(158.dp * fontScale, maxWidth * .70f)).height(118.dp * fontScale),
        )
    }
}

@Composable
private fun EmailVerificationTicket(
    state: EmailVerificationUiState,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.rl_email_status_ticket),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer(rotationZ = 3f),
            contentScale = ContentScale.FillBounds,
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 12.dp, top = 28.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = RlColor.Ink,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = state.status.ticketTitle(),
                    modifier = Modifier.padding(start = 8.dp),
                    color = if (state.status == EmailVerificationStatus.FAILED) {
                        RlColor.Error
                    } else {
                        RlColor.ActionVivid
                    },
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    ),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(RlColor.Border))
            Text(
                text = state.status.ticketDetail(state.resendCooldownSeconds),
                color = RlColor.TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 16.sp),
            )
        }
    }
}

@Composable
private fun EmailVerificationPaperSheet(
    state: EmailVerificationUiState,
    onAction: (EmailVerificationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.rl_torn_note),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleY = 1.12f),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            painter = painterResource(R.drawable.rl_email_paper_plane_path),
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopStart).offset(x = 10.dp, y = 66.dp)
                .width(90.dp).height(154.dp),
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier.align(Alignment.TopCenter).offset(x = (-18).dp)
                .padding(top = 47.dp)
                .fillMaxWidth(.58f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.rl_email_postal_header),
                contentDescription = null,
                modifier = Modifier.width(94.dp).height(38.dp),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = "验证你的邮箱",
                modifier = Modifier.padding(top = 4.dp),
                color = RlColor.Ink,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                ),
            )
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.padding(top = 4.dp).width(108.dp).height(7.dp),
                contentScale = ContentScale.FillBounds,
            )
            Text(
                text = state.email,
                modifier = Modifier.padding(top = 8.dp),
                color = RlColor.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                    fontSize = 20.sp,
                ),
            )
            Text(
                text = state.status.primaryMessage(state.resendCooldownSeconds),
                modifier = Modifier.padding(top = 5.dp).fillMaxWidth(),
                color = state.status.messageColor(),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            )
            Box(
                Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp)
                    .background(RlColor.Divider),
            )
            Row(
                modifier = Modifier.padding(top = 7.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "没有收到？你可以",
                    color = RlColor.TextSecondary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
                Text(
                    text = "重新发送",
                    color = RlColor.ActionVivid,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
                Text(
                    text = "，或",
                    color = RlColor.TextSecondary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
                Text(
                    text = "更换邮箱。",
                    color = RlColor.ActionVivid,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                )
            }
            state.submitError?.let { error ->
                Text(
                    text = error.message(),
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            EditorialRegistrationButton(
                text = stringResource(R.string.auth_resend),
                enabled = state.status != EmailVerificationStatus.RESEND_COOLDOWN &&
                    state.status != EmailVerificationStatus.VERIFIED &&
                    !state.resending,
                onClick = { onAction(EmailVerificationAction.ResendRequested) },
                modifier = Modifier.padding(top = 12.dp).width(220.dp).height(43.dp)
                    .testTag("email-verification-resend"),
            )
            TextButton(
                onClick = { onAction(EmailVerificationAction.ChangeEmailRequested) },
                enabled = !state.resending,
                modifier = Modifier.padding(top = 4.dp).height(34.dp)
                    .testTag("email-verification-change-email"),
            ) {
                Text(
                    text = stringResource(R.string.auth_change_email),
                    color = RlColor.Ink,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Image(
                painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                contentDescription = null,
                modifier = Modifier.width(60.dp).height(4.dp).graphicsLayer(rotationZ = -1f),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

private fun EmailVerificationStatus.ticketTitle(): String = when (this) {
    EmailVerificationStatus.WAITING -> "等待验证"
    EmailVerificationStatus.VERIFIED -> "验证完成"
    EmailVerificationStatus.FAILED -> "验证失败"
    EmailVerificationStatus.RESEND_COOLDOWN -> "等待重发"
}

private fun EmailVerificationStatus.ticketDetail(cooldownSeconds: Int?): String = when (this) {
    EmailVerificationStatus.WAITING -> "邮件已发送"
    EmailVerificationStatus.VERIFIED -> "邮箱已验证"
    EmailVerificationStatus.FAILED -> "请重新发送"
    EmailVerificationStatus.RESEND_COOLDOWN -> "${cooldownSeconds ?: 0} 秒后可重发"
}

private fun EmailVerificationStatus.primaryMessage(cooldownSeconds: Int?): String = when (this) {
    EmailVerificationStatus.WAITING -> "验证邮件已发送，请在邮件中完成验证。"
    EmailVerificationStatus.VERIFIED -> "邮箱已验证。"
    EmailVerificationStatus.FAILED -> "验证失败，请重新发送验证邮件。"
    EmailVerificationStatus.RESEND_COOLDOWN -> "${cooldownSeconds ?: 0} 秒后可重新发送"
}

internal enum class RegistrationArtwork { AGE, EMAIL, PASSWORD_RESET }

@Composable
internal fun RegistrationStatusPanel(
    modifier: Modifier,
    editorialTitle: String,
    artwork: RegistrationArtwork = RegistrationArtwork.EMAIL,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RlColor.Background.copy(alpha = 0f))
            .padding(horizontal = RlSpace.Space24, vertical = RlSpace.Space8),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 1480.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space20),
        ) {
            val leftWeight = if (artwork == RegistrationArtwork.PASSWORD_RESET) .88f else .72f
            val rightWeight = if (artwork == RegistrationArtwork.PASSWORD_RESET) 1.12f else 1.28f
            Box(Modifier.weight(leftWeight).fillMaxHeight()) {
                if (artwork == RegistrationArtwork.PASSWORD_RESET) {
                    Image(
                        painter = painterResource(R.drawable.rl_editorial_blue_panel),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().testTag("password-reset-visual-panel"),
                        contentScale = ContentScale.FillBounds,
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.rl_editorial_corner_collage),
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.68f),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxSize().padding(top = RlSpace.Space20),
                    verticalArrangement = Arrangement.Top,
                ) {
                    Text(
                        "RealLanguage",
                        color = if (artwork == RegistrationArtwork.PASSWORD_RESET) Color.White else RlColor.Ink,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = if (artwork == RegistrationArtwork.PASSWORD_RESET) 24.sp else 30.sp,
                        ),
                    )
                    if (artwork == RegistrationArtwork.PASSWORD_RESET) {
                        Text(
                            editorialTitle.substringBefore('\n'),
                            modifier = Modifier.padding(top = RlSpace.Space24),
                            color = Color.White,
                            style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp),
                        )
                        Text(
                            editorialTitle.substringAfter('\n'),
                            modifier = Modifier.padding(top = RlSpace.Space8),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Image(
                            painter = com.reallanguage.app.core.designsystem.editorialUnderlinePainter(),
                            contentDescription = null,
                            modifier = Modifier.padding(top = RlSpace.Space4).width(110.dp).height(5.dp),
                            contentScale = ContentScale.FillBounds,
                        )
                    } else {
                        Text(
                            editorialTitle,
                            modifier = Modifier.padding(top = RlSpace.Space32),
                            color = RlColor.TextPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                when (artwork) {
                    RegistrationArtwork.AGE -> Box(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(.78f)
                            .fillMaxHeight(.62f),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.rl_torn_note),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds,
                        )
                        Column(
                            modifier = Modifier.fillMaxSize().padding(RlSpace.Space24),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("ADMISSION GUIDE", color = RlColor.TextSecondary)
                            Text("适用年龄", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "14+",
                                color = RlColor.ActionVivid,
                                style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp),
                            )
                            Text("岁及以上", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    RegistrationArtwork.EMAIL -> Image(
                        painter = painterResource(R.drawable.rl_ledger_book_collage),
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .fillMaxHeight(.75f),
                        contentScale = ContentScale.Fit,
                    )
                    RegistrationArtwork.PASSWORD_RESET -> Image(
                        painter = painterResource(R.drawable.rl_ledger_book_collage),
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(.9f)
                            .fillMaxHeight(.64f),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            RlPaperPanel(
                modifier = Modifier.weight(rightWeight).fillMaxHeight(),
                contentPadding = RlSpace.Space24,
                contentSpacing = RlSpace.Space16,
                showSpine = false,
                contentModifier = Modifier.fillMaxSize(),
                contentArrangement = Arrangement.Center,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun EmailVerificationStatus.message(cooldownSeconds: Int?): String = when (this) {
    EmailVerificationStatus.WAITING -> stringResource(R.string.auth_email_verification_waiting)
    EmailVerificationStatus.VERIFIED -> stringResource(R.string.auth_email_verification_verified)
    EmailVerificationStatus.FAILED -> stringResource(R.string.auth_email_verification_failed)
    EmailVerificationStatus.RESEND_COOLDOWN ->
        stringResource(
            R.string.auth_email_verification_cooldown,
            requireNotNull(cooldownSeconds),
        )
}

private fun EmailVerificationStatus.messageColor() = when (this) {
    EmailVerificationStatus.FAILED -> RlColor.Error
    EmailVerificationStatus.VERIFIED -> RlColor.Secondary
    EmailVerificationStatus.WAITING,
    EmailVerificationStatus.RESEND_COOLDOWN,
    -> RlColor.TextSecondary
}
