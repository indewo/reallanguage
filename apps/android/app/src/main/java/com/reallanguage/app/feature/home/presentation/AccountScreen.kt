package com.reallanguage.app.feature.home.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.billing.presentation.BillingLabel
import kotlin.math.ceil

@Composable
fun AccountScreen(state: AccountUiState, onAction: (AccountAction) -> Unit) {
    BackHandler { onAction(AccountAction.ReturnHomeRequested) }
    RlPaperBackground(showCornerCollage = false) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
            val passportHeight = (maxHeight - 90.dp).coerceAtLeast(180.dp)
            val wide = maxWidth >= 640.dp && LocalDensity.current.fontScale <= 1.35f
            Column(Modifier.fillMaxSize()) {
                EditorialPageHeader("账号", { onAction(AccountAction.ReturnHomeRequested) })
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        AccountPassportArtwork(state.account?.email, Modifier.weight(.7f).height(passportHeight))
                        AccountLedgerPage(state, onAction, Modifier.weight(1.6f))
                    } else {
                        AccountPassportArtwork(state.account?.email, Modifier.fillMaxWidth().height(240.dp))
                        AccountLedgerPage(state, onAction, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
@Composable
private fun AccountLedgerPage(state: AccountUiState, onAction: (AccountAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.editorialPaper().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Surface(color = Color(0xFFE8DDB7), shape = RoundedCornerShape(20.dp)) {
            Text("账号信息", Modifier.padding(horizontal = 12.dp, vertical = 2.dp), color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
        }
        when {
            state.loading -> CircularProgressIndicator()
            state.loadFailed -> OutlinedButton({ onAction(AccountAction.RetryLoadRequested) }) { Text(stringResource(R.string.diary_retry)) }
            state.account != null -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("account-info-section"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Email, null, Modifier.size(20.dp), tint = RlColor.Ink)
                Column(Modifier.weight(1f)) {
                    Text("邮箱", fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 16.sp)
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(state.account.email, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("account-email-scroll"),
                            color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp,
                            maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        HorizontalDivider(color = RlColor.Border)
        AccountActionRow(Icons.Outlined.FormatListBulleted, "AI 费用与记录", "", Modifier.testTag("account-billing")) { onAction(AccountAction.BillingRequested) }
        AccountActionRow(Icons.Outlined.MicNone, stringResource(R.string.account_local_recordings), "", Modifier.testTag("account-recordings")) { onAction(AccountAction.LocalRecordingsRequested) }
        AccountActionRow(Icons.AutoMirrored.Outlined.Logout, stringResource(if (state.loggingOut) R.string.account_logging_out else R.string.account_logout), "", Modifier.testTag("account-logout"), enabled = !state.loggingOut) { onAction(AccountAction.LogoutRequested) }
        if (state.error == AccountError.LOGOUT_FAILED) Text(stringResource(R.string.account_logout_failed), color = RlColor.Error)
        AccountActionRow(Icons.Outlined.DeleteOutline, stringResource(R.string.account_delete), "", Modifier.testTag("account-delete"), RlColor.Error) { onAction(AccountAction.DeleteAccountRequested) }
    }
}
@Composable
private fun AccountPassportArtwork(email: String?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.testTag("account-passport-artwork"), contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, maxHeight * (1123f / 1401f))
        val height = width * (1401f / 1123f)
        val density = LocalDensity.current
        Box(Modifier.width(width).height(height)) {
            Image(painterResource(R.drawable.rl_account_passport_design_20261122), null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
            Box(Modifier.offset(x = width * .05f, y = height * .08f).width(width * .9f).height(height * .17f), contentAlignment = Alignment.Center) {
                Text("RealLanguage", color = Color.White, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold,
                    fontSize = with(density) { (width * .083f).toSp() }, lineHeight = with(density) { (width * .12f).toSp() })
            }
            Box(Modifier.offset(x = width * .14f, y = height * .62f).width(width * .73f).height(height * .11f), contentAlignment = Alignment.Center) {
                Text("RealLanguage 学习账号", color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                    fontSize = with(density) { (width * .055f).toSp() }, lineHeight = with(density) { (width * .08f).toSp() }, textAlign = TextAlign.Center)
            }
            // Measured green label bounds in the actual 1123 × 1401 transparent artwork.
            Box(Modifier.offset(x = width * (264f / 1123f), y = height * (1072f / 1401f))
                .width(width * (586f / 1123f)).height(height * (136f / 1401f)).testTag("account-verification-badge"), contentAlignment = Alignment.Center) {
                Text("邮箱已验证", color = Color.White, fontFamily = EditorialFont.Serif,
                    fontSize = with(density) { (width * .06f).toSp() },
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)))
            }
        }
    }
}
@Composable
private fun AccountActionRow(icon: ImageVector, title: String, supporting: String, modifier: Modifier = Modifier, tint: Color = RlColor.Ink, enabled: Boolean = true, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, fontFamily = EditorialFont.Serif, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            if (supporting.isNotBlank()) Text(supporting, color = RlColor.TextSecondary, fontFamily = EditorialFont.Serif, fontSize = 11.sp, lineHeight = 15.sp)
        }
        if (supporting.isNotBlank()) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), tint = tint)
    }
    HorizontalDivider(color = RlColor.Border.copy(alpha = .6f))
}
