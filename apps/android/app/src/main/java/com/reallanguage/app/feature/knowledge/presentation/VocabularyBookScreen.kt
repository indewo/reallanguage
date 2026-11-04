package com.reallanguage.app.feature.knowledge.presentation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reallanguage.app.R
import com.reallanguage.app.core.designsystem.*
import com.reallanguage.app.feature.knowledge.domain.KnowledgeStatus
import com.reallanguage.app.feature.knowledge.domain.VocabularyEntry
import com.reallanguage.app.feature.knowledge.domain.VocabularySenseRecord
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlin.math.abs

private val alphabet = ('A'..'Z').toList()
private val vocabularySenseMinHeight = 42.dp
private val vocabularyStatusHeight = 36.dp
private val vocabularyRailWidth = 58.dp

@Composable
private fun vocabularyCardPainter(): BitmapPainter {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_vocabulary_card_paper_r11)
    return remember(bitmap) { BitmapPainter(bitmap, IntOffset(10, 25), IntSize(1746, 841)) }
}

@Composable
private fun vocabularyPaperPainter() : BitmapPainter {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_vocabulary_search_paper_r11)
    return remember(bitmap) { BitmapPainter(bitmap, IntOffset(8, 294), IntSize(1872, 256)) }
}

@Composable
private fun vocabularyTapePainter() : BitmapPainter {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_vocabulary_terracotta_tape_r11)
    return remember(bitmap) { BitmapPainter(bitmap, IntOffset(88, 210), IntSize(1818, 370)) }
}

@Composable
private fun vocabularyRailPainter(): BitmapPainter {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_vocabulary_alphabet_rail_r11)
    return remember(bitmap) { BitmapPainter(bitmap, IntOffset(350, 45), IntSize(276, 1534)) }
}

@Composable
private fun vocabularyBeigeTapePainter(): BitmapPainter {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_vocabulary_beige_tape_r11)
    return remember(bitmap) { BitmapPainter(bitmap, IntOffset(92, 167), IntSize(1984, 382)) }
}

@Composable
private fun vocabularyUnderlinePainter(): BitmapPainter {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_vocabulary_underline_r11)
    return remember(bitmap) { BitmapPainter(bitmap, IntOffset(60, 318), IntSize(2054, 80)) }
}

@Composable
private fun vocabularyBotanicalPainter(): BitmapPainter {
    val bitmap = ImageBitmap.imageResource(R.drawable.rl_vocabulary_botanical_r11)
    return remember(bitmap) { BitmapPainter(bitmap, IntOffset(150, 15), IntSize(800, 1582)) }
}

@Composable
fun VocabularyBookScreen(state: VocabularyBookUiState, onAction: (VocabularyBookAction) -> Unit) {
    var visibleInitial by remember { mutableStateOf(state.selectedInitial) }
    LaunchedEffect(state.selectedInitial) { visibleInitial = state.selectedInitial }
    RlPaperBackground(showCornerCollage = false) {
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
            .background(Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 1480.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            VocabularyBookHeader(state, onAction)
            VocabularyPlaybackMessage(state, onAction)
            when {
                state.loading -> LoadingRow()
                state.loadFailed -> LoadFailure(onAction)
                state.entries.isEmpty() -> Text(
                    if (state.appliedQuery.isBlank()) stringResource(R.string.vocabulary_book_empty)
                    else stringResource(R.string.vocabulary_book_no_search_result, state.appliedQuery),
                )
                else -> Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AlphabetRail(visibleInitial, { initial ->
                        visibleInitial = initial
                        onAction(VocabularyBookAction.InitialSelected(initial))
                    }, Modifier.fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        // The initial ribbon owns a real row, outside the scrolling
                        // list. It must never float over the first visible word.
                        Box(Modifier.fillMaxWidth()) {
                            Box(Modifier.padding(start = 16.dp).widthIn(min = 82.dp).heightIn(min = 25.dp)
                                .testTag("vocabulary-initial-ribbon"), contentAlignment = Alignment.Center) {
                                Image(painterResource(R.drawable.rl_torn_blue), null, Modifier.matchParentSize(),
                                    contentScale = ContentScale.FillBounds)
                                Text("$visibleInitial 开头", color = Color.White, fontSize = 13.sp, lineHeight = 17.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                            }
                        }
                        VocabularySenseList(
                            state, onAction, { visibleInitial = it },
                            Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun VocabularyBookHeader(state: VocabularyBookUiState, onAction: (VocabularyBookAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 760.dp || LocalDensity.current.fontScale > 1.35f || state.currentScope.length > 16
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        ) {
            IconButton(
                { onAction(VocabularyBookAction.ReturnHomeRequested) },
                Modifier.size(if (compact) 40.dp else 48.dp).testTag("vocabulary-book-back"),
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = RlColor.Ink) }
            Spacer(Modifier.width(if (compact) 2.dp else 18.dp))
            VerticalDivider(Modifier.height(34.dp), color = RlColor.Divider)
            Text(
                stringResource(R.string.vocabulary_book_title), color = RlColor.Ink,
                modifier = Modifier.width(if (compact) 86.dp else 102.dp),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 22.sp else 30.sp,
                ),
            )
            VerticalDivider(Modifier.height(34.dp), color = RlColor.Primary)
            Box(Modifier.width(if (compact) 130.dp else 140.dp).height(34.dp), contentAlignment = Alignment.Center) {
                Image(
                    vocabularyPaperPainter(), null, Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds,
                )
                Text(
                    stringResource(R.string.vocabulary_book_scope, state.currentScope),
                    modifier = Modifier.padding(horizontal = 7.dp), maxLines = 1,
                    color = RlColor.Ink, fontSize = if (compact) 12.sp else 14.sp,
                )
            }
            Spacer(Modifier.width(if (compact) 2.dp else 8.dp))
            VocabularySearchBar(state, onAction, Modifier.weight(1f), compact)
        }
    }
}

@Composable
private fun VocabularySearchBar(
    state: VocabularyBookUiState,
    onAction: (VocabularyBookAction) -> Unit,
    modifier: Modifier,
    compact: Boolean,
) {
    val actionWidth = if (compact) 58.dp else 64.dp
    val controlHeight = 38.dp
    Row(
        modifier.height(controlHeight).testTag("vocabulary-search-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().testTag("vocabulary-search-input"),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                vocabularyPaperPainter(), null,
                Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Search, null, tint = RlColor.TextSecondary, modifier = Modifier.size(20.dp))
                BasicTextField(
                    value = state.query,
                    onValueChange = { onAction(VocabularyBookAction.QueryChanged(it)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = RlColor.Ink, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onAction(VocabularyBookAction.SearchRequested) }),
                    decorationBox = { inner ->
                        if (state.query.isEmpty()) Text(
                            stringResource(R.string.vocabulary_book_search_hint),
                            color = RlColor.TextSecondary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.testTag("vocabulary-search-hint"),
                        )
                        inner()
                    },
                )
            }
        }
        Box(
            Modifier.width(actionWidth).fillMaxHeight()
                .testTag("vocabulary-search-clear")
                .clickable(
                    enabled = state.query.isNotEmpty() || state.appliedQuery.isNotEmpty(),
                    onClick = { onAction(VocabularyBookAction.ClearSearchRequested) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.vocabulary_book_search_clear),
                color = if (state.query.isNotEmpty() || state.appliedQuery.isNotEmpty())
                    RlColor.TextSecondary else RlColor.TextSecondary.copy(alpha = .38f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Box(
            Modifier.width(actionWidth).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                .background(RlColor.Secondary)
                .clickable(
                    enabled = !state.loading && !state.loadingMore,
                    onClick = { onAction(VocabularyBookAction.SearchRequested) },
                )
                .testTag("vocabulary-search-submit"),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.rl_olive_torn_paper), null,
                Modifier.matchParentSize(),
                contentScale = ContentScale.FillBounds,
                alpha = .18f,
            )
            Text(
                stringResource(R.string.vocabulary_book_search_action),
                color = RlColor.OnSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.zIndex(1f),
            )
        }
    }
}

@Composable
private fun AlphabetRail(
    selectedInitial: Char,
    onInitialSelected: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var heightPx by remember { mutableIntStateOf(1) }
    var railTop by remember { mutableFloatStateOf(0f) }
    val letterBounds = remember { mutableMapOf<Int, Rect>() }
    var previewIndex by remember(selectedInitial) {
        mutableIntStateOf(alphabet.indexOf(selectedInitial).coerceAtLeast(0))
    }
    val selectAtY: (Float) -> Unit = { y ->
        val absoluteY = y + railTop
        val nextIndex = letterBounds.minByOrNull { (_, bounds) ->
            when { absoluteY < bounds.top -> bounds.top - absoluteY
                absoluteY > bounds.bottom -> absoluteY - bounds.bottom; else -> 0f }
        }?.key ?: previewIndex
        if (nextIndex != previewIndex) {
            previewIndex = nextIndex
        }
    }
    val interactionModifier = Modifier.pointerInput(heightPx) {
        awaitEachGesture {
            var startY: Float? = null
            var releaseY = 0f
            var dragging = false
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: break
                releaseY = change.position.y
                val origin = startY ?: change.position.y.also { startY = it }
                if (!dragging && abs(change.position.y - origin) > viewConfiguration.touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    selectAtY(change.position.y)
                    change.consume()
                }
                pressed = change.pressed
            }
            // A short tap belongs to the letter itself. Only a real drag is
            // committed here, otherwise the parent and child can disagree
            // after the Dock-style weights animate under the pointer.
            if (dragging) {
                selectAtY(releaseY)
                onInitialSelected(alphabet[previewIndex])
            }
        }
    }
    val railHeightDp = with(density) { heightPx.toDp().value }
    val baseSlotDp = ((railHeightDp - 28f) / 30.3f).coerceIn(5.2f, 10f)
    Box(modifier.width(vocabularyRailWidth).zIndex(2f)) {
    Box(
        Modifier.fillMaxSize().onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
            .onGloballyPositioned { railTop = it.boundsInRoot().top }
            .then(interactionModifier).testTag("vocabulary-alphabet-rail"),
    ) {
        Image(
            vocabularyRailPainter(), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds,
        )
        Column(
            Modifier.fillMaxSize().padding(vertical = 14.dp).testTag("vocabulary-alphabet-content"),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            alphabet.forEachIndexed { index, letter ->
                val distance = abs(index - previewIndex)
                val dockWeight = when (distance) { 0 -> 4.4f; 1 -> 1.2f; 2 -> 1.05f; else -> 1f }
                AlphabetLetter(letter, index, previewIndex, baseSlotDp, Modifier.weight(dockWeight)
                    .onGloballyPositioned { coordinates ->
                        letterBounds[index] = coordinates.boundsInRoot()
                    }) {
                    previewIndex = index
                    onInitialSelected(letter)
                }
            }
        }
    }
        Image(
            vocabularyBotanicalPainter(), null,
            Modifier.align(Alignment.BottomStart).offset(x = 40.dp, y = (-15).dp)
                .width(44.dp).height(88.dp).zIndex(3f).testTag("vocabulary-botanical"),
        )
    }
}

@Composable
private fun AlphabetLetter(
    letter: Char,
    index: Int,
    selectedIndex: Int,
    baseSlotDp: Float,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val distance = abs(index - selectedIndex)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val targetScale = when (distance) { 0 -> 1f; 1 -> 1.14f; 2 -> 1.05f; else -> 1f }
    val scale by animateFloatAsState(targetScale, tween(120), label = "alphabetDockScale")
    Box(
        modifier.fillMaxWidth().testTag("vocabulary-initial-$letter"),
        contentAlignment = Alignment.Center,
    ) {
        val selectedSize = (baseSlotDp * 4.1f).coerceIn(25f, 38f).dp
        Box(
            modifier = (if (distance == 0) Modifier.size(selectedSize) else Modifier.fillMaxSize())
                .testTag("vocabulary-initial-glyph-$letter")
                .then(if (distance == 0) Modifier
                    .shadow(4.dp, CircleShape)
                    .background(RlColor.PaperWarm, CircleShape)
                    .border(1.dp, RlColor.Primary, CircleShape) else Modifier)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "$letter", color = if (distance == 0) RlColor.Ink else RlColor.OnSecondary,
                    fontWeight = if (distance <= 1) FontWeight.Bold else FontWeight.Medium,
                    fontSize = ((if (distance == 0) (baseSlotDp * 1.9f).coerceIn(11f, 17f)
                        else (baseSlotDp * .92f).coerceIn(5f, 9.5f)) / fontScale).sp,
                    lineHeight = ((if (distance == 0) (baseSlotDp * 2.1f).coerceIn(12f, 19f)
                        else (baseSlotDp * 1.02f).coerceIn(5.5f, 10.5f)) / fontScale).sp,
                    style = MaterialTheme.typography.bodySmall.copy(
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                    modifier = Modifier.testTag("vocabulary-initial-text-$letter").then(
                        if (distance in 1..2) Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        } else Modifier,
                    ),
                )
            }
        }
    }
}

@Composable
private fun VocabularySenseList(
    state: VocabularyBookUiState,
    onAction: (VocabularyBookAction) -> Unit,
    onVisibleInitialChanged: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.entries) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .mapNotNull { state.entries.getOrNull(it)?.englishForm?.firstOrNull()?.uppercaseChar() }
            .distinctUntilChanged().collect(onVisibleInitialChanged)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.testTag("vocabulary-sense-list"),
        contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(state.entries, key = { _, entry -> entry.englishForm.lowercase() }) { index, entry ->
            if (index == state.entries.lastIndex && state.hasMore && !state.loadingMore) {
                LaunchedEffect(state.entries.size, state.appliedQuery, state.selectedInitial) {
                    onAction(VocabularyBookAction.LoadMoreRequested)
                }
            }
            VocabularyEntryGroup(
                entry,
                state,
                onAction,
                longestHeadword = state.longestHeadword,
                showPaperclip = index % 3 == 2,
            )
        }
        if (state.loadingMore) item(key = "vocabulary-loading-more") { LoadingMoreRow() }
    }
}

@Composable
private fun VocabularyEntryGroup(
    entry: VocabularyEntry,
    state: VocabularyBookUiState,
    onAction: (VocabularyBookAction) -> Unit,
    longestHeadword: String,
    showPaperclip: Boolean = false,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val stacked = maxWidth < 520.dp || LocalDensity.current.fontScale > 1.35f
    val selected = state.expandedSense?.takeIf { record -> entry.senses.any { it.senseId == record.senseId } }
    val entryRequester = remember { BringIntoViewRequester() }
    var entrySize by remember(entry.englishForm) { mutableStateOf(IntSize.Zero) }
    val anchorHeight = with(LocalDensity.current) { 120.dp.toPx() }
    LaunchedEffect(selected?.senseId, entrySize.width) {
        if (selected != null && entrySize.width > 0) {
            // Reveal the selected word's identity first. A long detail expands below it
            // and is read with the outer list, rather than scrolling its word out of view.
            entryRequester.bringIntoView(Rect(0f, 0f, entrySize.width.toFloat(), anchorHeight.coerceAtMost(entrySize.height.toFloat())))
        }
    }
    Column(
        Modifier.bringIntoViewRequester(entryRequester).onSizeChanged { entrySize = it }
            .testTag("vocabulary-entry-${entry.englishForm.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (stacked) {
            // Large accessibility text gets the full width, not a shrunken font
            // or a mid-word break in the narrow left-hand column.
            VocabularyWordCell(entry, state.loading, onAction,
                Modifier.fillMaxWidth().height(IntrinsicSize.Min), longestHeadword, compactIdentity = true)
            VocabularyMeaningCell(entry, state, onAction,
                Modifier.fillMaxWidth().height(IntrinsicSize.Min), showPaperclip)
        } else Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 98.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            val wordWeight = headwordColumnWeight(longestHeadword.ifBlank { entry.englishForm })
            VocabularyWordCell(
                entry,
                state.loading,
                onAction,
                Modifier.weight(wordWeight).fillMaxHeight(),
                longestHeadword,
            )
            VocabularyMeaningCell(
                entry,
                state,
                onAction,
                Modifier.weight(2f - wordWeight).fillMaxHeight(),
                showPaperclip,
            )
        }
        selected?.let { InlineVocabularyDetail(it, state.loading, onAction) }
    }
    }
}

@Composable
private fun VocabularyWordCell(
    entry: VocabularyEntry,
    loading: Boolean,
    onAction: (VocabularyBookAction) -> Unit,
    modifier: Modifier,
    longestHeadword: String,
    compactIdentity: Boolean = false,
) {
    VocabularyCardPaper(modifier.testTag("vocabulary-word-${entry.englishForm.lowercase()}"), tape = true) {
      var cellWidthPx by remember(entry.englishForm, longestHeadword) { mutableIntStateOf(0) }
      val density = LocalDensity.current
      Box(Modifier.fillMaxSize().onSizeChanged { cellWidthPx = it.width }) {
        val pronunciation = entry.sharedPronunciationSense()
        val cellWidth = with(density) { cellWidthPx.toDp() }
        val availableHeadwordWidth = (cellWidth - 50.dp - if (pronunciation == null) 0.dp else 56.dp)
            .coerceAtLeast(48.dp)
        val headwordSize = fittedHeadwordSize(
            headword = entry.englishForm,
            poolReference = longestHeadword,
            availableWidth = availableHeadwordWidth,
            maximumSize = if (compactIdentity) 32.sp else 40.sp,
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 25.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                entry.englishForm, color = RlColor.Ink,
                modifier = Modifier.weight(1f).testTag("vocabulary-word-label-${entry.englishForm.lowercase()}"),
                fontFamily = EditorialFont.Serif, fontWeight = FontWeight.Bold,
                fontSize = headwordSize,
                lineHeight = (headwordSize.value * 1.08f).sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
                pronunciation?.let { sense ->
                    VocabularyPronunciationButton(sense, loading, onAction)
                }
            }
            entry.sharedPhonetic()?.let {
                Text(it, color = RlColor.Ink, fontSize = 15.sp, lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().testTag("vocabulary-phonetic-${entry.englishForm.lowercase()}"))
            }
            if (entry.senses.size <= 2 || compactIdentity) Image(vocabularyUnderlinePainter(), null,
                Modifier.width(86.dp).height(3.dp), contentScale = ContentScale.FillBounds)
            if (entry.senses.size > 2 && !compactIdentity) {
                // Decoration participates in measurement: a long headword or
                // phonetic can never run beneath an absolutely positioned footer.
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Image(vocabularyUnderlinePainter(), null, Modifier.width(86.dp).height(5.dp),
                        contentScale = ContentScale.FillBounds,
                        colorFilter = ColorFilter.tint(RlColor.NoteAccent))
                    Spacer(Modifier.weight(1f))
                    Text("Words build\na bigger you.", color = RlColor.TextSecondary.copy(alpha = .65f),
                        fontFamily = FontFamily.Cursive, fontStyle = FontStyle.Italic,
                        fontSize = 11.sp, lineHeight = 13.sp)
                }
            }
        }
      }
    }
}

internal fun headwordColumnWeight(poolReference: String): Float = when {
    poolReference.length >= 22 -> 1.16f
    poolReference.length >= 18 -> 1.08f
    poolReference.length >= 14 -> .94f
    poolReference.length >= 11 -> .86f
    else -> .76f
}

@Composable
private fun fittedHeadwordSize(
    headword: String,
    poolReference: String,
    availableWidth: Dp,
    maximumSize: TextUnit,
): TextUnit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val availableWidthPx = with(density) { availableWidth.toPx() }.coerceAtLeast(1f)
    val benchmark = listOf(poolReference.trim(), headword.trim()).filter(String::isNotEmpty)
    val family = EditorialFont.Serif
    return remember(benchmark, availableWidthPx, maximumSize, density.fontScale) {
        (maximumSize.value.toInt() downTo 12).firstOrNull { size ->
            benchmark.all { text ->
                measurer.measure(
                    text = text,
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = family,
                        fontWeight = FontWeight.Bold,
                        fontSize = size.sp,
                    ),
                    softWrap = false,
                    maxLines = 1,
                ).size.width <= availableWidthPx
            }
        }?.sp ?: 12.sp
    }
}

/** A vocabulary paper has no baked-in tape, spine or padding. All layers are
 * measured with their owner, so rows can grow without moving decoration into text. */
@Composable
private fun VocabularyCardPaper(
    modifier: Modifier,
    tape: Boolean = false,
    spine: Boolean = false,
    paperclip: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        Image(vocabularyCardPainter(), null,
            Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        if (spine) Box(Modifier.matchParentSize()) {
            Image(painterResource(R.drawable.rl_torn_blue), null,
                Modifier.width(10.dp).fillMaxHeight().padding(vertical = 5.dp),
                contentScale = ContentScale.Crop)
            Image(vocabularyBeigeTapePainter(), null,
                Modifier.align(Alignment.TopEnd).padding(end = 12.dp).offset(y = (-7).dp)
                    .width(56.dp).height(17.dp).graphicsLayer { rotationZ = 12f },
                contentScale = ContentScale.FillBounds)
        }
        if (tape) Image(vocabularyTapePainter(), null,
            Modifier.align(Alignment.TopEnd).padding(end = 26.dp).offset(y = (-6).dp)
                .width(72.dp).height(20.dp).graphicsLayer { rotationZ = 3f }, contentScale = ContentScale.FillBounds)
        content()
        if (paperclip) Image(painterResource(R.drawable.rl_home_scene_paperclip), null,
            Modifier.align(Alignment.TopEnd).padding(end = 4.dp).offset(y = (-4).dp)
                .width(12.dp).height(29.dp).graphicsLayer { rotationZ = 22f },
            contentScale = ContentScale.Fit)
    }
}

@Composable
private fun VocabularyMeaningCell(
    entry: VocabularyEntry,
    state: VocabularyBookUiState,
    onAction: (VocabularyBookAction) -> Unit,
    modifier: Modifier,
    showPaperclip: Boolean = false,
) {
    VocabularyCardPaper(modifier.testTag("vocabulary-meaning-${entry.englishForm.lowercase()}"),
        spine = true, paperclip = showPaperclip) {
      Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 18.dp, top = 10.dp, bottom = 12.dp),
          verticalArrangement = Arrangement.Center) {
        val sharedPhonetic = entry.sharedPhonetic()
        entry.senses.forEachIndexed { index, sense ->
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = vocabularySenseMinHeight)
                    .clickable { onAction(VocabularyBookAction.SenseSelected(sense.senseId)) }
                    .testTag("vocabulary-sense-${sense.senseId}"),
                contentAlignment = Alignment.Center,
            ) { Row(
                Modifier.fillMaxWidth().padding(horizontal = 0.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}", color = RlColor.Primary, fontFamily = RlFont.Brand,
                    fontSize = 17.sp, modifier = Modifier.width(24.dp),
                )
                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        sense.partOfSpeech.takeIf(String::isNotBlank)?.let { PartOfSpeechChip(it, index) }
                        Text(
                            sense.meaningZh, color = RlColor.Ink, fontSize = 16.sp, lineHeight = 21.sp,
                            modifier = Modifier.weight(1f).testTag("vocabulary-meaning-text-${sense.senseId}"),
                        )
                    }
                    if (sharedPhonetic == null) Row(verticalAlignment = Alignment.CenterVertically) {
                        sense.phonetic?.takeIf(String::isNotBlank)?.let {
                            Text(it, color = RlColor.TextSecondary, fontSize = 12.sp,
                                modifier = Modifier.weight(1f))
                        }
                        if (entry.sharedPronunciationSense() == null && !sense.pronunciationText.isNullOrBlank()) {
                            VocabularyPronunciationButton(sense, state.loading, onAction,
                                Modifier.testTag("vocabulary-sense-pronunciation-${sense.senseId}"))
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                VocabularyStatusSeal(sense.status, sense.effectiveLearnedCount)
            } }
            if (index != entry.senses.lastIndex) HorizontalDivider(color = RlColor.Divider)
        }
      }
    }
}

@Composable
private fun VocabularyStatusSeal(status: KnowledgeStatus, count: Int) {
    val (icon, tint, container) = when (status) {
        KnowledgeStatus.NOT_STARTED -> Triple(Icons.Outlined.BookmarkBorder, Color(0xFF6C5746), Color(0xFFF2E9DD))
        KnowledgeStatus.LEARNING -> Triple(Icons.Outlined.DonutLarge, Color(0xFF2F72A8), Color(0xFFDCE9F4))
        KnowledgeStatus.TENTATIVE -> Triple(Icons.Outlined.StarOutline, RlColor.NoteAccent, RlColor.NoteContainer)
        KnowledgeStatus.MASTERED -> Triple(Icons.Outlined.DoneAll, RlColor.Secondary, RlColor.SecondaryContainer)
    }
    Box(
        modifier = Modifier.widthIn(min = 108.dp).heightIn(min = vocabularyStatusHeight)
            .testTag("vocabulary-status-${status.name.lowercase()}"),
        contentAlignment = Alignment.Center,
    ) {
        Image(vocabularyPaperPainter(), null, Modifier.matchParentSize(),
            contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.tint(container, BlendMode.Modulate))
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Surface(shape = CircleShape, color = Color.Transparent, border = BorderStroke(1.dp, tint)) {
                Icon(icon, null, tint = tint, modifier = Modifier.padding(3.dp).size(16.dp))
            }
            Text(status.display(count), color = tint, fontWeight = FontWeight.Medium, fontSize = 12.sp,
                lineHeight = 16.sp)
        }
    }
}

@Composable
private fun PartOfSpeechChip(label: String, index: Int) {
    val colors = listOf(RlColor.PrimaryContainerStrong, Color(0xFFDCE9E1), Color(0xFFF0DDAE), Color(0xFFF1C7B9))
    Surface(color = colors[index % colors.size], shape = RoundedCornerShape(5.dp)) {
        Text(
            label, Modifier.padding(horizontal = 9.dp, vertical = 3.dp), color = RlColor.Ink,
            fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 16.sp,
        )
    }
}

@Composable
private fun VocabularyPronunciationButton(
    sense: VocabularySenseRecord,
    loading: Boolean,
    onAction: (VocabularyBookAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = { onAction(VocabularyBookAction.SensePronunciationRequested(sense.senseId)) },
        enabled = !loading,
        modifier = modifier.size(48.dp).testTag("vocabulary-group-pronunciation-${sense.senseId}")
            .semantics { contentDescription = "播放 ${sense.englishForm} 的美式发音" },
    ) {
        Box(Modifier.size(34.dp).shadow(1.dp, CircleShape)
            .background(Color(0xFF2F72A8), CircleShape).border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.VolumeUp, null, tint = Color.White, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun InlineVocabularyDetail(
    sense: VocabularySenseRecord,
    loading: Boolean,
    onAction: (VocabularyBookAction) -> Unit,
) {
    VocabularyCardPaper(
        Modifier.fillMaxWidth().testTag("vocabulary-detail-sheet"),
    ) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)) {
        VocabularySenseDetails(sense, loading, onAction)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                { onAction(VocabularyBookAction.CollapseRequested) },
                Modifier.testTag("vocabulary-detail-collapse"),
            ) { Text(stringResource(R.string.vocabulary_detail_collapse)) }
        }
      }
    }
}

@Composable
private fun VocabularySenseDetails(
    sense: VocabularySenseRecord,
    loading: Boolean,
    onAction: (VocabularyBookAction) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("vocabulary-detail-content")) {
        val stacked = maxWidth < 650.dp || LocalDensity.current.fontScale > 1.35f
        val scene: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("代表场景", color = RlColor.TextSecondary, fontWeight = FontWeight.Bold)
                Image(painterResource(R.drawable.rl_vocabulary_cafe_scene), null,
                    Modifier.fillMaxWidth().height(88.dp), contentScale = ContentScale.Crop)
                Text(sense.representativeScene ?: "暂无场景记录", color = RlColor.Ink,
                    fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        val examples: @Composable () -> Unit = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("重点例句", color = RlColor.TextSecondary, fontWeight = FontWeight.Bold)
            sense.examples.forEachIndexed { index, example ->
                Row(verticalAlignment = Alignment.Top) {
                    IconButton(
                        { onAction(VocabularyBookAction.ExamplePronunciationRequested(sense.senseId, index)) },
                        Modifier.size(40.dp).testTag("vocabulary-example-${sense.senseId}-$index"),
                        enabled = !loading && example.pronunciationText.isNotBlank(),
                    ) { Icon(Icons.Outlined.VolumeUp, null, tint = RlColor.NoteAccent, modifier = Modifier.size(18.dp)) }
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Text(example.textEn, fontFamily = RlFont.Brand, fontSize = 15.sp, lineHeight = 21.sp)
                        Text(example.textZh, color = RlColor.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                    }
                }
                if (index != sense.examples.lastIndex) HorizontalDivider(color = RlColor.Divider)
            }
          }
        }
        val statistics: @Composable () -> Unit = {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("学习统计", color = RlColor.TextSecondary, fontWeight = FontWeight.Bold)
            StatisticNote("重点句出现 ${sense.focusOccurrenceCount} 次", RlColor.ActionVividContainer, RlColor.ActionVivid)
            StatisticNote("非重点句出现 ${sense.contextOccurrenceCount} 次", RlColor.SecondaryContainer, RlColor.Secondary)
          }
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            scene(); examples(); statistics()
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.weight(.75f)) { scene() }
            Box(Modifier.weight(1.35f)) { examples() }
            Box(Modifier.weight(.65f)) { statistics() }
        }
    }
}

@Composable
private fun StatisticNote(text: String, background: Color, foreground: Color) {
    Surface(color = background.copy(alpha = .35f), shape = RlShape.Small, border = BorderStroke(1.dp, foreground.copy(alpha = .65f))) {
        Text(text, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 15.dp), color = foreground,
            fontFamily = EditorialFont.Serif, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun VocabularyPlaybackMessage(state: VocabularyBookUiState, onAction: (VocabularyBookAction) -> Unit) {
    if (state.pronunciationBusy) Text(
        stringResource(R.string.vocabulary_playback_busy), color = RlColor.TextSecondary,
        modifier = Modifier.testTag("vocabulary-pronunciation-busy"),
    )
    if (state.pronunciationFailed) Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.vocabulary_playback_failed), color = RlColor.Error,
            modifier = Modifier.testTag("vocabulary-pronunciation-failed"),
        )
        TextButton(
            { onAction(VocabularyBookAction.RetryPronunciationRequested) },
            Modifier.testTag("vocabulary-pronunciation-retry"),
        ) { Text(stringResource(R.string.vocabulary_playback_retry), color = RlColor.Primary) }
    }
}

@Composable
private fun LoadingRow() = Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    CircularProgressIndicator(Modifier.size(24.dp), color = RlColor.Primary, strokeWidth = 2.dp)
    Text(stringResource(R.string.vocabulary_book_loading), color = RlColor.TextSecondary)
}

@Composable
private fun LoadingMoreRow() = Row(
    Modifier.fillMaxWidth().padding(12.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
) {
    CircularProgressIndicator(Modifier.size(22.dp), color = RlColor.Primary, strokeWidth = 2.dp)
    Text(stringResource(R.string.vocabulary_book_loading_more), Modifier.padding(start = 8.dp), color = RlColor.TextSecondary)
}

@Composable
private fun LoadFailure(onAction: (VocabularyBookAction) -> Unit) = Column(
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    Text(stringResource(R.string.vocabulary_book_load_failed), color = RlColor.Error)
    RlPrimaryButton(stringResource(R.string.diary_retry), true, { onAction(VocabularyBookAction.RetryRequested) })
}

private fun VocabularyEntry.sharedPhonetic(): String? =
    senses.map { it.phonetic?.takeIf(String::isNotBlank) }.distinct().singleOrNull()

private fun VocabularyEntry.sharedPronunciationSense() = senses.firstOrNull()?.takeIf { first ->
    !first.pronunciationText.isNullOrBlank() &&
        senses.all { it.pronunciationText == first.pronunciationText && it.phonetic == first.phonetic }
}

private fun KnowledgeStatus.display(count: Int): String = when (this) {
    KnowledgeStatus.NOT_STARTED -> "待学习"
    KnowledgeStatus.LEARNING -> "学习中"
    KnowledgeStatus.TENTATIVE -> "待验证"
    KnowledgeStatus.MASTERED -> "已学会 $count 次"
}
