package com.reallanguage.app.feature.auth.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPrimaryButton
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.core.designsystem.RlSpace
import com.reallanguage.app.core.designsystem.RlTextField
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.feature.billing.presentation.BillingAction

@Composable
fun PasswordResetScreen(
    state: PasswordResetUiState,
    onAction: (PasswordResetAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onAction(PasswordResetAction.ReturnToLoginRequested) }
    AuthResponsivePage(modifier = modifier, leftFraction = .40f, illustration = { artModifier ->
        BoxWithConstraints(artModifier.testTag("password-reset-visual-panel")) {
            val artHeight = maxHeight
            Image(painterResource(R.drawable.rl_v73_password_reset_collage), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = artHeight * .12f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("找回访问权", color = RlColor.PaperWarm, fontFamily = EditorialFont.Serif, fontSize = 28.sp, lineHeight = 40.sp)
                Text("安全地重新设置", color = RlColor.PaperWarm, fontFamily = EditorialFont.Serif, fontSize = 18.sp, lineHeight = 28.sp)
            }
        }
    }) { wide ->
        if (state.step == PasswordResetStep.WAITING_VERIFICATION) {
            VerificationStep(state, onAction, wide)
            state.error?.let {
                Text(errorMessage(it), color = MaterialTheme.colorScheme.error)
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(top = if (wide) 18.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(if (state.step == PasswordResetStep.SUCCESS) "已完成" else if (state.step == PasswordResetStep.EMAIL_CONFIRMATION) "第 1 步 / 共 3 步" else "第 3 步 / 共 3 步",
                        color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 22.sp)
                    PasswordResetProgress(if (state.step == PasswordResetStep.EMAIL_CONFIRMATION) 1 else 3, Modifier.weight(1f), complete = state.step == PasswordResetStep.SUCCESS)
                }
                Column {
                    Text(stringResource(R.string.password_reset_title), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = if (wide) 36.sp else 30.sp, lineHeight = if (wide) 46.sp else 39.sp)
                    Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(122.dp).height(6.dp), contentScale = ContentScale.FillBounds)
                }
                Spacer(Modifier.height(if (wide) 4.dp else 0.dp))
                Column(Modifier.fillMaxWidth(if (wide) .84f else 1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (state.step) {
                    PasswordResetStep.EMAIL_CONFIRMATION -> EmailConfirmationStep(state, onAction)
                    PasswordResetStep.WAITING_VERIFICATION -> Unit
                    PasswordResetStep.NEW_PASSWORD -> NewPasswordStep(state, onAction)
                    PasswordResetStep.SUCCESS -> SuccessStep(state, onAction)
                }
                state.error?.let {
                    Text(errorMessage(it), color = MaterialTheme.colorScheme.error)
                }
                if (state.step != PasswordResetStep.SUCCESS) {
                    TextButton(
                        onClick = { onAction(PasswordResetAction.ReturnToLoginRequested) },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("password-reset-return-login"),
                    ) {
                        Text(stringResource(R.string.auth_return_to_login))
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun EmailConfirmationStep(state: PasswordResetUiState, onAction: (PasswordResetAction) -> Unit) {
    Text(stringResource(R.string.password_reset_email_instruction), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 24.sp)
    RlTextField(
        value = state.email,
        label = stringResource(R.string.auth_email),
        onValueChange = { onAction(PasswordResetAction.EmailChanged(it)) },
        enabled = !state.operationInProgress,
        isError = state.error == PasswordResetError.INVALID_EMAIL,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.testTag("password-reset-email-input"),
    )
    PasswordResetResendAction(
        text = stringResource(R.string.password_reset_send),
        enabled = !state.operationInProgress,
        onClick = { onAction(PasswordResetAction.SendRequested) },
        modifier = Modifier.widthIn(max = 278.dp).fillMaxWidth().testTag("password-reset-send-email"),
    )
}

@Composable
private fun VerificationStep(state: PasswordResetUiState, onAction: (PasswordResetAction) -> Unit, wide: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = if (wide) 18.dp else 0.dp).testTag("password-reset-waiting"),
        verticalArrangement = Arrangement.spacedBy(RlSpace.Space4),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RlSpace.Space24),
        ) {
            Text(
                text = "第 2 步 / 共 3 步",
                color = RlColor.TextPrimary,
                fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 22.sp,
            )
            PasswordResetProgress(
                currentStep = 2,
                modifier = Modifier.weight(1f).testTag("password-reset-progress"),
            )
        }
        Text(
            text = stringResource(R.string.password_reset_title),
            color = RlColor.Ink,
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = EditorialFont.Serif, fontSize = if (wide) 36.sp else 30.sp, lineHeight = if (wide) 46.sp else 39.sp),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val roomy = maxWidth >= 340.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.35f
            Column(
                modifier = Modifier.fillMaxWidth(if (roomy) .73f else 1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "验证你的邮箱",
                    color = RlColor.TextPrimary,
                    fontFamily = EditorialFont.Serif, fontSize = 20.sp, lineHeight = 28.sp,
                )
                Text(
                    text = state.email,
                    color = RlColor.Ink,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                        fontSize = 18.sp, lineHeight = 24.sp,
                    ),
                )
                HorizontalDivider(color = RlColor.Border.copy(alpha = .7f))
                Text(
                    text = "重置链接已发送，请在邮件中完成验证。",
                    color = RlColor.TextPrimary,
                    fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 22.sp,
                )
                if (!roomy) Text("链接 30 分钟内有效", color = RlColor.TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                PasswordResetResendAction(
                    text = "重新发送",
                    enabled = !state.operationInProgress,
                    onClick = { onAction(PasswordResetAction.SendRequested) },
                    modifier = Modifier.widthIn(max = if (wide) 278.dp else 330.dp).fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("password-reset-resend"),
                )
                TextButton(
                    onClick = { onAction(PasswordResetAction.ChangeEmailRequested) },
                    enabled = !state.operationInProgress,
                    modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 34.dp)
                        .testTag("password-reset-change-email"),
                ) {
                    Text("更换邮箱", color = RlColor.Ink)
                }
                TextButton(
                    onClick = { onAction(PasswordResetAction.ReturnToLoginRequested) },
                    modifier = Modifier.heightIn(min = 34.dp).testTag("password-reset-return-login"),
                ) {
                    Text(stringResource(R.string.auth_return_to_login), color = RlColor.Ink)
                }
            }
            if (roomy) Box(
                modifier = Modifier.align(Alignment.TopEnd).width(112.dp).height(72.dp)
                    .graphicsLayer(rotationZ = 4f)
                    .testTag("password-reset-link-expiry-note"),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.rl_registration_age_note),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Text(
                    text = "链接\n30 分钟内有效",
                    color = RlColor.Ink,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 20.sp),
                )
            }
            if (roomy) Image(
                painter = painterResource(R.drawable.rl_email_postal_header),
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 146.dp).width(118.dp).height(54.dp)
                    .testTag("password-reset-postal-mark"),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** The reset reference uses a rounded olive printed action, not a torn red tag. */
@Composable
private fun PasswordResetResendAction(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val paper = ImageBitmap.imageResource(R.drawable.rl_olive_torn_paper)
    val texture = remember(paper) {
        BitmapPainter(paper, srcOffset = IntOffset((paper.width * .20f).toInt(), (paper.height * .25f).toInt()),
            srcSize = IntSize((paper.width * .60f).toInt(), (paper.height * .50f).toInt()))
    }
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(4.dp))
        .background(if (enabled) RlColor.Secondary else RlColor.DisabledContainer)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Image(texture, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds, alpha = if (enabled) 1f else .24f)
        Text(text, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), textAlign = TextAlign.Center,
            color = if (enabled) Color.White else RlColor.TextSecondary,
            fontFamily = EditorialFont.Serif, fontSize = 18.sp, lineHeight = 25.sp)
    }
}

@Composable
private fun PasswordResetProgress(
    currentStep: Int,
    modifier: Modifier = Modifier,
    complete: Boolean = false,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val selected = complete || index + 1 == currentStep
            val selectedColor = if (complete) RlColor.Secondary else RlColor.ActionVivid
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .border(1.dp, if (selected) selectedColor else RlColor.TextSecondary, CircleShape)
                    .background(if (selected) selectedColor else RlColor.PaperWarm),
            )
            if (index < 2) {
                Box(
                    modifier = Modifier.weight(1f).height(1.dp)
                        .background(RlColor.TextSecondary.copy(alpha = .55f)),
                )
            }
        }
    }
}

@Composable
private fun NewPasswordStep(state: PasswordResetUiState, onAction: (PasswordResetAction) -> Unit) {
    Text(stringResource(R.string.password_reset_new_password_instruction), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 24.sp)
    RlTextField(
        value = state.newPassword,
        label = stringResource(R.string.password_reset_new_password),
        onValueChange = { onAction(PasswordResetAction.PasswordChanged(it)) },
        enabled = !state.operationInProgress,
        isError = state.error in setOf(
            PasswordResetError.PASSWORD_TOO_SHORT,
            PasswordResetError.PASSWORD_TOO_LONG,
            PasswordResetError.PASSWORD_BLOCKED,
        ),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.testTag("password-reset-new-password-input"),
    )
    PasswordResetResendAction(
        text = stringResource(R.string.password_reset_submit),
        enabled = !state.operationInProgress,
        onClick = { onAction(PasswordResetAction.ResetRequested) },
        modifier = Modifier.widthIn(max = 278.dp).fillMaxWidth().testTag("password-reset-submit"),
    )
}

@Composable
private fun SuccessStep(state: PasswordResetUiState, onAction: (PasswordResetAction) -> Unit) {
    Column(Modifier.widthIn(max = 290.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(85.dp), tint = RlColor.Secondary)
    Text(stringResource(R.string.password_reset_success), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 25.sp, lineHeight = 36.sp, textAlign = TextAlign.Center)
    PasswordResetResendAction(
        text = stringResource(
            if (state.completionDestination == PasswordResetCompletionDestination.LOGIN) {
                R.string.auth_return_to_login
            } else {
                R.string.password_reset_continue
            },
        ),
        enabled = true,
        onClick = { onAction(PasswordResetAction.CompleteRequested) },
        modifier = Modifier.fillMaxWidth().testTag("password-reset-complete"),
    )
    }
}

@Composable
private fun stepLabel(step: PasswordResetStep): String = stringResource(when (step) {
    PasswordResetStep.EMAIL_CONFIRMATION -> R.string.password_reset_step_email
    PasswordResetStep.WAITING_VERIFICATION -> R.string.password_reset_step_verification
    PasswordResetStep.NEW_PASSWORD -> R.string.password_reset_step_password
    PasswordResetStep.SUCCESS -> R.string.password_reset_step_success
})

@Composable
private fun errorMessage(error: PasswordResetError): String = stringResource(when (error) {
    PasswordResetError.INVALID_EMAIL -> R.string.auth_invalid_email
    PasswordResetError.PASSWORD_TOO_SHORT -> R.string.auth_password_too_short
    PasswordResetError.PASSWORD_TOO_LONG -> R.string.auth_password_too_long
    PasswordResetError.PASSWORD_BLOCKED -> R.string.auth_password_blocked
    PasswordResetError.INVALID_LINK -> R.string.password_reset_invalid_link
    PasswordResetError.NETWORK -> R.string.auth_network_failure
    PasswordResetError.SERVICE -> R.string.auth_service_failure
})
