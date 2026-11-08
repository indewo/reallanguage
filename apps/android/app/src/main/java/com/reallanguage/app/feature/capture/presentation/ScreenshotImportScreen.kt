package com.reallanguage.app.feature.capture.presentation

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.editorialUnderlinePainter
import com.reallanguage.app.feature.capture.data.ScreenshotOcr
import com.reallanguage.app.feature.capture.data.screenshotPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val clay = Color(0xFFB95134)
private val olive = Color(0xFF526344)

@Composable
fun ScreenshotImportScreen(
    onBack: () -> Unit,
    onRecognized: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val deliver by rememberUpdatedState(onRecognized)
    var items by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recognized by remember(items) { mutableStateOf<String?>(null) }
    val selectFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && !busy) {
            items = ArrayList((items + uris.map(Uri::toString)).distinct())
            error = null
        }
    }
    val choose = { selectFiles.launch(arrayOf("image/*")) }
    fun edit(newItems: List<String>) {
        if (!busy) {
            items = ArrayList(newItems)
            error = null
        }
    }
    fun translate() {
        if (busy || items.isEmpty()) return
        busy = true
        error = null
        val ordered = items.map(Uri::parse)
        scope.launch {
            try {
                val source = recognized ?: ScreenshotOcr.read(resolver, ordered).also { recognized = it }
                deliver(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.localizedMessage?.takeIf { it.isNotBlank() } ?: "导入失败，请重试。"
            } finally {
                busy = false
            }
        }
    }
    BackHandler(onBack = onBack)
    BoxWithConstraints(modifier.fillMaxSize().testTag("screenshot_import")) {
        Image(painterResource(R.drawable.rl_screenshot_import_frame), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        val compact = maxHeight < 380.dp
        val sideInset = (maxWidth * .083f).coerceIn(12.dp, 94.dp)
        val headerHeight = (maxHeight * .16f).coerceIn(48.dp, 84.dp)
        val buttonWidth = (maxWidth * .395f).coerceIn(172.dp, 420.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = sideInset)) {
            Box(Modifier.fillMaxWidth().height(headerHeight)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = RlColor.Ink)
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("截图导入", color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold,
                        fontSize = if (compact) 22.sp else 25.sp, maxLines = 1)
                    Image(editorialUnderlinePainter(), null, Modifier.width(58.dp).height(5.dp), contentScale = ContentScale.FillBounds)
                }
                if (items.isNotEmpty()) {
                    OutlinedButton(onClick = choose, enabled = !busy, modifier = Modifier.align(Alignment.CenterEnd),
                        border = BorderStroke(1.dp, olive), shape = RoundedCornerShape(7.dp),
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(17.dp), tint = olive)
                        Text("添加截图", color = olive, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            HorizontalDivider(color = RlColor.Border.copy(alpha = .75f), thickness = .7.dp)
            if (items.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = choose, border = BorderStroke(1.dp, olive), shape = RoundedCornerShape(9.dp)) {
                        Icon(Icons.Outlined.Add, null, tint = olive)
                        Text("添加截图", color = olive, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                ScreenshotGrid(items, !busy, ::edit, Modifier.fillMaxWidth().weight(1f))
            }
            error?.let {
                Text(it, color = clay, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            Button(onClick = ::translate, enabled = !busy && items.isNotEmpty(),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = if (compact) 4.dp else 8.dp)
                    .width(buttonWidth).height(if (compact) 39.dp else 46.dp)
                    .testTag("screenshot_import_submit"),
                contentPadding = PaddingValues(horizontal = 12.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = clay, contentColor = Color.White,
                    disabledContainerColor = if (busy) clay else clay.copy(alpha = .4f), disabledContentColor = Color.White)) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(17.dp), color = Color.White, strokeWidth = 1.8.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "正在导入中" else "识别上传截图", fontFamily = EditorialFont.Serif,
                    fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun ScreenshotGrid(items: List<String>, editable: Boolean, onEdit: (List<String>) -> Unit, modifier: Modifier) {
    val grid = rememberLazyGridState()
    val current by rememberUpdatedState(items)
    val applyEdit by rememberUpdatedState(onEdit)
    var moving by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var anchor by remember { mutableStateOf(Offset.Zero) }

    fun at(point: Offset): LazyGridItemInfo? = grid.layoutInfo.visibleItemsInfo.firstOrNull { cell ->
        point.x >= cell.offset.x && point.x < cell.offset.x + cell.size.width &&
            point.y >= cell.offset.y && point.y < cell.offset.y + cell.size.height
    }
    fun moveOverCell() {
        val key = moving ?: return
        val target = at(pointer) ?: return
        val to = current.indexOf(target.key)
        val from = current.indexOf(key)
        if (from >= 0 && to >= 0 && from != to) applyEdit(current.toMutableList().apply { add(to, removeAt(from)) })
    }
    BoxWithConstraints(modifier) {
        val cols = when {
            maxWidth >= 565.dp -> 4
            maxWidth >= 375.dp -> 3
            else -> 2
        }
        val gutters = if (cols == 4) 10.dp else 6.dp
        LazyVerticalGrid(columns = GridCells.Fixed(cols), state = grid,
            contentPadding = PaddingValues(start = 5.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
            verticalArrangement = Arrangement.spacedBy(gutters), horizontalArrangement = Arrangement.spacedBy(gutters),
            modifier = Modifier.fillMaxSize().testTag("screenshot_grid").pointerInput(editable) {
                if (!editable) return@pointerInput
                detectDragGesturesAfterLongPress(onDragStart = { point ->
                    at(point)?.let { cell ->
                        moving = cell.key as? String
                        pointer = point
                        anchor = point - Offset(cell.offset.x.toFloat(), cell.offset.y.toFloat())
                    }
                }, onDragEnd = { moving = null }, onDragCancel = { moving = null }) { change, delta ->
                    if (moving != null) {
                        change.consume()
                        pointer += delta
                        moveOverCell()
                    }
                }
            }) {
            itemsIndexed(items, key = { _, uri -> uri }) { index, uri ->
                val active = uri == moving
                val placement = if (active) Modifier.zIndex(2f).graphicsLayer {
                    val cell = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == uri }
                    if (cell != null) {
                        translationX = pointer.x - anchor.x - cell.offset.x
                        translationY = pointer.y - anchor.y - cell.offset.y
                    }
                    scaleX = 1.025f
                    scaleY = 1.025f
                } else Modifier.animateItem()
                val accessibility = Modifier.semantics {
                    if (editable) customActions = buildList {
                        for ((delta, label) in listOf(-1 to "向前移动", 1 to "向后移动")) {
                            val target = index + delta
                            if (target in items.indices) add(CustomAccessibilityAction(label) {
                                onEdit(items.toMutableList().apply { add(target, removeAt(index)) })
                                true
                            })
                        }
                    }
                }
                ScreenshotCard(uri, index, editable, onRemove = { onEdit(items - uri) },
                    modifier = placement.then(accessibility).fillMaxWidth().aspectRatio(1.6f))
            }
        }
        LaunchedEffect(moving) {
            if (moving == null) return@LaunchedEffect
            var previous = withFrameNanos { it }
            while (moving != null) {
                val now = withFrameNanos { it }
                val elapsed = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
                previous = now
                val height = grid.layoutInfo.viewportSize.height
                val edge = minOf(95f, height * .17f)
                if (edge <= 0) continue
                val speed = when {
                    pointer.y < edge -> -((edge - pointer.y) / edge).coerceIn(0f, 1f)
                    pointer.y > height - edge -> ((pointer.y - height + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (speed != 0f && grid.scrollBy(speed * height * elapsed * 1.75f) != 0f) moveOverCell()
            }
        }
        CaptureScrollHint(grid.scrollIndicatorState, grid.isScrollInProgress,
            Modifier.align(Alignment.CenterEnd).width(3.dp).fillMaxHeight())
    }
}

@Composable
private fun ScreenshotCard(uri: String, index: Int, editable: Boolean, onRemove: () -> Unit, modifier: Modifier) {
    val resolver = LocalContext.current.contentResolver
    val thumb by produceState<Pair<Boolean, Bitmap?>>(false to null, uri) {
        value = true to screenshotPreview(resolver, Uri.parse(uri))
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(9.dp), color = Color(0xFFFBF6EF),
        border = BorderStroke(.8.dp, Color(0xFFDFCFBC)), shadowElevation = 1.5.dp) {
        Column {
            Box(Modifier.fillMaxWidth().height(32.dp)) {
                Text("${index + 1}", Modifier.align(Alignment.CenterStart).padding(start = 10.dp), fontSize = 16.sp,
                    color = RlColor.Ink, fontFamily = EditorialFont.Serif, fontWeight = FontWeight.SemiBold)
                Canvas(Modifier.align(Alignment.Center).size(19.dp, 12.dp)) {
                    for (x in 0..2) for (y in 0..1)
                        drawCircle(Color(0xFFB7A58E), radius = 1.7.dp.toPx(),
                            center = Offset(size.width * (x + .5f) / 3, size.height * (y + .5f) / 2))
                }
                Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(39.dp)
                    .clickable(enabled = editable, onClickLabel = "删除第 ${index + 1} 张截图", onClick = onRemove),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, "删除第 ${index + 1} 张截图",
                        modifier = Modifier.size(18.dp).background(clay, CircleShape).padding(2.dp), tint = Color.White)
                }
            }
            HorizontalDivider(color = RlColor.Border.copy(alpha = .6f), thickness = .6.dp)
            Box(Modifier.fillMaxSize().padding(4.dp).clip(RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                val bitmap = thumb.second
                when {
                    bitmap != null -> Image(remember(bitmap) { bitmap.asImageBitmap() }, "第 ${index + 1} 张截图",
                        Modifier.fillMaxSize(), alignment = Alignment.TopCenter, contentScale = ContentScale.Crop)
                    thumb.first -> Text("无法预览", color = RlColor.Ink, fontSize = 12.sp)
                    else -> CircularProgressIndicator(Modifier.size(17.dp), color = olive, strokeWidth = 1.6.dp)
                }
            }
        }
    }
}
