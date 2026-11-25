package com.reallanguage.app.feature.scene.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlShape
import com.reallanguage.app.feature.formation.presentation.EditorialSheet

@Composable
internal fun SceneSummaryPoster(topic: String, type: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val posterHeight = maxHeight
        val captionWidth = maxWidth * .72f
        val captionHeight = posterHeight * .33f
        val density = LocalDensity.current
        val titleStyle = TextStyle(
            color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold,
            fontSize = 16.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
        val typeStyle = TextStyle(
            color = RlColor.OnPrimary, fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 15.sp,
        )
        val measurer = rememberTextMeasurer()
        val titleLayout = measurer.measure(topic, titleStyle,
            constraints = Constraints(maxWidth = with(density) { captionWidth.roundToPx() }.coerceAtLeast(1)))
        val typeHeight = with(density) { measurer.measure(type, typeStyle).size.height.toDp() }

        if (density.fontScale > 1.35f || titleLayout.lineCount > 2 ||
            with(density) { titleLayout.size.height.toDp() } + typeHeight + 12.dp > captionHeight
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(painterResource(R.drawable.rl_v73_scene_type_dialogue), null,
                    Modifier.fillMaxWidth().height(minOf(230.dp, posterHeight * .52f)), contentScale = ContentScale.Fit)
                EditorialSheet(contentPadding = 12.dp) {
                    ScenePosterCaption(topic, type, titleStyle, typeStyle, Modifier.fillMaxWidth())
                }
            }
        } else {
            Image(painterResource(R.drawable.rl_scene_review_portrait_poster), null,
                Modifier.matchParentSize().testTag("scene-review-portrait-poster"), contentScale = ContentScale.FillBounds)
            ScenePosterCaption(
                topic, type, titleStyle, typeStyle,
                Modifier.align(Alignment.TopCenter).offset(y = posterHeight * .60f)
                    .width(captionWidth).height(captionHeight),
            )
        }
    }
}

@Composable
private fun ScenePosterCaption(
    topic: String,
    type: String,
    titleStyle: TextStyle,
    typeStyle: TextStyle,
    modifier: Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Text(topic, Modifier.fillMaxWidth(), style = titleStyle)
        Surface(color = Color(0xFF35546D), shape = RlShape.Small) {
            Text(type, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = typeStyle)
        }
    }
}
