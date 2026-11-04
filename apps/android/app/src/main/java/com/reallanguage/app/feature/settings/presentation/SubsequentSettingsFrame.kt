package com.reallanguage.app.feature.settings.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.core.designsystem.RlPaperPanel

/** Page 53 geometry only. Existing materials remain unchanged and are not exact source assets. */
@Composable
internal fun SubsequentSettingsFrame(
    modifier: Modifier = Modifier,
    scrollState: ScrollState,
    onBack: () -> Unit,
    navigation: @Composable () -> Unit,
    note: @Composable (Modifier) -> Unit,
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    RlPaperBackground(modifier, showCornerCollage = false) {
        var consumedInsets by remember { mutableStateOf<WindowInsets>(WindowInsets(0, 0, 0, 0)) }
        BoxWithConstraints(Modifier.fillMaxSize().onConsumedWindowInsetsChanged { consumedInsets = it }) {
            val canvasHeight = maxHeight
            val safe = WindowInsets.safeDrawing.union(WindowInsets.ime).exclude(consumedInsets).asPaddingValues()
            val safeLeft = safe.calculateLeftPadding(LocalLayoutDirection.current)
            val safeRight = safe.calculateRightPadding(LocalLayoutDirection.current)
            val fontScale = LocalDensity.current.fontScale
            if (maxWidth < 720.dp || maxHeight < 350.dp || fontScale > 1.35f) {
                Column(Modifier.fillMaxSize().padding(safe).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink) }
                        Text("学习设置", color = RlColor.Ink, fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 25.sp, lineHeight = 34.sp)
                    }
                    navigation()
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
                    footer()
                }
                return@BoxWithConstraints
            }
            val headerTop = maxOf(14.dp, safe.calculateTopPadding() + 8.dp)
            val sidebarWidth = maxWidth * (408f / 1831f)
            val paperTop = maxOf(maxHeight * (154f / 859f), headerTop + 63.dp * fontScale)
            val paperLeft = maxWidth * (432f / 1831f)
            val paperRight = maxOf(maxWidth * (91f / 1831f), safeRight + 8.dp)
            val paperBottom = maxOf(maxHeight * (21f / 859f), safe.calculateBottomPadding() + 8.dp)
            val paperHeight = (maxHeight - paperTop - paperBottom).coerceAtLeast(1.dp)
            val noteWidth = (maxWidth * (276f / 1831f)).coerceAtLeast(138.dp)
            val noteHeight = maxOf(maxHeight * (166f / 859f), 57.dp * fontScale + 12.dp)

            // Decoration can extend behind the safe area. All actual controls remain below it.
            Box(Modifier.width(sidebarWidth).fillMaxHeight().clipToBounds().testTag("subsequent-settings-sidebar")) {
                Image(painterResource(R.drawable.rl_editorial_blue_panel), null,
                    Modifier.fillMaxWidth().height(canvasHeight * (684f / 859f)), contentScale = ContentScale.FillBounds)
            }
            Box(Modifier.offset(x = maxOf(maxWidth * (56f / 1831f), safeLeft + 4.dp), y = maxOf(14.dp, safe.calculateTopPadding() + 4.dp))
                .size(54.dp, 48.dp)) {
                Image(painterResource(R.drawable.rl_torn_note), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                IconButton(onBack, Modifier.fillMaxSize().testTag("ledger-back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = RlColor.Ink)
                }
            }
            val navigationTop = maxOf(maxHeight * (140f / 859f), safe.calculateTopPadding() + 54.dp)
            val navigationLeft = maxOf(maxWidth * (40f / 1831f), safeLeft + 8.dp)
            Box(Modifier.offset(x = navigationLeft, y = navigationTop)
                .width(minOf(maxWidth * (330f / 1831f), paperLeft - navigationLeft - 8.dp))
                .height((maxHeight - navigationTop - safe.calculateBottomPadding() - 12.dp).coerceAtLeast(1.dp))
                .verticalScroll(rememberScrollState())) { navigation() }

            Column(Modifier.offset(x = maxWidth * .53f - 140.dp, y = headerTop).width(280.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("学习设置", color = RlColor.Ink, style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold))
                Image(com.reallanguage.app.core.designsystem.editorialUnderlinePainter(), null,
                    Modifier.width(70.dp).height(5.dp), contentScale = ContentScale.FillBounds)
                Text("调整之后的学习方式", Modifier.padding(top = 2.dp), color = RlColor.TextSecondary,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp))
            }

            Box(Modifier.offset(x = paperLeft, y = paperTop)
                .width(maxWidth - paperLeft - paperRight).height(paperHeight)
                .testTag("subsequent-settings-paper")) {
                // Paint the immutable paper independently; neither its texture nor footer is
                // measured as the height of the long form. Do not add generic process artwork.
                Image(painterResource(R.drawable.rl_vocabulary_card_paper_r11), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Column(Modifier.fillMaxSize().padding(start = 30.dp, end = 46.dp, top = 26.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
                    footer()
                }
            }
            note(Modifier.offset(x = (maxWidth * (1396f / 1831f)).coerceAtMost(maxWidth - noteWidth - safeRight - 8.dp),
                y = maxOf(10.dp, safe.calculateTopPadding() + 3.dp)).width(noteWidth).height(noteHeight))
        }
    }
}
