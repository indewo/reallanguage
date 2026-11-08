package com.reallanguage.app.feature.capture.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.reallanguage.app.R
import com.reallanguage.app.core.app.rememberConnectionAwareSentenceSpeaker
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.editorialUnderlinePainter
import com.reallanguage.app.feature.capture.domain.BilingualParagraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val selectionClay = Color(0xFFB95033)
private val selectedWash = Color(0xFFF6E5D8)

@Composable
fun BilingualPassagesScreen(
    paragraphs: List<BilingualParagraph>,
    selected: Set<String>,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val speaker = rememberConnectionAwareSentenceSpeaker()
    val owner = LocalLifecycleOwner.current
    var playback by remember { mutableStateOf<Job?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(speaker, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { playback?.cancel(); speaker.stop(); playing = null }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); playback?.cancel(); speaker.close() }
    }
    val speak: (String, String) -> Unit = { key, text ->
        playback?.cancel()
        speaker.stop()
        playing = key
        playbackError = null
        playback = scope.launch {
            if (!speaker.play(text)) playbackError = "暂时无法朗读，请检查设备的英语语音。"
            playing = null
        }
    }
    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize().testTag("bilingual_passages")) {
        val small = maxHeight < 360.dp || maxWidth < 640.dp
        val largeFont = LocalDensity.current.fontScale >= 1.4f
        val header = (maxHeight * .157f).coerceIn(45.dp, 82.dp)
        val horizontalInset = (maxWidth * .093f).coerceIn(14.dp, 140.dp)
        val projection = remember(paragraphs) { paragraphs.map { englishRows(it) } }
        Image(painterResource(R.drawable.rl_bilingual_passages_frame), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxSize().padding(horizontal = horizontalInset)) {
            Box(Modifier.fillMaxWidth().height(header)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink)
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("选择学习短句", modifier = Modifier.semantics { heading() }, color = RlColor.Ink,
                        fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold, fontSize = if (small) 20.sp else 24.sp)
                    Image(editorialUnderlinePainter(), null, Modifier.width(86.dp).height(5.dp), contentScale = ContentScale.FillBounds)
                }
            }
            HorizontalDivider(color = RlColor.Border.copy(alpha = .8f), thickness = .7.dp)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(end = 5.dp).testTag("bilingual_scroll")) {
                    items(paragraphs.size) { index ->
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            Text(paragraphs[index].textZh,
                                modifier = Modifier.weight(.35f).padding(start = 8.dp, top = 11.dp, end = 12.dp, bottom = 12.dp),
                                color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Medium,
                                fontSize = if (small) 15.sp else 17.sp, lineHeight = if (small) 22.sp else 25.sp)
                            VerticalDivider(Modifier.fillMaxHeight(), color = RlColor.Border.copy(alpha = .75f), thickness = .7.dp)
                            Column(Modifier.weight(.65f).padding(start = 5.dp, top = 4.dp, bottom = 4.dp)) {
                                projection[index].forEachIndexed { rowIndex, row ->
                                    val key = row.sentenceId ?: "context-$index-$rowIndex"
                                    val checked = row.sentenceId in selected
                                    EnglishRow(row, checked, checked || selected.size < 3, playing == key,
                                        onToggle = { row.sentenceId?.let(onSelect) },
                                        onSpeak = { speak(key, row.text) }, small = small)
                                    if (rowIndex != projection[index].lastIndex) {
                                        HorizontalDivider(color = RlColor.Border.copy(alpha = .48f), thickness = .6.dp)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = RlColor.Border.copy(alpha = .8f), thickness = .7.dp)
                    }
                }
                CaptureScrollHint(list.scrollIndicatorState, list.isScrollInProgress,
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp))
            }
            HorizontalDivider(color = RlColor.Border.copy(alpha = .76f), thickness = .7.dp)
            if (playbackError != null) Text(playbackError.orEmpty(), color = selectionClay, fontSize = 12.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(Modifier.fillMaxWidth().padding(vertical = if (small) 3.dp else 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("已选 ${selected.size}/3", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.SemiBold,
                    fontSize = if (small) 16.sp else 18.sp, modifier = Modifier
                        .background(Color(0xFFD9D4AD), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 2.dp).semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.width(6.dp))
                Button(onClick = onContinue, enabled = selected.size in 1..3, modifier = Modifier.testTag("bilingual_continue"),
                    shape = RoundedCornerShape(7.dp),
                    contentPadding = PaddingValues(horizontal = if (largeFont) 10.dp else 16.dp, vertical = 5.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = selectionClay, contentColor = Color.White,
                        disabledContainerColor = selectionClay.copy(alpha = .4f), disabledContentColor = Color.White)) {
                    Text("继续选择学习目标", fontFamily = EditorialFont.Serif, fontWeight = FontWeight.SemiBold,
                        fontSize = if (small) 15.sp else 17.sp)
                }
            }
        }
    }
}

private data class EnglishRowContent(val sentenceId: String?, val text: String)
private val paragraphWhitespace = Regex("\\s+")

/** Preserve surrounding context when only part of a coherent English paragraph is eligible. */
private fun englishRows(paragraph: BilingualParagraph): List<EnglishRowContent> = buildList {
    val english = paragraph.textEn.trim().replace(paragraphWhitespace, " ")
    var position = 0
    for (sentence in paragraph.sentences) {
        val phrase = sentence.textEn.trim().replace(paragraphWhitespace, " ")
        if (phrase.isEmpty()) continue
        val match = english.indexOf(phrase, startIndex = position)
        if (match < 0) continue
        val before = english.substring(position, match).trim()
        if (before.isNotEmpty()) add(EnglishRowContent(null, before))
        add(EnglishRowContent(sentence.id, phrase))
        position = match + phrase.length
    }
    val remaining = english.substring(position).trim()
    if (remaining.isNotEmpty()) add(EnglishRowContent(null, remaining))
}

@Composable
private fun EnglishRow(row: EnglishRowContent, checked: Boolean, canSelect: Boolean, playing: Boolean,
    onToggle: () -> Unit, onSpeak: () -> Unit, small: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(if (checked) selectedWash else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically) {
        val selectable = row.sentenceId?.let {
            Modifier.toggleable(checked, enabled = canSelect, role = Role.Checkbox, onValueChange = { onToggle() })
                .testTag("bilingual_sentence_$it")
        } ?: Modifier
        Row(Modifier.weight(1f).then(selectable).heightIn(min = if (small) 34.dp else 40.dp)
            .padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (row.sentenceId != null) {
                val box = RoundedCornerShape(3.dp)
                Box(Modifier.size(17.dp).background(if (checked) selectionClay else Color.Transparent, box)
                    .border(BorderStroke(1.25.dp, if (checked) selectionClay else Color(0xFF958978)), box),
                    contentAlignment = Alignment.Center) {
                    if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(15.dp), tint = Color.White)
                }
                Spacer(Modifier.width(if (small) 7.dp else 10.dp))
            }
            Text(row.text, color = RlColor.Ink, fontFamily = FontFamily.Serif,
                fontSize = if (small) 15.sp else 17.sp, lineHeight = if (small) 20.sp else 23.sp)
        }
        // The entire outer target handles touch. The ink square stays square even in wrapped rows.
        IconButton(onClick = onSpeak, modifier = Modifier.size(if (small) 38.dp else 44.dp)
            .testTag("bilingual_play_${row.sentenceId ?: row.text}")
            .semantics { stateDescription = if (playing) "正在朗读" else "可朗读" }) {
            Box(Modifier.size(if (small) 24.dp else 28.dp)
                .background(if (playing) Color(0xFF315879) else RlColor.Ink, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, "朗读 ${row.text}", Modifier.size(17.dp), tint = Color.White)
            }
        }
    }
}
