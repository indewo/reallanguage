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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.feature.formation.presentation.EditorialSheet

/** Labels remain native; long context flows outside the bounded artwork instead of being clipped. */
@Composable
internal fun SceneSummaryPoster(topic: String, type: String, detail: String?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val width = maxWidth
        val height = maxHeight
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val topicStyle = TextStyle(fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 19.sp)
        val detailStyle = TextStyle(fontFamily = EditorialFont.Serif, fontSize = 12.sp, lineHeight = 18.sp)
        val detailText = detail?.let { "补充 · $it" } ?: "想法来自你的回答"
        val topicHeight = with(density) { measurer.measure(topic, topicStyle,
            constraints = Constraints(maxWidth = (width * .49f).roundToPx().coerceAtLeast(1))).size.height.toDp() }
        val detailHeight = with(density) { measurer.measure(detailText, detailStyle,
            constraints = Constraints(maxWidth = (width * .52f).roundToPx().coerceAtLeast(1))).size.height.toDp() }
        // The safe rectangles belong to the paper labels, not to the overall image.
        // If real text cannot fit, reflow it as a separate native paper module.
        val natural = density.fontScale > 1.35f || height < 290.dp || topicHeight > height * .09f || detailHeight > height * .115f
        if (natural) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(painterResource(R.drawable.rl_v73_scene_type_dialogue), null, Modifier.fillMaxWidth().height(260.dp), contentScale = ContentScale.Fit)
                EditorialSheet(contentPadding = 16.dp, contentSpacing = 8.dp) {
                    Text(topic, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, color = RlColor.Ink, fontSize = 18.sp, lineHeight = 28.sp)
                    Text("形式 · $type", fontFamily = EditorialFont.Serif, color = RlColor.Ink, fontSize = 15.sp, lineHeight = 24.sp)
                    detail?.let { Text("补充 · $it", fontFamily = EditorialFont.Serif, color = RlColor.Ink, fontSize = 15.sp, lineHeight = 24.sp) }
                }
            }
        } else {
            Image(painterResource(R.drawable.rl_v73_scene_generation_poster), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            Text(topic, Modifier.offset(x = width * .19f, y = height * .105f).width(width * .49f),
                color = RlColor.OnPrimary, style = topicStyle)
            Text(type, Modifier.offset(x = width * .20f, y = height * .195f).width(width * .24f),
                color = RlColor.OnPrimary, fontFamily = EditorialFont.Serif, fontSize = 13.sp, lineHeight = 18.sp)
            Text("形式 · $type", Modifier.offset(x = width * .20f, y = height * .655f).width(width * .48f),
                color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontSize = 16.sp, lineHeight = 25.sp)
            Text(detailText, Modifier.offset(x = width * .19f, y = height * .79f).width(width * .52f),
                color = RlColor.Ink, style = detailStyle)
        }
    }
}
