package com.reallanguage.app.feature.home.presentation

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
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

private val HomeBackground = Color(0xFFF8F5EF)
private val HomeSurface = Color(0xFFFCFAF6)
private val HomeInk = Color(0xFF071A33)

private enum class Cover(val title: String, val detail: String, val art: Int, val tint: Color,
                         val action: HomeAction, val tag: String) {
    Record("开始录音", "录下真实对话，稍后学习", R.drawable.rl_home_record_plain_20261123, Color(0xff476882),
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
    Box(modifier.fillMaxSize().background(HomeBackground)) {
        Image(painterResource(R.drawable.rl_paper_background), null, Modifier.matchParentSize(),
            contentScale = ContentScale.Crop, alpha = .10f)
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
                style = TextStyle(fontFamily = RlFont.Brand, fontWeight = FontWeight.Bold, color = HomeInk),
                autoSize = TextAutoSize.StepBased(18.sp, size.sp), maxLines = 1)
            listOf(
                Triple("消息", Icons.Outlined.ChatBubbleOutline, HomeAction.MessagesRequested),
                Triple("设置", Icons.Outlined.Settings, HomeAction.SettingsRequested),
                Triple("账号", Icons.Outlined.AccountCircle, HomeAction.AccountRequested),
            ).forEach { (label, image, action) ->
                Row(Modifier.fillMaxHeight().clickable(role = Role.Button) { onAction(action) }
                    .padding(horizontal = actionInset),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(image, null, Modifier.size((size * .55f).dp), tint = HomeInk)
                    Spacer(Modifier.width(4.dp))
                    Text(label, color = HomeInk, fontSize = (size * .48f).sp, fontWeight = FontWeight.SemiBold)
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

private const val FrontWidth = .408f
private const val FrontHeight = .91f
private const val FrontAspect = 772f / 470f

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
    val x = direction * (spread + .123f * far) * (1f - back)
    private val lift = 4f * reach * (1f - reach)
    val y = -.012f + .044f * side - .018f * direction * lift + .045f * back
    // Readability comes from milder perspective and uniform depth scaling, not flattening the ink.
    val scale = blend(1f, .80f, side) - .06f * far - .18f * back
    val yaw = -direction * (26f * side + 8f * far) * (1f - back)
    val roll = -direction * (2.6f * side + .9f * far + 1.8f * lift) * (1f - back)
}

private class DeckHitCoordinates {
    var deck: LayoutCoordinates? = null
    val cards = arrayOfNulls<LayoutCoordinates>(Cover.entries.size)
}

private fun containsPaper(point: Offset, corners: List<Offset>): Boolean {
    val crosses = corners.indices.map { index ->
        val a = corners[index]
        val b = corners[(index + 1) % corners.size]
        (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
    }
    return crosses.all { it >= -.5f } || crosses.all { it <= .5f }
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
    val hits = remember { DeckHitCoordinates() }
    val currentAction by rememberUpdatedState(onAction)
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
                        CoverFace(cover, index, position, Modifier.testTag(cover.tag)
                            .onGloballyPositioned { hits.cards[index] = it }
                            .semantics {
                                selected = index == focused
                                contentDescription = cover.title
                                role = Role.Button
                                onClick(cover.title) { currentAction(cover.action); true }
                            })
                    }
                }
            }, modifier = Modifier.fillMaxSize().onGloballyPositioned { hits.deck = it }
                .pointerInput(Unit) {
                    detectTapGestures { point ->
                        val rootPoint = hits.deck?.takeIf { it.isAttached }?.localToRoot(point) ?: return@detectTapGestures
                        val card = Cover.entries.indices.sortedBy { poses[it].value.depth }.firstOrNull { index ->
                            val coordinates = hits.cards[index]?.takeIf { it.isAttached } ?: return@firstOrNull false
                            val w = coordinates.size.width.toFloat()
                            val h = coordinates.size.height.toFloat()
                            containsPaper(rootPoint, listOf(Offset.Zero, Offset(w, 0f), Offset(w, h), Offset(0f, h)).map(coordinates::localToRoot))
                        }
                        card?.let { currentAction(Cover.entries[it].action) }
                    }
                }.draggable(drag, Orientation.Horizontal,
                startDragImmediately = animation?.isActive == true,
                onDragStarted = { animation?.cancel(); animation = null; carryVelocity = 0f },
                onDragStopped = { velocity ->
                    val speed = (-velocity / stepPx).coerceIn(-5f, 5f)
                    val target = (position.floatValue + (speed * .13f).coerceIn(-.7f, .7f)).roundToInt()
                    settle(target, speed)
                })) { children, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val cardWidth = min(width * FrontWidth, height * FrontHeight * FrontAspect).roundToInt()
                val cardHeight = (cardWidth / FrontAspect).roundToInt()
                val fixed = Constraints.fixed(cardWidth, cardHeight)
                val cards = children.map { it.measure(fixed) }
                layout(width, height) {
                    cards.forEachIndexed { index, card ->
                        val pose = poses[index].value
                        card.placeWithLayer((width - cardWidth) / 2, (height - cardHeight) / 2,
                            zIndex = 3f - pose.depth) {
                            clip = true
                            translationX = width * pose.x
                            translationY = height * pose.y
                            scaleX = pose.scale
                            scaleY = pose.scale
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
        Box(Modifier.size(24.dp).shadow(2.dp, CircleShape).background(HomeSurface, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(if (next) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft,
                if (next) "下一张" else "上一张", Modifier.size(21.dp), tint = HomeInk)
        }
    }
}

@Composable
private fun CoverFace(cover: Cover, index: Int, position: State<Float>, modifier: Modifier) {
    val art = painterResource(cover.art)
    val fabricColor = remember(cover.tint) {
        // Keep the woven luminance range; avoid amplifying individual channels into speckles.
        ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            .24f, .472f, .088f, 0f, 255f * (cover.tint.red - .32f),
            .24f, .472f, .088f, 0f, 255f * (cover.tint.green - .32f),
            .24f, .472f, .088f, 0f, 255f * (cover.tint.blue - .32f),
            0f, 0f, 0f, 1f, 0f,
        )))
    }
    Box(modifier.fillMaxSize().padding(2.dp)) {
        Image(painterResource(R.drawable.rl_home_paper_board_20261123), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        // All ink lives in this board's coordinate system and inherits its complete 3-D transform.
        Layout(content = {
            Image(art, null, contentScale = ContentScale.Fit)
            BasicText(cover.title, style = TextStyle(fontFamily = EditorialFont.Serif,
                fontSize = 48.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = HomeInk),
                softWrap = false, maxLines = 1)
            BasicText(cover.detail, style = TextStyle(color = HomeInk, fontFamily = EditorialFont.Serif, fontSize = 22.sp,
                fontWeight = FontWeight.Medium), softWrap = false, maxLines = 1)
                Canvas(Modifier.size(200.dp, 16.dp)) {
                    val sw = size.width; val sh = size.height
                    val ink = Path().apply {
                        moveTo(0f, .88f * sh); quadraticTo(.66f * sw, -sh, .59f * sw, .27f * sh)
                        lineTo(.40f * sw, .95f * sh); quadraticTo(.71f * sw, .03f * sh, sw, .62f * sh)
                    }
                    drawPath(ink, HomeInk, style = Stroke(1.dp.toPx()))
                }
            Image(painterResource(R.drawable.rl_home_fabric_corner_20261123), null,
                colorFilter = fabricColor, contentScale = ContentScale.Fit)
        }, modifier = Modifier.matchParentSize()) { children, constraints ->
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val aspect = art.intrinsicSize.width / art.intrinsicSize.height
            val artH = min(h.toFloat(), w / aspect)
            val picture = children[0].measure(Constraints.fixed((artH * aspect).roundToInt(), artH.roundToInt()))
            val title = children[1].measure(Constraints())
            val detail = children[2].measure(Constraints())
            val underline = children[3].measure(Constraints())
            val fabric = children[4].measure(Constraints.fixed(256, 256))
            layout(w, h) {
                val ink = homeCardInkFrame(index, position.value)
                val pose = CoverPose(ink.slot.toFloat())
                val width = w.toFloat()
                val height = h.toFloat()
                val upper = pose.side
                val across = pose.side
                val strip = blend(.57f, .38f, pose.far)
                val sideCenter = if (pose.direction < 0f) .025f + strip / 2f else .975f - strip / 2f
                val labelCenter = width * blend(.744f, sideCenter, across)
                fun androidx.compose.ui.layout.Placeable.fitAt(
                    cx: Float, cy: Float, availableWidth: Float, availableHeight: Float, opacity: Float = ink.opacity
                ) {
                    val fit = min(availableWidth / this.width, availableHeight / this.height)
                    placeWithLayer(0, 0) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = cx - this@fitAt.width * fit / 2f
                        translationY = cy - this@fitAt.height * fit / 2f
                        scaleX = fit
                        scaleY = fit
                        alpha = opacity
                    }
                }
                val pictureTop = blend(.014f, .36f, upper)
                val pictureBottom = blend(.982f, .93f, pose.side)
                val pictureCenter = blend(blend(.268f, .50f + pose.direction * .038f, across), sideCenter, pose.far)
                picture.fitAt(width * pictureCenter, height * (pictureTop + pictureBottom) / 2f,
                    width * blend(.515f, .82f, across), height * (pictureBottom - pictureTop))
                val titleCenterY = blend(.37f, .25f, upper)
                title.fitAt(labelCenter, height * titleCenterY, width * blend(.42f, strip, across),
                    height * blend(.29f, .175f, upper))
                detail.fitAt(labelCenter, height * .565f, width * .432f, height * .14f,
                    if (ink.slot == 0) ink.opacity else 0f)
                underline.fitAt(labelCenter, height * .68f, width * .40f, height * .055f,
                    if (ink.slot == 0) ink.opacity else 0f)
                val fabricSize = width * if (ink.slot == 0) .21f else .12f
                // Keep the physical top-left corner: nearer sheets naturally hide it.
                // Never move an occluded corner into the exposed strip just to make it visible.
                val fabricInsetX = if (ink.slot == 0) 0f else width * .02f
                val fabricInsetY = if (ink.slot == 0) 0f else height * .035f
                fabric.fitAt(fabricInsetX + fabricSize / 2f,
                    fabricInsetY + fabricSize / 2f, fabricSize, fabricSize)
            }
        }
    }
}

@Composable
private fun FooterEntry(label: String, tag: String, art: Int? = null, icon: ImageVector? = null,
                        accent: Color = HomeInk, modifier: Modifier, onClick: () -> Unit) {
    BoxWithConstraints(modifier.fillMaxHeight().padding(vertical = 2.dp)
        .testTag(tag).clickable(interactionSource = remember { MutableInteractionSource() },
            indication = null, role = Role.Button, onClick = onClick)) {
        Image(painterResource(R.drawable.rl_home_paper_board_20261123), null, Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds)
        val iconSize = (maxHeight * .58f).coerceIn(21.dp, 38.dp)
        Row(Modifier.fillMaxSize().padding(horizontal = (maxWidth * .045f).coerceIn(3.dp, 10.dp)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (art != null) {
                Image(painterResource(art), null, Modifier.size(iconSize), contentScale = ContentScale.Fit)
            } else if (icon != null) {
                Icon(icon, null, Modifier.size(iconSize).background(accent, RoundedCornerShape(4.dp)).padding(3.dp), tint = Color.White)
            }
            BasicText(label, Modifier.weight(1f), style = TextStyle(fontFamily = EditorialFont.Serif,
                color = HomeInk, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                autoSize = TextAutoSize.StepBased(9.sp, 20.sp), maxLines = 1)
            Icon(Icons.Outlined.ArrowForward, null, Modifier.size((iconSize * .55f).coerceAtLeast(12.dp)), tint = HomeInk)
        }
    }
}
