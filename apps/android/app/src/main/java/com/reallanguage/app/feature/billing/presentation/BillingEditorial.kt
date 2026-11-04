package com.reallanguage.app.feature.billing.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground

/** Pages 45–48 use their own ledger composition, not the generic two-column learning sheet. */
@Composable
internal fun BillingEditorialPage(
    title: String,
    onBack: () -> Unit,
    wideContentTop: Dp = 62.dp,
    content: @Composable ColumnScope.(Boolean) -> Unit,
) {
    RlPaperBackground(showCornerCollage = false) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth >= 720.dp && maxHeight >= 350.dp && LocalDensity.current.fontScale <= 1.35f
            Image(painterResource(R.drawable.rl_v73_billing_ledger_background), null,
                Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            if (wide) {
                val left = maxWidth * .164f
                val right = maxWidth * .104f
                val contentScroll = rememberScrollState()
                val density = LocalDensity.current
                var headingHeight by remember { mutableStateOf(48.dp) }
                val scrollTopClip = with(density) {
                    if (contentScroll.value > 0) (38.dp + headingHeight - wideContentTop).toPx().coerceAtLeast(0f) else 0f
                }
                Column(Modifier.fillMaxSize().padding(start = left, end = right, top = wideContentTop, bottom = 12.dp)
                    // A real layer clip also excludes covered touch targets. Its measured
                    // top follows the heading's height instead of a guessed line count.
                    .clip(GenericShape { size, _ -> addRect(Rect(0f, scrollTopClip.coerceAtMost(size.height), size.width, size.height)) })
                    .testTag("ledger-scroll-content")
                    .verticalScroll(contentScroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                        fontSize = 13.sp, lineHeight = 18.sp)) { content(true) }
                }
                // The source ledger heading shares the upper composition with the balance
                // and receipt cards; it is not an additional full-width row above them.
                Box(Modifier.fillMaxWidth().padding(top = 38.dp).heightIn(min = 48.dp)
                    .onSizeChanged { headingHeight = with(density) { it.height.toDp() } }.testTag("ledger-fixed-heading")) {
                    IconButton(onBack, Modifier.align(Alignment.CenterStart).padding(start = left).testTag("ledger-back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink)
                    }
                    if (title.isNotEmpty()) Column(Modifier.align(Alignment.Center).widthIn(max = 230.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, color = RlColor.Ink, textAlign = TextAlign.Center,
                            fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                            fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 34.sp)
                        Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
                            Modifier.width(100.dp).height(6.dp), contentScale = ContentScale.FillBounds)
                    }
                }
                return@BoxWithConstraints
            }
            Column(
                Modifier.fillMaxSize().padding(
                    start = if (wide) maxWidth * .164f else 22.dp,
                    end = if (wide) maxWidth * .104f else 18.dp,
                    top = if (wide) 8.dp else 12.dp,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(if (wide) 6.dp else 16.dp),
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    IconButton(onBack, Modifier.align(Alignment.CenterStart).testTag("ledger-back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink)
                    }
                    if (title.isNotEmpty()) Column(Modifier.align(Alignment.Center).padding(horizontal = 50.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, color = RlColor.Ink, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
                                fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 34.sp))
                        Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
                            Modifier.width(100.dp).height(6.dp), contentScale = ContentScale.FillBounds)
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(if (wide) 6.dp else 16.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp)) { content(wide) }
                }
            }
        }
    }
}

@Composable
internal fun BillingPaper(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), spacing: androidx.compose.ui.unit.Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit) {
    Box(modifier) {
        Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxWidth().padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

@Composable
internal fun BillingLabel(text: String, color: Color = RlColor.Secondary) {
    Box(contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.rl_torn_terracotta), null, Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds, colorFilter = billingPaperTint(color))
        Text(text, Modifier.padding(horizontal = 15.dp, vertical = 3.dp), color = Color.White,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 14.sp, lineHeight = 18.sp))
    }
}

@Composable
internal fun BillingAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, color: Color = RlColor.ActionVivid, secondary: Boolean = false) {
    Box(modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Image(painterResource(if (secondary) R.drawable.rl_vocabulary_card_paper_r11 else R.drawable.rl_torn_terracotta),
            null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds,
            colorFilter = if (secondary || enabled && color == RlColor.ActionVivid) null else billingPaperTint(if (enabled) color else RlColor.DisabledContainer))
        Text(text, Modifier.padding(horizontal = 18.dp, vertical = 11.dp), textAlign = TextAlign.Center,
            color = if (!enabled) RlColor.TextSecondary else if (secondary) RlColor.Ink else Color.White,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 18.sp, lineHeight = 23.sp))
    }
}

private fun billingPaperTint(color: Color): ColorFilter {
    fun channel(value: Float) = floatArrayOf(.2126f * .8f * value, .7152f * .8f * value, .0722f * .8f * value, 0f, value * .6f * 255f)
    return ColorFilter.colorMatrix(ColorMatrix(channel(color.red) + channel(color.green) + channel(color.blue) + floatArrayOf(0f, 0f, 0f, 1f, 0f)))
}

@Composable
internal fun BillingAmount(label: String, amount: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = RlColor.TextSecondary, style = MaterialTheme.typography.bodySmall.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp))
        Text(amount, color = RlColor.ActionVivid,
            style = MaterialTheme.typography.displaySmall.copy(fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 36.sp, lineHeight = 43.sp))
    }
}
