package com.reallanguage.app.feature.capture.presentation

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.editorialUnderlinePainter
import com.reallanguage.app.feature.capture.data.AUDIO_DOCUMENT_TYPES
import com.reallanguage.app.feature.capture.data.ImportAudioInfo
import com.reallanguage.app.feature.capture.data.inspectImportAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private fun mediaClock(ms: Long): String =
    String.format(Locale.ROOT, "%d:%02d", ms / 60_000, ms / 1_000 % 60)

@Composable
fun AudioImportScreen(onBack: () -> Unit, onTranscribe: suspend (Uri) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transcribe by rememberUpdatedState(onTranscribe)
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var candidateId by rememberSaveable { mutableStateOf<String?>(null) }
    var file by remember { mutableStateOf<ImportAudioInfo?>(null) }
    var reading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) { candidateId = uri.toString(); error = null }
    }
    LaunchedEffect(candidateId, selectedId) {
        val target = candidateId ?: selectedId ?: return@LaunchedEffect
        if (candidateId == null && file?.uri?.toString() == target) return@LaunchedEffect
        reading = true
        try {
            val verified = inspectImportAudio(context, Uri.parse(target))
            file = verified
            selectedId = target
            error = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (ex: Exception) {
            error = if (ex is IllegalArgumentException && ex.message?.any { it.code > 127 } == true)
                ex.message else "无法读取这个音频文件，请重新选择。"
            if (target == selectedId && file == null) selectedId = null
        } finally {
            candidateId = null
            reading = false
        }
    }
    val choose = { launcher.launch(AUDIO_DOCUMENT_TYPES) }
    val clay = Color(0xffb95134)
    val olive = Color(0xff526344)
    BackHandler { if (!busy) onBack() }
    BoxWithConstraints(modifier.fillMaxSize().testTag("audio_import")) {
        Image(painterResource(R.drawable.rl_screenshot_import_frame), null, Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds)
        val compact = maxHeight < 380.dp
        val width = maxWidth
        val height = maxHeight
        Column(Modifier.fillMaxSize().padding(horizontal = (width * .083f).coerceIn(12.dp, 94.dp))) {
            Box(Modifier.fillMaxWidth().height((height * .16f).coerceIn(48.dp, 84.dp))) {
                IconButton(onClick = onBack, enabled = !busy, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink)
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("导入音频", fontFamily = EditorialFont.Serif, color = RlColor.Ink, fontWeight = FontWeight.Bold,
                        fontSize = if (compact) 22.sp else 25.sp)
                    Image(editorialUnderlinePainter(), null, Modifier.width(58.dp).height(5.dp),
                        contentScale = ContentScale.FillBounds)
                }
                if (file != null) OutlinedButton(onClick = choose, enabled = !busy && !reading,
                    modifier = Modifier.align(Alignment.CenterEnd).testTag("audio_replace"),
                    border = BorderStroke(1.dp, olive), shape = RoundedCornerShape(8.dp)) {
                    Text("更换文件", color = olive, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider(color = RlColor.Divider)
            Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 5.dp), contentAlignment = Alignment.Center) {
                val selected = file
                if (selected == null) {
                    OutlinedButton(onClick = choose, enabled = !reading,
                        modifier = Modifier.testTag("audio_select"), border = BorderStroke(1.dp, olive)) {
                        Text(if (reading) "正在读取文件" else "选择音频文件", color = olive, fontFamily = EditorialFont.Serif)
                    }
                } else {
                    key(selected.uri) {
                        AudioFileCard(selected, enabled = !busy && !reading, onError = { error = it },
                            modifier = Modifier.fillMaxWidth(.88f).height((height * .4f).coerceIn(106.dp, 194.dp)))
                    }
                }
            }
            error?.let { Text(it, Modifier.align(Alignment.CenterHorizontally),
                fontSize = 13.sp, color = RlColor.Error) }
            Button(onClick = {
                val current = file ?: return@Button
                if (busy || reading) return@Button
                busy = true
                error = null
                scope.launch {
                    try { transcribe(current.uri) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (ex: Exception) { error = ex.localizedMessage?.takeIf { it.isNotBlank() } ?: "转译失败，请重试。" }
                    finally { busy = false }
                }
            }, enabled = !busy && !reading && file != null,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = if (compact) 4.dp else 8.dp)
                    .width((width * .395f).coerceIn(172.dp, 420.dp)).height(if (compact) 39.dp else 46.dp)
                    .testTag("audio_transcribe"),
                contentPadding = PaddingValues(horizontal = 12.dp), shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = clay, contentColor = Color.White,
                    disabledContainerColor = if (busy) clay else clay.copy(alpha = .4f), disabledContentColor = Color.White)) {
                if (busy) { CircularProgressIndicator(Modifier.size(17.dp), color = Color.White, strokeWidth = 1.8.dp)
                    Spacer(Modifier.width(8.dp)) }
                Text(if (busy) "正在转译文字" else "转译音频", fontFamily = EditorialFont.Serif,
                    fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun AudioFileCard(file: ImportAudioInfo, enabled: Boolean, onError: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var cursor by rememberSaveable { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    val report by rememberUpdatedState(onError)
    fun release() {
        val old = player
        player = null; playing = false; preparing = false
        old?.setOnPreparedListener(null)
        old?.setOnErrorListener(null)
        old?.setOnCompletionListener(null)
        old?.release()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) release() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); release() }
    }
    LaunchedEffect(enabled) { if (!enabled) release() }
    LaunchedEffect(playing) {
        while (playing) {
            if (!seeking) cursor = runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: cursor
            delay(180)
        }
    }
    fun toggle() {
        if (!enabled || preparing) return
        try {
            val current = player
            if (current == null) {
                val fresh = MediaPlayer()
                player = fresh
                preparing = true
                fresh.setOnPreparedListener {
                    if (player !== it) return@setOnPreparedListener
                    preparing = false
                    it.seekTo(cursor, MediaPlayer.SEEK_CLOSEST)
                    it.start(); playing = true
                }
                fresh.setOnErrorListener { who, _, _ ->
                    if (who === player) { release(); report("无法播放这个音频文件。") }
                    true
                }
                fresh.setOnCompletionListener { if (it === player) { cursor = 0L; release() } }
                fresh.setDataSource(context, file.uri)
                fresh.prepareAsync()
            } else if (playing) {
                current.pause(); cursor = current.currentPosition.toLong(); playing = false
            } else { current.start(); playing = true }
        } catch (_: Exception) { release(); report("无法播放这个音频文件。") }
    }
    BoxWithConstraints(modifier) {
        Image(painterResource(R.drawable.rl_home_task_note_adaptive), null, Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds)
        val inset = (maxWidth * .055f).coerceIn(7.dp, 30.dp)
        val iconWidth = (maxWidth * .15f).coerceIn(32.dp, 96.dp)
        Row(Modifier.fillMaxSize().padding(horizontal = inset), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.rl_import_audio_document), null,
                Modifier.width(iconWidth).fillMaxHeight(.74f), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(inset))
            VerticalDivider(Modifier.fillMaxHeight(.66f), color = RlColor.Divider)
            Spacer(Modifier.width(inset))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(file.name, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold,
                    color = RlColor.Ink, fontSize = 22.sp, lineHeight = 26.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("audio_filename"))
                Text(mediaClock(file.durationMs), color = RlColor.Ink, fontFamily = EditorialFont.Serif,
                    fontSize = 14.sp, modifier = Modifier.testTag("audio_duration"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedIconButton(onClick = ::toggle, enabled = enabled && !preparing,
                        modifier = Modifier.size(39.dp).testTag("audio_play")) {
                        Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            if (playing) "暂停试听" else "播放", tint = RlColor.Ink)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(mediaClock(cursor), color = RlColor.Ink, fontSize = 12.sp,
                        modifier = Modifier.testTag("audio_position"))
                    Slider(value = (cursor.toDouble() / file.durationMs).toFloat().coerceIn(0f, 1f),
                        onValueChange = { seeking = true; cursor = (file.durationMs * it.toDouble()).toLong() },
                        onValueChangeFinished = {
                            if (!preparing) runCatching { player?.seekTo(cursor, MediaPlayer.SEEK_CLOSEST) }
                            seeking = false
                        }, enabled = enabled && !preparing, modifier = Modifier.weight(1f).testTag("audio_seek"),
                        colors = SliderDefaults.colors(thumbColor = RlColor.Primary,
                            activeTrackColor = RlColor.Primary, inactiveTrackColor = RlColor.Divider))
                }
            }
        }
    }
}
