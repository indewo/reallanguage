package com.reallanguage.app.feature.auth.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.billing.presentation.BillingAction
import com.reallanguage.app.feature.billing.presentation.BillingLabel

@Composable
fun AuthScreen(state: AuthUiState, onAction: (AuthAction) -> Unit, modifier: Modifier = Modifier) {
    if (state.mode == AuthMode.REGISTRATION) {
        RegistrationEditorialScreen(state, onAction, modifier)
        return
    }
    RlPaperBackground(modifier, showCornerCollage = false) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 720.dp && LocalDensity.current.fontScale <= 1.35f
            val sheetHeight = (maxHeight - 16.dp).coerceAtLeast(390.dp)
            if (wide) Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                AuthEditorialIntro(Modifier.weight(.32f).height(sheetHeight).testTag("registration-editorial-intro"))
                AuthPaperForm(state, onAction, Modifier.weight(.68f).heightIn(min = sheetHeight).testTag("registration-paper-sheet"), true)
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("RealLanguage", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp)
                Text("把你真正想说的话，变成你真正会用的英语。", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 15.sp)
                AuthPaperForm(state, onAction, Modifier.fillMaxWidth().testTag("registration-paper-sheet"), false)
            }
        }
    }
}

@Composable
private fun AuthPaperForm(state: AuthUiState, onAction: (AuthAction) -> Unit, modifier: Modifier, wide: Boolean) {
    val registration = state.mode == AuthMode.REGISTRATION
    Box(modifier) {
        Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        if (wide) {
            Image(painterResource(R.drawable.rl_editorial_top_right_collage), null, Modifier.align(Alignment.TopEnd).width(128.dp).height(56.dp), contentScale = ContentScale.Fit)
            Image(painterResource(R.drawable.rl_editorial_bottom_right_grid_botanical), null, Modifier.align(Alignment.BottomEnd).width(90.dp).height(86.dp), contentScale = ContentScale.Fit)
        }
        if (wide && registration) Box(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp, top = 108.dp).width(145.dp).height(110.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.rl_registration_age_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Text("＊ 出生年月\n只用于年龄确认", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 21.sp)
        }
        Column(Modifier.fillMaxWidth().padding(start = if (wide && registration) 58.dp else if (wide) 48.dp else 20.dp, end = if (wide && registration) 161.dp else if (wide) 108.dp else 20.dp, top = if (wide && registration) 24.dp else if (wide) 40.dp else 24.dp, bottom = if (wide && registration) 8.dp else 26.dp), verticalArrangement = Arrangement.spacedBy(if (wide && registration) 0.dp else 8.dp)) {
            if (registration) Box(Modifier.offset(x = if (wide) (-34).dp else 0.dp).widthIn(min = 134.dp).heightIn(min = 30.dp), contentAlignment = Alignment.CenterStart) {
                Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.widthIn(min = 44.dp).heightIn(min = 30.dp), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.rl_torn_terracotta), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                        Text("01", Modifier.padding(horizontal = 7.dp), color = Color.White, fontFamily = EditorialFont.Serif, fontSize = 22.sp, lineHeight = 30.sp)
                    }
                    Text(" · 注册", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 17.sp, lineHeight = 24.sp)
                }
            } else BillingLabel("登录", RlColor.ActionVivid)
            Column {
                Text(if (registration) "创建你的学习档案" else "欢迎回来", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 39.sp)
                Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(117.dp).height(7.dp), contentScale = ContentScale.FillBounds)
            }
            HorizontalDivider(Modifier.padding(bottom = if (wide) 5.dp else 0.dp), color = RlColor.Border)
            AuthInput(Icons.Outlined.Email, "邮箱", state.email, "请输入常用邮箱", !state.submitting, state.emailError?.message(), false, wide,
                Modifier.testTag("registration-email-row"), { onAction(AuthAction.EmailChanged(it)) }, minimumHeight = if (wide && !registration) 37.dp else 30.dp)
            AuthInput(Icons.Outlined.Lock, if (registration) "密码（至少 4 位）" else "密码", state.password, if (registration) "请输入密码（至少 4 位）" else "请输入密码", !state.submitting, state.passwordError?.message(), true, wide,
                Modifier.testTag("registration-password-row"), { onAction(AuthAction.PasswordChanged(it)) }, minimumHeight = if (wide && !registration) 37.dp else 30.dp)
            if (registration) {
                if (wide) Row(Modifier.fillMaxWidth().testTag("registration-birth-row"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(21.dp), tint = RlColor.Primary)
                    Text("出生年月", Modifier.width(104.dp), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 19.sp)
                    BirthYearMonthPicker(state.birthYearMonth, !state.submitting, state.birthYearMonthError != null, state.birthYearMonthError?.message(), { onAction(AuthAction.BirthYearMonthChanged(it)) }, Modifier.weight(1f).padding(end = 28.dp), editorial = true)
                } else {
                    Text("出生年月", fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp)
                    BirthYearMonthPicker(state.birthYearMonth, !state.submitting, state.birthYearMonthError != null, state.birthYearMonthError?.message(), { onAction(AuthAction.BirthYearMonthChanged(it)) }, Modifier.fillMaxWidth(), editorial = true)
                    Text("出生年月只用于年龄确认", color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp)
                }
                HorizontalDivider(color = RlColor.Border)
                Column(Modifier.testTag("registration-consent-block")) {
                    AuthAgreement(state.acceptedTerms, !state.submitting, stringResource(R.string.auth_terms), state.termsError, { onAction(AuthAction.TermsAcceptanceChanged(it)) }, { onAction(AuthAction.TermsRequested) })
                    AuthAgreement(state.acceptedPrivacyPolicy, !state.submitting, stringResource(R.string.auth_privacy_policy), state.privacyPolicyError, { onAction(AuthAction.PrivacyAcceptanceChanged(it)) }, { onAction(AuthAction.PrivacyPolicyRequested) })
                }
            } else {
                TextButton({ onAction(AuthAction.ForgotPasswordRequested) }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.auth_forgot_password), color = RlColor.Primary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif) }
                if (!wide) Spacer(Modifier.height(4.dp))
            }
            state.submitError?.let { Text(it.message(), color = RlColor.Error, fontSize = 13.sp) }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("registration-actions"), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                EditorialRegistrationButton(stringResource(if (registration) R.string.auth_submit_registration else R.string.auth_login), state.submitEnabled && !state.submitting, { onAction(AuthAction.SubmitRequested) }, Modifier.weight(1.5f).fillMaxHeight().testTag(if (registration) "registration-submit" else "login-submit"))
                if (registration) TextButton({ onAction(AuthAction.LoginRequested) }, Modifier.weight(1f).fillMaxHeight()) {
                    Text(stringResource(R.string.auth_return_to_login), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 16.sp)
                } else OutlinedButton({ onAction(AuthAction.RegistrationRequested) }, Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp).testTag("login-create-account"),
                    shape = RoundedCornerShape(4.dp), border = BorderStroke(.8.dp, RlColor.Ink)) {
                    Text("创建账号", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 24.sp)
                }
            }
        }
    }
}

@Composable
private fun AuthInput(icon: ImageVector, label: String, value: String, placeholder: String, enabled: Boolean, error: String?, password: Boolean, wide: Boolean, modifier: Modifier, onValueChange: (String) -> Unit, minimumHeight: Dp = 30.dp) {
    var revealed by remember { mutableStateOf(false) }
    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        RlKeyboardAwareBasicTextField(value, onValueChange, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Email),
            visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, color = RlColor.Ink, fontSize = 14.sp, lineHeight = 20.sp), cursorBrush = SolidColor(RlColor.Primary),
            modifier = fieldModifier.heightIn(min = maxOf(48.dp, minimumHeight)).border(.5.dp, if (error != null) RlColor.Error else RlColor.Border, RoundedCornerShape(2.dp)).padding(start = 10.dp, end = if (password) 0.dp else 10.dp),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, color = RlColor.TextMuted, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 20.sp)
                        inner()
                    }
                    if (password) IconButton({ revealed = !revealed }, Modifier.size(48.dp).testTag("auth-password-visibility")) { Icon(if (revealed) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, if (revealed) "隐藏密码" else "显示密码", Modifier.size(19.dp), tint = RlColor.Primary) }
                }
            })
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (wide) 0.dp else 5.dp)) {
        if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(21.dp), tint = RlColor.Primary)
            Text(label, Modifier.width(104.dp), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 12.sp, lineHeight = 19.sp, color = RlColor.Ink)
            field(Modifier.weight(1f))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, Modifier.size(20.dp), tint = RlColor.Primary); Text(label, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp) }
            field(Modifier.fillMaxWidth())
        }
        error?.let { Text(it, color = RlColor.Error, fontSize = 12.sp) }
        if (!wide) HorizontalDivider(color = RlColor.Border.copy(alpha = .7f))
    }
}

@Composable
private fun AuthAgreement(checked: Boolean, enabled: Boolean, label: String, error: AuthFieldError?, onCheckedChange: (Boolean) -> Unit, onDetails: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange).testTag("auth-consent-$label"), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 28.dp) { Checkbox(checked, null, Modifier.size(28.dp), enabled = enabled) }
            }
            Text(stringResource(R.string.auth_agreement_prefix), Modifier.weight(1f, fill = false), fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp)
            Box(Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onDetails).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Text(label, color = RlColor.ActionVivid, fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        error?.let { Text(it.message(), color = RlColor.Error, fontSize = 12.sp) }
    }
}

@Composable
internal fun EditorialRegistrationButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    BillingAction(text, onClick, modifier.heightIn(min = 44.dp), enabled = enabled)
}

/** Approved 02/03 have rounded, printed red actions, unlike the torn tag on 01. */
@Composable
internal fun EditorialRoundedAction(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val paper = ImageBitmap.imageResource(R.drawable.rl_torn_terracotta)
    val texture = remember(paper) {
        BitmapPainter(paper, srcOffset = IntOffset((paper.width * .05f).toInt(), (paper.height * .08f).toInt()),
            srcSize = IntSize((paper.width * .90f).toInt(), (paper.height * .84f).toInt()))
    }
    Box(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
        .background(if (enabled) RlColor.ActionVivid else RlColor.DisabledContainer)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Image(texture, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds, alpha = if (enabled) 1f else .24f)
        Text(text, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), textAlign = TextAlign.Center,
            color = if (enabled) Color.White else RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 23.sp)
    }
}

@Composable
private fun AuthEditorialIntro(modifier: Modifier) {
    BoxWithConstraints(modifier) {
        Image(painterResource(R.drawable.rl_v73_auth_collage), null, Modifier.align(Alignment.BottomStart).offset(x = (-18).dp).width(maxWidth + 54.dp).height(maxHeight * .68f), contentScale = ContentScale.FillBounds)
    Column(Modifier.fillMaxWidth().padding(start = 30.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("RealLanguage", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp)
        Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(34.dp).height(5.dp), contentScale = ContentScale.FillBounds)
        Text("把你真正想说的话，\n变成你真正会用的英语。", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 25.sp)
        Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null, Modifier.width(162.dp).height(7.dp), contentScale = ContentScale.FillBounds)
    }
    }
}

@Composable
private fun AuthFieldError.message(): String = stringResource(when (this) {
    AuthFieldError.INVALID_EMAIL -> R.string.auth_invalid_email
    AuthFieldError.PASSWORD_TOO_SHORT -> R.string.auth_password_too_short
    AuthFieldError.PASSWORD_TOO_LONG -> R.string.auth_password_too_long
    AuthFieldError.PASSWORD_BLOCKED -> R.string.auth_password_blocked
    AuthFieldError.INVALID_BIRTH_YEAR_MONTH -> R.string.auth_invalid_birth_year_month
    AuthFieldError.AGE_INELIGIBLE -> R.string.auth_age_ineligible
    AuthFieldError.TERMS_REQUIRED -> R.string.auth_terms_required
    AuthFieldError.PRIVACY_POLICY_REQUIRED -> R.string.auth_privacy_policy_required
    AuthFieldError.PASSWORD_REQUIRED -> R.string.auth_password_required
})
