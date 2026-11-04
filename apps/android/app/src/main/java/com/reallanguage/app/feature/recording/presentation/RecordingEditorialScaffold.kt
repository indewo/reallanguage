package com.reallanguage.app.feature.recording.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.RlPaperBackground
import com.reallanguage.app.feature.formation.presentation.EditorialHeader
import com.reallanguage.app.feature.formation.presentation.EditorialSheet

/** The recording family keeps its cover and paper independently measurable. */
@Composable
internal fun RecordingEditorialScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    paperBody: Boolean = true,
    pinActionsInStacked: Boolean = true,
    headerTag: String? = null,
    artwork: @Composable (Modifier) -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    RlPaperBackground {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 18.dp, vertical = 6.dp)) {
            val stacked = maxWidth < 640.dp || LocalDensity.current.fontScale > 1.35f
            val artworkHeight = 340.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EditorialHeader(title, modifier = headerTag?.let { Modifier.testTag(it) } ?: Modifier, onBack = onBack)
                if (stacked) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (paperBody) EditorialSheet(contentPadding = 22.dp, contentSpacing = 14.dp, showSpine = true, content = body)
                        else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), content = body)
                        artwork(Modifier.fillMaxWidth().height(artworkHeight))
                        if (!pinActionsInStacked) actions?.let {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = it)
                        }
                    }
                    if (pinActionsInStacked) actions?.let {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = it)
                    }
                } else Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    artwork(Modifier.weight(.86f).fillMaxHeight())
                    Column(Modifier.weight(1.14f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (paperBody) EditorialSheet(Modifier.fillMaxWidth().weight(1f), contentPadding = 24.dp, contentSpacing = 14.dp,
                            showSpine = true, contentModifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), content = body)
                        else Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp), content = body)
                        actions?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = it) }
                    }
                }
            }
        }
    }
}
