package com.reallanguage.app.feature.scene.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.feature.formation.presentation.EditorialHeader
import com.reallanguage.app.feature.formation.presentation.EditorialSheet

@Composable
internal fun SceneEditorialFrame(
    title: String,
    supporting: String,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 6.dp)) {
        val stacked = maxWidth < 640.dp || LocalDensity.current.fontScale > 1.4f
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EditorialHeader(title = title, modifier = Modifier.testTag("scene-questions-header"))
            if (stacked) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    EditorialSheet(contentPadding = 24.dp, showSpine = true, contentSpacing = 16.dp, content = content)
                    SceneEditorialArtwork(supporting, Modifier.fillMaxWidth().height(230.dp))
                }
                footer?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = it) }
            } else Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                SceneEditorialArtwork(supporting, Modifier.weight(.7f).fillMaxHeight())
                Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorialSheet(Modifier.fillMaxWidth().weight(1f), contentPadding = 18.dp,
                        showSpine = true, contentSpacing = 10.dp,
                        contentModifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), content = content)
                    footer?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = it) }
                }
            }
        }
    }
}

@Composable
internal fun SceneEditorialArtwork(supporting: String, modifier: Modifier = Modifier) {
    Box(modifier.testTag("scene-questions-artwork")) {
        Image(painterResource(R.drawable.rl_v73_scene_context), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Text(supporting, Modifier.align(Alignment.TopCenter).padding(horizontal = 32.dp, vertical = 46.dp),
            color = RlColor.OnPrimary.copy(alpha = .92f), fontFamily = com.reallanguage.app.core.designsystem.EditorialFont.Serif,
            fontSize = 24.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}
