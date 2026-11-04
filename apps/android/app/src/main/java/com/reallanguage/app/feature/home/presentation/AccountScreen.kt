package com.reallanguage.app.feature.home.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AlternateEmail
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
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 700.dp && LocalDensity.current.fontScale <= 1.35f
            val bookHeight = (maxHeight - 42.dp).coerceAtLeast(300.dp)
            Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wide) Box(Modifier.fillMaxWidth()) {
                  Row(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AccountPassportArtwork(state.account?.email, Modifier.weight(.78f).height(bookHeight))
                    AccountLedgerPage(state, onAction, Modifier.weight(1.35f).heightIn(min = bookHeight))
                    Box(Modifier.width(48.dp).align(Alignment.CenterVertically).clickable(role = Role.Button) { onAction(AccountAction.ReturnHomeRequested) }.testTag("account-return-home"), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(31.dp), contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.rl_olive_torn_paper), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                            Text("返\n回\n首\n页", Modifier.padding(vertical = 14.dp), textAlign = TextAlign.Center, color = Color.White, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                  }
                  AccountPageHeading { onAction(AccountAction.ReturnHomeRequested) }
                } else {
                    AccountPageHeading { onAction(AccountAction.ReturnHomeRequested) }
                    AccountPassportArtwork(state.account?.email, Modifier.fillMaxWidth().height(315.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)))
                    AccountLedgerPage(state, onAction, Modifier.fillMaxWidth())
                    TextButton({ onAction(AccountAction.ReturnHomeRequested) }, Modifier.fillMaxWidth().testTag("account-return-home")) { Text("返回首页") }
                }
            }
        }
    }
}

@Composable
private fun AccountPageHeading(onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
        IconButton(onBack, Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink)
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(R.drawable.rl_v73_pattern_botanical_v2), null, Modifier.size(27.dp).graphicsLayer(scaleX = -1f), colorFilter = ColorFilter.tint(RlColor.TextSecondary))
            Text(stringResource(R.string.account_title), color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 34.sp)
                Image(painterResource(R.drawable.rl_v73_pattern_botanical_v2), null, Modifier.size(27.dp), colorFilter = ColorFilter.tint(RlColor.TextSecondary))
            }
            Image(editorialUnderlinePainter(), null, Modifier.width(78.dp).height(5.dp), contentScale = ContentScale.FillBounds)
        }
    }
}

@Composable
private fun AccountLedgerPage(state: AccountUiState, onAction: (AccountAction) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        Image(painterResource(R.drawable.rl_account_ledger_page), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxWidth().layout { measurable, constraints ->
            val content = measurable.measure(constraints.copy(minHeight = 0))
            // The stacked-sheet artwork has a ragged/shadowed lower 10%. Keep
            // the entire last action above it, including when large text grows.
            val paperHeight = ceil((content.height + 12.dp.toPx()) / .90f).toInt()
            layout(content.width, constraints.constrainHeight(paperHeight)) { content.placeRelative(0, 0) }
        }.padding(start = 30.dp, end = 58.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                state.loadFailed -> OutlinedButton({ onAction(AccountAction.RetryLoadRequested) }, Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.diary_retry)) }
                state.account != null -> {
                    BillingLabel("账号信息", RlColor.Primary)
                    Row(Modifier.fillMaxWidth().border(.5.dp, RlColor.Border).padding(horizontal = 12.dp, vertical = 8.dp).testTag("account-info-section"), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AlternateEmail, null, Modifier.size(18.dp), tint = RlColor.Ink)
                        Text("邮箱", Modifier.weight(.4f), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
                        Text(state.account.email, Modifier.weight(1f), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp, color = RlColor.Ink)
                    }
                }
            }
            BillingLabel("本机数据", RlColor.Secondary)
            AccountActionRow(Icons.Outlined.MicNone, stringResource(R.string.account_local_recordings), "查看与删除保存在本机的录音", Modifier.testTag("account-recordings")) { onAction(AccountAction.LocalRecordingsRequested) }
            BillingLabel("AI 服务", RlColor.NoteAccent)
            AccountActionRow(Icons.Outlined.CreditCard, "AI 费用与记录", "查看使用记录与余额", Modifier.testTag("account-billing")) { onAction(AccountAction.BillingRequested) }
            OutlinedButton({ onAction(AccountAction.LogoutRequested) }, Modifier.fillMaxWidth().heightIn(min = 42.dp).testTag("account-logout"), enabled = !state.loggingOut, shape = RlShape.Small, border = BorderStroke(.7.dp, RlColor.Ink)) {
                Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp), tint = RlColor.Ink)
                Text("  " + stringResource(if (state.loggingOut) R.string.account_logging_out else R.string.account_logout), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 16.sp, color = RlColor.Ink)
            }
            if (state.error == AccountError.LOGOUT_FAILED) Text(stringResource(R.string.account_logout_failed), color = RlColor.Error)
            HorizontalDivider(color = RlColor.Border)
            BillingLabel("账号与数据", RlColor.ActionVivid)
            AccountActionRow(Icons.Outlined.DeleteOutline, stringResource(R.string.account_delete), "将按流程确认并处理账号数据", Modifier.testTag("account-delete"), RlColor.Error) { onAction(AccountAction.DeleteAccountRequested) }
        }
    }
}

@Composable
private fun AccountPassportArtwork(email: String?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.testTag("account-passport-artwork"), contentAlignment = Alignment.Center) {
        val bookHeight = maxHeight
        val bookWidth = maxWidth
        Image(painterResource(R.drawable.rl_v73_account_passport), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxSize().padding(start = bookWidth * .16f, end = bookWidth * .1f, top = bookHeight * .075f, bottom = bookHeight * .09f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("RealLanguage", color = Color.White, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp)
            Text("PERSONAL PASSPORT", color = Color.White.copy(alpha = .8f), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 9.sp, lineHeight = 14.sp, letterSpacing = 1.sp)
            Image(painterResource(R.drawable.rl_v73_account_avatar), "账号默认头像插画", Modifier.padding(top = 8.dp).height(bookHeight * .32f).aspectRatio(.8f), contentScale = ContentScale.Fit)
            Text(email ?: "", Modifier.padding(top = 10.dp), color = Color.White, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        }
        // These two blank printed labels are part of the cover artwork. Anchor the
        // native text to their measured image regions, not to leftover Column space.
        Box(Modifier.align(Alignment.TopStart).offset(x = bookWidth * .27f, y = bookHeight * .65f)
            .width(bookWidth * .59f).height(bookHeight * .12f), contentAlignment = Alignment.Center) {
            Text("RealLanguage 学习账号", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        }
        Box(Modifier.align(Alignment.TopStart).offset(x = bookWidth * .385f, y = bookHeight * .794f)
            .width(bookWidth * .38f).height(bookHeight * .085f), contentAlignment = Alignment.Center) {
            Text("邮箱已验证", color = Color.White, fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AccountActionRow(icon: ImageVector, title: String, supporting: String, modifier: Modifier = Modifier, tint: Color = RlColor.Ink, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (tint == RlColor.Error) RlColor.ActionVivid.copy(alpha = .13f) else Color.Transparent).border(.5.dp, RlColor.Border).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(21.dp), tint = tint)
        BoxWithConstraints(Modifier.weight(1f)) {
            if (maxWidth >= 260.dp && LocalDensity.current.fontScale <= 1.35f) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, Modifier.weight(.45f), color = tint, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 15.sp, lineHeight = 20.sp)
                Text(supporting, Modifier.weight(.55f), color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp, lineHeight = 15.sp)
            } else Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = tint, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 15.sp, lineHeight = 20.sp)
                Text(supporting, color = RlColor.TextSecondary, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp), tint = tint)
    }
}
