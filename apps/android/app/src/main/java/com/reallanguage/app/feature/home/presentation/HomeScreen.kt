package com.reallanguage.app.feature.home.presentation

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.EditorialFont
import com.reallanguage.app.core.designsystem.RlColor
import com.reallanguage.app.core.designsystem.RlFont
import com.reallanguage.app.core.designsystem.RlPaperBackground
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.min

private enum class Cover(val title: String, val detail: String, val art: Int, val tint: Color,
                         val action: HomeAction, val tag: String) {
    Record("开始录音", "录下真实对话，稍后学习", R.drawable.rl_home_cover_record, Color(0xff476882),
        HomeAction.QuickRecordingRequested, "home-quick-recording"),
    Screenshots("对话截图", "从真实对话里选句子", R.drawable.rl_home_cover_shot, Color(0xff879456),
        HomeAction.ScreenshotImportRequested, "home-screenshot-card"),
    Audio("导入音频", "从已有录音里选句子", R.drawable.rl_home_cover_audio, Color(0xff7295a9),
        HomeAction.AudioImportRequested, "home-audio-card"),
    Scene("AI设定场景", "练习你会遇到的对话", R.drawable.rl_home_cover_scene, Color(0xff69839a),
        HomeAction.AiSceneRequested, "home-scene-card"),
    Diary("日常记录", "把生活片段变成英语", R.drawable.rl_home_cover_daily, Color(0xffb86746),
        HomeAction.DiaryRequested, "home-diary-card"),
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    recordingContent: (@Composable () -> Unit)? = null,
) {
    RlPaperBackground(modifier, showCornerCollage = false) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val margin = (maxWidth * .032f).coerceIn(10.dp, 50.dp)
            val headerHeight = (maxHeight * .145f).coerceIn(42.dp, 90.dp)
            val footerHeight = (maxHeight * .145f).coerceIn(45.dp, 84.dp)
            Column(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                HomeHeader(onAction, Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = margin))
                HomeDeck(onAction, Modifier.weight(1f).fillMaxWidth())
                if (recordingContent != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { recordingContent() }
                }
                Row(
                    Modifier.fillMaxWidth().height(footerHeight).padding(horizontal = margin, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy((margin * .30f).coerceAtMost(15.dp)),
                ) {
                    FooterEntry("词汇本", "home-vocabulary", R.drawable.rl_home_vocabulary_book_v4,
                        modifier = Modifier.weight(1f)) { onAction(HomeAction.VocabularyRequested) }
                    FooterEntry("句式本", "home-pattern", R.drawable.rl_home_pattern_book_v2,
                        modifier = Modifier.weight(1f)) { onAction(HomeAction.SentencePatternsRequested) }
                    FooterEntry(if (state.hasDueReview) "统一回顾已到期" else "统一回顾",
                        "home-review-card", icon = Icons.Outlined.CalendarMonth,
                        accent = Color(0xffc65c38), modifier = Modifier.weight(1.08f)) {
                        onAction(HomeAction.DueReviewRequested)
                    }
                    FooterEntry("继续未完成学习", "home-incomplete-card", icon = Icons.Outlined.Assignment,
                        accent = Color(0xffb77736), modifier = Modifier.weight(1.12f)) {
                        onAction(HomeAction.IncompleteLearningRequested)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(onAction: (HomeAction) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val size = (maxHeight.value * .69f).coerceIn(23f, 47f)
        val actionInset = (maxWidth * .016f).coerceIn(4.dp, 20.dp)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(stringResource(R.string.app_name), Modifier.weight(1f),
                style = TextStyle(fontFamily = RlFont.Brand, fontWeight = FontWeight.Bold, color = RlColor.Ink),
                autoSize = TextAutoSize.StepBased(18.sp, size.sp), maxLines = 1)
            listOf(
                Triple("消息", Icons.Outlined.ChatBubbleOutline, HomeAction.MessagesRequested),
                Triple("设置", Icons.Outlined.Settings, HomeAction.SettingsRequested),
                Triple("账号", Icons.Outlined.AccountCircle, HomeAction.AccountRequested),
            ).forEach { (label, image, action) ->
                Row(Modifier.fillMaxHeight().clickable(role = Role.Button) { onAction(action) }
                    .padding(horizontal = actionInset),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(image, null, Modifier.size((size * .55f).dp), tint = RlColor.Ink)
                    Spacer(Modifier.width(4.dp))
                    Text(label, color = RlColor.Ink, fontSize = (size * .48f).sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// A single position drives the whole pile, including the covered return path behind it.
private fun wrapped(distance: Float): Float {
    val count = Cover.entries.size.toFloat()
    return ((distance + count / 2f) % count + count) % count - count / 2f
}

private fun ease(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun blend(start: Float, end: Float, t: Float) = start + (end - start) * t

private const val FrontWidth = .416f
private const val FrontHeight = .91f

private data class CoverPose(val distance: Float) {
    val depth = abs(distance)
    val direction = sign(distance)
    val side = ease(depth)
    val far = ease(depth - 1f)
    private val back = ease((depth - 2f) * 2f)
    private val reach = depth.coerceAtMost(1f)
    // Travel farther from the spine during the exchange. The two front sheets pass at
    // their paper edges; changing their paint order cannot cut through a title.
    private val spread = .237f * reach + .185f * reach * (1f - reach)
    val x = direction * (spread + .169f * far) * (1f - back)
    private val lift = 4f * reach * (1f - reach)
    val y = -.012f + .044f * side - .018f * direction * lift + .045f * back
    val width = blend(FrontWidth, .244f, side) - .052f * far - .05f * back
    val height = blend(FrontHeight, .775f, side) + .035f * far - .22f * back
    val yaw = -direction * (14f * side + 4f * far + 3f * lift) * (1f - back)
    val roll = -direction * (2.6f * side + .9f * far + 1.8f * lift) * (1f - back)
}

@Composable
private fun HomeDeck(onAction: (HomeAction) -> Unit, modifier: Modifier) {
    val position = remember { mutableFloatStateOf(0f) }
    var destination by remember { mutableIntStateOf(0) }
    var animation by remember { mutableStateOf<Job?>(null) }
    var carryVelocity by remember { mutableFloatStateOf(0f) }
    val poses = remember {
        Cover.entries.indices.map { index -> derivedStateOf { CoverPose(wrapped(index - position.floatValue)) } }
    }
    val focused by remember {
        derivedStateOf { Math.floorMod(position.floatValue.roundToInt(), Cover.entries.size) }
    }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                animation?.cancel()
                animation = null
                position.floatValue = 0f
                destination = 0
                carryVelocity = 0f
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); animation?.cancel() }
    }
    fun settle(target: Int, releaseVelocity: Float = carryVelocity) {
        animation?.cancel()
        destination = target
        animation = scope.launch {
            animate(position.floatValue, target.toFloat(), initialVelocity = releaseVelocity,
                animationSpec = spring(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = .0008f)) { value, velocity ->
                position.floatValue = value
                carryVelocity = velocity
            }
            destination = Math.floorMod(target, Cover.entries.size)
            position.floatValue = destination.toFloat()
            carryVelocity = 0f
            animation = null
        }
    }
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds().testTag("home-deck"),
            contentAlignment = Alignment.Center) {
            val stepPx = with(LocalDensity.current) { (maxWidth * .29f).toPx().coerceAtLeast(1f) }
            val drag = rememberDraggableState { delta ->
                position.floatValue -= delta / stepPx
                destination = position.floatValue.roundToInt()
            }
            Layout(content = {
                Cover.entries.forEachIndexed { index, cover ->
                    key(cover) {
                        CoverFace(cover, poses[index], Modifier.testTag(cover.tag)
                            .semantics { selected = index == focused; contentDescription = cover.title }
                            .clickable(role = Role.Button) { onAction(cover.action) })
                    }
                }
            }, modifier = Modifier.fillMaxSize().draggable(drag, Orientation.Horizontal,
                startDragImmediately = animation?.isActive == true,
                onDragStarted = { animation?.cancel(); animation = null; carryVelocity = 0f },
                onDragStopped = { velocity ->
                    val speed = (-velocity / stepPx).coerceIn(-5f, 5f)
                    val target = (position.floatValue + (speed * .13f).coerceIn(-.7f, .7f)).roundToInt()
                    settle(target, speed)
                })) { children, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val cardWidth = (width * FrontWidth).roundToInt()
                val cardHeight = (height * FrontHeight).roundToInt()
                val fixed = Constraints.fixed(cardWidth, cardHeight)
                val cards = children.map { it.measure(fixed) }
                layout(width, height) {
                    cards.forEachIndexed { index, card ->
                        val pose = poses[index].value
                        card.placeWithLayer((width - cardWidth) / 2, (height - cardHeight) / 2,
                            zIndex = 3f - pose.depth) {
                            translationX = width * pose.x
                            translationY = height * pose.y
                            scaleX = pose.width / FrontWidth
                            scaleY = pose.height / FrontHeight
                            rotationY = pose.yaw
                            rotationZ = pose.roll
                            cameraDistance = 1150.dp.toPx()
                        }
                    }
                }
            }
            TurnArrow(false, Modifier.align(Alignment.CenterStart).zIndex(6f)) { settle(destination - 1) }
            TurnArrow(true, Modifier.align(Alignment.CenterEnd).zIndex(6f)) { settle(destination + 1) }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 17.dp, max = 30.dp).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Cover.entries.indices.forEach { index ->
                Box(Modifier.padding(horizontal = 3.dp).size(7.dp)
                    .background(if (index == focused) RlColor.ActionVivid else Color(0xffcdc2b9), CircleShape))
            }
        }
    }
}

@Composable
private fun TurnArrow(next: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.size(40.dp).testTag(if (next) "home-next" else "home-previous")
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(24.dp).shadow(2.dp, CircleShape).background(Color(0xfffbf5ec), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(if (next) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft,
                if (next) "下一张" else "上一张", Modifier.size(21.dp), tint = RlColor.Ink)
        }
    }
}

@Composable
private fun CoverFace(cover: Cover, poseState: State<CoverPose>, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val art = painterResource(cover.art)
    Box(modifier.fillMaxSize().padding(2.dp).shadow(5.dp, shape).background(Color(0xfffcf7f0), shape)) {
        Image(painterResource(R.drawable.rl_home_task_note_adaptive), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.align(Alignment.TopStart).size(36.dp).graphicsLayer {
            alpha = if (cover == Cover.Record) poseState.value.side else 1f
        }) {
            val p = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(0f, size.height); close() }
            drawPath(p, cover.tint)
            drawLine(Color.White.copy(alpha = .77f), Offset(0f, size.height * .84f),
                Offset(size.width * .84f, 0f), .8.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.5.dp.toPx())))
        }
        // Measure the illustration and both pieces of type once. They move on the paper
        // without rewrapping or stretching as its front and side silhouettes exchange.
        Layout(content = {
            Image(art, null, contentScale = ContentScale.Fit)
            BasicText(cover.title, style = TextStyle(fontFamily = EditorialFont.Serif,
                fontSize = 48.sp, fontWeight = FontWeight.Bold, color = RlColor.Ink),
                softWrap = false, maxLines = 1)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(cover.detail, style = TextStyle(color = RlColor.Ink, fontSize = 22.sp,
                    fontWeight = FontWeight.Medium), softWrap = false, maxLines = 1)
                Canvas(Modifier.padding(top = 3.dp).size(92.dp, 7.dp)) {
                    val sw = size.width; val sh = size.height
                    val ink = Path().apply {
                        moveTo(0f, .88f * sh); quadraticTo(.66f * sw, -sh, .59f * sw, .27f * sh)
                        lineTo(.40f * sw, .95f * sh); quadraticTo(.71f * sw, .03f * sh, sw, .62f * sh)
                    }
                    drawPath(ink, RlColor.Ink, style = Stroke(1.dp.toPx()))
                }
            }
        }, modifier = Modifier.matchParentSize()) { children, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val aspect = art.intrinsicSize.width / art.intrinsicSize.height
            val artH = min(h.toFloat(), w / aspect)
            val picture = children[0].measure(Constraints.fixed((artH * aspect).roundToInt(), artH.roundToInt()))
            val title = children[1].measure(Constraints())
            val detail = children[2].measure(Constraints())
            layout(w, h) {
                val pose = poseState.value
                val sx = pose.width / FrontWidth
                val sy = pose.height / FrontHeight
                val width = w * sx
                val height = h * sy
                // Raise the title above the artwork before moving it across. Reversing the
                // gesture retraces both paths, even when the finger stops halfway through.
                val upper = ease(pose.depth / .60f)
                val across = ease((pose.depth - .14f) / .86f)
                val strip = blend(.57f, .65f, pose.far)
                val sideCenter = if (pose.direction < 0f) .025f + strip / 2f else .975f - strip / 2f
                val labelCenter = width * blend(.744f, sideCenter, across)
                fun androidx.compose.ui.layout.Placeable.fitAt(
                    cx: Float, cy: Float, availableWidth: Float, availableHeight: Float, opacity: Float = 1f
                ) {
                    val fit = min(availableWidth / this.width, availableHeight / this.height)
                    placeWithLayer(0, 0) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = (cx - this@fitAt.width * fit / 2f) / sx
                        translationY = (cy - this@fitAt.height * fit / 2f) / sy
                        scaleX = fit / sx
                        scaleY = fit / sy
                        alpha = opacity
                    }
                }
                val pictureTop = blend(.014f, .33f, upper)
                val pictureBottom = blend(.982f, .93f, pose.side)
                val pictureCenter = blend(.268f, .50f + pose.direction * .038f, across)
                picture.fitAt(width * pictureCenter, height * (pictureTop + pictureBottom) / 2f,
                    width * blend(.515f, .82f, across), height * (pictureBottom - pictureTop))
                val titleCenterY = blend(.392f, .187f, upper)
                title.fitAt(labelCenter, height * titleCenterY, width * blend(.46f, strip, across),
                    height * blend(.29f, .175f, upper))
                detail.fitAt(labelCenter, height * blend(.634f, .307f, upper),
                    width * blend(.432f, strip, across), height * blend(.20f, .11f, upper),
                    1f - ease((pose.depth - .12f) / .61f))
            }
        }
    }
}

@Composable
private fun FooterEntry(label: String, tag: String, art: Int? = null, icon: ImageVector? = null,
                        accent: Color = RlColor.Ink, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    BoxWithConstraints(modifier.fillMaxHeight().padding(vertical = 2.dp)
        .shadow(2.dp, shape).background(Color(0xfff7ebe0), shape)
        .testTag(tag).clickable(role = Role.Button, onClick = onClick)) {
        Image(painterResource(R.drawable.rl_home_task_note_adaptive), null, Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds, alpha = .53f)
        val iconSize = (maxHeight * .58f).coerceIn(21.dp, 38.dp)
        Row(Modifier.fillMaxSize().padding(horizontal = (maxWidth * .045f).coerceIn(3.dp, 10.dp)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (art != null) {
                Image(painterResource(art), null, Modifier.size(iconSize), contentScale = ContentScale.Fit)
            } else if (icon != null) {
                Icon(icon, null, Modifier.size(iconSize).background(accent, RoundedCornerShape(4.dp)).padding(3.dp), tint = Color.White)
            }
            BasicText(label, Modifier.weight(1f), style = TextStyle(fontFamily = EditorialFont.Serif,
                color = RlColor.Ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                autoSize = TextAutoSize.StepBased(9.sp, 20.sp), maxLines = 1)
            Icon(Icons.Outlined.ArrowForward, null, Modifier.size((iconSize * .55f).coerceAtLeast(12.dp)), tint = RlColor.Ink)
        }
    }
}
