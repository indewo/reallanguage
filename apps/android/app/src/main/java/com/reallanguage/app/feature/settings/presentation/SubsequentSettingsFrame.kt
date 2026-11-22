package com.reallanguage.app.feature.settings.presentation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.reallanguage.app.core.designsystem.*

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
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp)) {
            val wide = maxWidth >= 640.dp && LocalDensity.current.fontScale <= 1.35f
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EditorialPageHeader("设置", onBack)
                if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(.55f).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("subsequent-settings-sidebar")) { navigation() }
                    Column(Modifier.weight(2f).fillMaxHeight().settingsPaper().padding(12.dp).verticalScroll(scrollState).testTag("subsequent-settings-paper"), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
                } else {
                    navigation()
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(12.dp).testTag("subsequent-settings-paper"), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
                }
                footer()
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}