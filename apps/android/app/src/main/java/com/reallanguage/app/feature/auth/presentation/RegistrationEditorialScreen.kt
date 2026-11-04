package com.reallanguage.app.feature.auth.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*

/** Reference 01, measured as separate editorial, form and note modules.
 * The paper never determines content height; actual fields and 48dp targets do.
 * Registration is isolated so calibrating this page cannot move login or other forms.
 */
@Composable
internal fun RegistrationEditorialScreen(state: AuthUiState, onAction: (AuthAction) -> Unit, modifier: Modifier = Modifier) {
    RlPaperBackground(modifier, showCornerCollage = false) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            val wide = maxWidth >= 720.dp && LocalDensity.current.fontScale <= 1.35f
            val scale = (maxWidth.value / 1831f).coerceIn(.5f, 1.4f)
            if (wide) {
                val viewportHeight = maxHeight
                Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalAlignment = Alignment.Top) {
                    RegistrationIntro(scale, Modifier.weight(610f).height(viewportHeight.coerceAtLeast(859.dp * scale)).testTag("registration-editorial-intro"))
                    RegistrationForm(state, onAction, scale, true, viewportHeight < 500.dp, viewportHeight < 420.dp,
                        Modifier.weight(1221f).heightIn(min = viewportHeight).testTag("registration-paper-sheet"))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("RealLanguage", fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = RlColor.Ink)
                    Text("把你真正想说的话，变成你真正会用的英语。", fontFamily = EditorialFont.Serif, fontSize = 15.sp, color = RlColor.Ink)
                    RegistrationForm(state, onAction, .5f, false, false, false, Modifier.fillMaxWidth().testTag("registration-paper-sheet"))
                }
            }
        }
    }
}

@Composable
private fun RegistrationIntro(scale: Float, modifier: Modifier) {
    Box(modifier) {
        Image(registrationPlate(0f, 0f, 610f / 1831f, 1f), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxWidth().padding(start = 81.dp * scale, top = 50.dp * scale)) {
            Text("RealLanguage", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold,
                fontSize = (64 * scale).sp, lineHeight = (92 * scale).sp)
            Image(editorialUnderlinePainter(), null, Modifier.padding(top = 9.dp * scale).width(69.dp * scale).height(7.dp * scale), contentScale = ContentScale.FillBounds)
            Spacer(Modifier.height(18.dp * scale))
            Text("把你真正想说的话，\n变成你真正会用的英语。", color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                fontWeight = FontWeight.SemiBold, fontSize = (29 * scale).sp, lineHeight = (50 * scale).sp)
            Image(editorialUnderlinePainter(), null, Modifier.width(340.dp * scale).height(18.dp * scale), contentScale = ContentScale.FillBounds)
        }
    }
}

@Composable
private fun RegistrationForm(state: AuthUiState, onAction: (AuthAction) -> Unit, scale: Float, wide: Boolean, short: Boolean, constrained: Boolean, modifier: Modifier) {
    val s = scale
    val alignFieldsToReference = short && !constrained
    val tabScale = s * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(modifier) {
        if (wide) Image(registrationPlate(610f / 1831f, 0f, 1f, 1f), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        else Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        if (wide) Box(Modifier.align(Alignment.CenterEnd).padding(end = 75.dp * s, top = 232.dp * s).width(220.dp * s).rotate(10f)) {
            Text("＊ 出生年月\n   只用于年龄确认", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = (24 * s).sp, lineHeight = (37 * s).sp)
        }
        Column(Modifier.fillMaxWidth().padding(start = if (wide) 103.dp * s else 20.dp, end = if (wide) 338.dp * s else 20.dp,
            top = if (constrained) 12.dp else if (wide) 60.dp * s else 24.dp, bottom = if (short) 12.dp else if (wide) 70.dp * s else 24.dp)) {
            Box(Modifier.offset(x = if (wide) (-73).dp * s else 0.dp).width(270.dp * tabScale).heightIn(min = 66.dp * tabScale)) {
                Image(painterResource(R.drawable.rl_registration_tab_v74), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                Row(Modifier.padding(start = 28.dp * tabScale), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(92.dp * tabScale).heightIn(min = 61.dp * tabScale), contentAlignment = Alignment.Center) {
                        Text("01", color = Color.White, fontFamily = EditorialFont.Serif, fontSize = (38 * s).sp, lineHeight = (50 * s).sp)
                    }
                    Text(" · 注册", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = (31 * s).sp)
                }
            }
            Spacer(Modifier.height(if (constrained) 2.dp else 17.dp * s))
            Text("创建你的学习档案", Modifier.testTag("registration-heading"), color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                fontWeight = FontWeight.Bold, fontSize = (60 * s).sp, lineHeight = (78 * s).sp)
            Image(editorialUnderlinePainter(), null, Modifier.width(208.dp * s).height(13.dp * s), contentScale = ContentScale.FillBounds)
            if (wide && !constrained) Spacer(Modifier.height(4.dp * s))
            RegistrationRule()
            // On a short phone, decorative field borders sit lower INSIDE their
            // 48dp input targets. The targets themselves never overlap.
            Spacer(Modifier.height(if (short) 0.dp else 22.dp * s))
            RegistrationInput(Icons.Outlined.Email, "邮箱", state.email, "请输入常用邮箱", state.emailError?.registrationMessage(), false,
                state.submitting, wide, s, Modifier.testTag("registration-email-row"), if (alignFieldsToReference) 10.dp else 0.dp) { onAction(AuthAction.EmailChanged(it)) }
            RegistrationInput(Icons.Outlined.Lock, "密码（至少 4 位）", state.password, "请输入密码（至少 4 位）", state.passwordError?.registrationMessage(), true,
                state.submitting, wide, s, Modifier.testTag("registration-password-row"), if (alignFieldsToReference) 7.dp else 0.dp) { onAction(AuthAction.PasswordChanged(it)) }
            RegistrationRow(Icons.Outlined.CalendarMonth, "出生年月", wide, s, Modifier.testTag("registration-birth-row"), if (alignFieldsToReference) 4.dp else 0.dp) { fieldModifier ->
                BirthYearMonthPicker(state.birthYearMonth, !state.submitting, state.birthYearMonthError != null,
                    state.birthYearMonthError?.registrationMessage(), { onAction(AuthAction.BirthYearMonthChanged(it)) },
                    fieldModifier.padding(end = if (wide) 56.dp * s else 0.dp), editorial = true, referenceStyle = true, referenceScale = s,
                    referenceVisualOffset = if (alignFieldsToReference) 4.dp else 0.dp)
            }
            Column(Modifier.testTag("registration-consent-block")) {
                RegistrationAgreement(state.acceptedTerms, !state.submitting, stringResource(R.string.auth_terms), state.termsError?.registrationMessage(), s,
                    { onAction(AuthAction.TermsAcceptanceChanged(it)) }, { onAction(AuthAction.TermsRequested) }, if (alignFieldsToReference) (-1.5).dp else 0.dp,
                    if (alignFieldsToReference) (-11).dp else 0.dp)
                RegistrationAgreement(state.acceptedPrivacyPolicy, !state.submitting, stringResource(R.string.auth_privacy_policy), state.privacyPolicyError?.registrationMessage(), s,
                    { onAction(AuthAction.PrivacyAcceptanceChanged(it)) }, { onAction(AuthAction.PrivacyPolicyRequested) }, if (alignFieldsToReference) (-15).dp else 0.dp,
                    if (alignFieldsToReference) (-32).dp else 0.dp)
            }
            state.submitError?.let { Text(it.message(), color = RlColor.Error, fontSize = 14.sp) }
            Spacer(Modifier.height(if (short) 0.dp else 22.dp * s))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("registration-actions")) {
                RegistrationSubmit(state.submitEnabled && !state.submitting, { onAction(AuthAction.SubmitRequested) }, s,
                    Modifier.weight(if (wide) 550f else 1.5f).fillMaxHeight().testTag("registration-submit"))
                Spacer(Modifier.width(if (wide) 22.dp * s else 12.dp))
                VerticalDivider(Modifier.fillMaxHeight(), color = RlColor.Border, thickness = .5.dp)
                Spacer(Modifier.width(if (wide) 62.dp * s else 12.dp))
                Box(Modifier.weight(if (wide) 144f else 1f).heightIn(min = 48.dp).fillMaxHeight().clickable(role = Role.Button) { onAction(AuthAction.LoginRequested) }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.auth_return_to_login), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = (30 * s).sp, lineHeight = (40 * s).sp)
                        Image(editorialUnderlinePainter(), null, Modifier.width(132.dp * s).height(7.dp * s), contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(RlColor.Ink))
                    }
                }
            }
        }
    }
}

@Composable
private fun RegistrationSubmit(enabled: Boolean, onClick: () -> Unit, scale: Float, modifier: Modifier) {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_registration_submit_v74)
    val painter = remember(bitmap) { BitmapPainter(bitmap, IntOffset(70, 145), IntSize(1971, 395)) }
    Box(modifier.heightIn(min = maxOf(48.dp, 88.dp * scale)).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Image(painter, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds,
            alpha = if (enabled) 1f else .4f,
            colorFilter = if (enabled) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }))
        Text(stringResource(R.string.auth_submit_registration), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontFamily = EditorialFont.Serif, fontSize = (36 * scale).coerceAtLeast(16f).sp, lineHeight = (46 * scale).coerceAtLeast(23f).sp,
            color = if (enabled) Color.White else RlColor.TextSecondary)
    }
}

@Composable
private fun RegistrationRow(icon: ImageVector, label: String, wide: Boolean, scale: Float, modifier: Modifier = Modifier, visualOffset: Dp = 0.dp, field: @Composable (Modifier) -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (wide) Row(Modifier.fillMaxWidth().heightIn(min = 91.dp * scale), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.offset(y = visualOffset).size(40.dp * scale), tint = RlColor.Primary)
            Spacer(Modifier.width(30.dp * scale))
            Text(label, Modifier.offset(y = visualOffset).width(187.dp * scale), color = RlColor.Ink, fontWeight = FontWeight.Medium,
                fontSize = (22 * scale).sp, lineHeight = (32 * scale).sp)
            VerticalDivider(Modifier.offset(y = visualOffset).height(51.dp * scale), color = RlColor.TextMuted, thickness = .5.dp)
            Spacer(Modifier.width(26.dp * scale))
            field(Modifier.weight(1f))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(22.dp), tint = RlColor.Primary)
                Text(label, Modifier.padding(start = 8.dp, bottom = 4.dp), fontFamily = EditorialFont.Serif, color = RlColor.Ink, fontSize = 14.sp)
            }
            field(Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        RegistrationRule(Modifier.offset(y = if (visualOffset > 0.dp) visualOffset - 1.5.dp else 0.dp))
    }
}

@Composable
private fun RegistrationInput(icon: ImageVector, label: String, value: String, hint: String, error: String?, password: Boolean,
                              submitting: Boolean, wide: Boolean, scale: Float, modifier: Modifier, visualOffset: Dp = 0.dp, onValueChange: (String) -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    RegistrationRow(icon, label, wide, scale, modifier, visualOffset) { fieldModifier ->
        Column(fieldModifier) {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                RlKeyboardAwareBasicTextField(value, onValueChange, enabled = !submitting, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Email),
                    visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = EditorialFont.Serif, color = RlColor.Ink,
                        fontSize = (26 * scale).coerceAtLeast(14f).sp, lineHeight = (36 * scale).coerceAtLeast(22f).sp),
                    cursorBrush = SolidColor(RlColor.Primary), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            Box(Modifier.offset(y = visualOffset).fillMaxWidth().heightIn(min = 58.dp * scale).border(.5.dp, if (error == null) RlColor.BorderStrong.copy(alpha = .65f) else RlColor.Error, RoundedCornerShape(2.dp)))
                            Row(Modifier.padding(start = 18.dp * scale, end = if (password) 0.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).offset(y = visualOffset)) {
                                    if (value.isEmpty()) Text(hint, color = RlColor.TextMuted, fontFamily = EditorialFont.Serif,
                                        fontSize = (26 * scale).coerceAtLeast(14f).sp, lineHeight = (36 * scale).coerceAtLeast(22f).sp)
                                    inner()
                                }
                                if (password) IconButton({ revealed = !revealed }, Modifier.size(48.dp).testTag("auth-password-visibility"), enabled = !submitting) {
                                    Icon(if (revealed) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                        if (revealed) "隐藏密码" else "显示密码", Modifier.offset(y = visualOffset).size(28.dp * scale), tint = RlColor.Primary)
                                }
                            }
                        }
                    })
            }
            error?.let { Text(it, color = RlColor.Error, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun RegistrationAgreement(checked: Boolean, enabled: Boolean, label: String, error: String?, scale: Float, onChecked: (Boolean) -> Unit, onDetails: () -> Unit, visualOffset: Dp = 0.dp, dividerOffset: Dp = 0.dp) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = maxOf(48.dp, 55.dp * scale)), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChecked).testTag("auth-consent-$label"), contentAlignment = Alignment.CenterStart) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
                    Checkbox(checked, null, Modifier.offset(y = visualOffset).size(maxOf(24.dp, 30.dp * scale)).graphicsLayer {
                        scaleX = .75f; scaleY = .75f
                    }, enabled = enabled)
                }
            }
            Row(Modifier.weight(1f).offset(x = (-17).dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.auth_agreement_prefix), Modifier.weight(1f, fill = false).offset(y = visualOffset), color = RlColor.Ink,
                fontFamily = EditorialFont.Serif, fontSize = (24 * scale).coerceAtLeast(12f).sp, lineHeight = (32 * scale).coerceAtLeast(18f).sp)
            Box(Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onDetails), contentAlignment = Alignment.Center) {
                Row(Modifier.offset(y = visualOffset)) {
                    listOf("《", label, "》").forEach { part ->
                        Text(part, color = RlColor.ActionVivid, fontFamily = EditorialFont.Serif, fontSize = (24 * scale).coerceAtLeast(12f).sp, lineHeight = (32 * scale).coerceAtLeast(18f).sp)
                    }
                }
            }
            }
        }
        error?.let { Text(it, color = RlColor.Error, fontSize = 14.sp) }
        RegistrationRule(Modifier.offset(y = dividerOffset))
    }
}

/** Native UI separator, independent of the invisible input/touch bounds. */
@Composable
private fun RegistrationRule(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(.5.dp)) {
        drawLine(RlColor.BorderStrong.copy(alpha = .55f), Offset.Zero, Offset(size.width, 0f),
            strokeWidth = .5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 2.dp.toPx())))
    }
}

/** Crops decorative-only generated material; UI text is never baked into a bitmap. */
@Composable
private fun registrationPlate(left: Float, top: Float, right: Float, bottom: Float): BitmapPainter {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_registration_background_v74)
    return remember(bitmap, left, top, right, bottom) {
        BitmapPainter(bitmap, IntOffset((bitmap.width * left).toInt(), (bitmap.height * top).toInt()),
            IntSize((bitmap.width * (right - left)).toInt(), (bitmap.height * (bottom - top)).toInt()))
    }
}

@Composable
private fun AuthFieldError.registrationMessage(): String = stringResource(when (this) {
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
