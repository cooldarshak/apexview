package app.apexview


import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Short and wide, to sit in the strip of space under or between the feeds: two clips tall. */
private val PANEL_MAX_WIDTH = 560.dp
private val ROW_HEIGHT = 50.dp
private val HEADER_HEIGHT = 36.dp
private val PANEL_MAX_HEIGHT = HEADER_HEIGHT + ROW_HEIGHT * 2 + 4.dp
/** The smallest the panel can be made: its title and one clip, and narrow enough to tuck aside. */
private val PANEL_MIN_WIDTH = 220.dp
private val PANEL_MIN_HEIGHT = HEADER_HEIGHT + ROW_HEIGHT + 4.dp
/** Edges this close to another edge (the screen's, a feed's, the hinge) pull onto it, as feeds do. */
private val SNAP = 12.dp

/**
 * The edges the panel's edges pull toward: the screen's, the feeds' (where they are on screen,
 * shrunk under the controls by [feedScale]) and the hinge in laptop mode.
 */
private fun snapLines(app: AppState, width: Float, height: Float, feedScale: Float): Pair<List<Float>, List<Float>> {
    val feeds = app.tiles.map { t ->
        onScreen(androidx.compose.ui.geometry.Rect(t.x, t.y, t.x + t.w, t.y + t.h), width, height, feedScale)
    }.filter { it.left < width && it.right > 0f }
    val xs = listOf(0f, width) + feeds.flatMap { listOf(it.left, it.right) }
    val ys = listOf(0f, height) + feeds.flatMap { listOf(it.top, it.bottom) } +
        (app.hinge?.takeIf { app.laptopActive }?.toList() ?: emptyList())
    return xs to ys
}

/** How far to move so the nearest of [edges] lands on a line within [reach]; 0 if none is close. */
private fun pull(edges: List<Float>, lines: List<Float>, reach: Float): Float {
    var best = 0f
    var bestDistance = reach
    for (e in edges) for (l in lines) {
        val d = l - e
        if (kotlin.math.abs(d) <= bestDistance) {
            bestDistance = kotlin.math.abs(d)
            best = d
        }
    }
    return best
}
/** A clip this new (by the picture's clock) flashes once as it arrives. */
private const val FRESH_MS = 3_000L

/**
 * Team radio as it comes in, oldest at the top and the newest at the bottom, where the list
 * follows it; each clip is marked with the lap the driver was on. Follows
 * the picture like the map does, so nothing shows before it's happened on screen. Tap a clip to
 * hear it over the feeds; drag the title to move the panel and the corner to resize it. With
 * Snap on (Layout), its edges pull onto the screen's, the feeds' and the hinge, as the feeds do.
 */
@Composable
fun RadioPanel(app: AppState, timing: SessionTiming, width: Float, height: Float, feedScale: Float) {
    // Twice a second is plenty for a list.
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            now = app.mapTimeMs() ?: 0L
            delay(500)
        }
    }
    // Transcribe while the panel is up.
    LaunchedEffect(Unit) { app.transcribeRadio() }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) app.retryTranscripts() }
    val heard = timing.radio.count { it.utc <= now }
    val clips = remember(timing, heard) { timing.radio.take(heard) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // A new clip brings the list down to it.
    LaunchedEffect(clips.size) { if (clips.isNotEmpty()) list.animateScrollToItem(clips.lastIndex) }

    FloatingPanel(app, app.radioSpot, "Team radio", "Hide team radio", app::toggleRadio, width, height, feedScale, Modifier.helpAnchor(app, "radio")) {
        app.transcriptBlocked?.let { note ->
            Text(
                "$note Tap to allow.",
                style = Type.caption,
                modifier = Modifier
                    .pressable { ask.launch(android.Manifest.permission.RECORD_AUDIO) }
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        if (clips.isEmpty()) {
            Text(
                if (timing.radio.isEmpty()) "No radio in this session yet." else "Radio shows up here as it happens.",
                style = Type.footnote,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            )
            return@FloatingPanel
        }
        LazyColumn(Modifier.padding(bottom = 4.dp), state = list) {
            items(clips, key = { it.path }) { clip -> RadioRow(app, timing, clip, fresh = now - clip.utc < FRESH_MS) }
        }
    }
}

@Composable
private fun RadioRow(app: AppState, timing: SessionTiming, clip: RadioClip, fresh: Boolean) {
    val driver = timing.drivers[clip.number]
    val playing = app.radioPlaying?.path == clip.path
    val lap = timing.laps[clip.number]?.lapAt(clip.utc)
    val text = app.transcripts[clip.path]
    // Arriving clips flash once, softly.
    val flash = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(clip.path) {
        if (fresh) {
            flash.animateTo(1f, androidx.compose.animation.core.tween(Motion.FADE_MS, easing = EaseOut))
            flash.animateTo(0f, androidx.compose.animation.core.tween(Motion.SCREEN_MS * 2, easing = EaseOut))
        }
    }
    // One line per clip: who and when on the left, what was said beside it.
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .pressable(scaleTo = 0.99f) { app.playRadio(clip) }
            .background(Color.White.copy(alpha = if (playing) 0.1f else 0.18f * flash.value))
            .padding(start = 16.dp, end = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(driver?.color?.let { Color(it) } ?: Color.White)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(54.dp)) {
            Text(driver?.tla ?: clip.number, style = Type.subhead.copy(color = Ink.primary))
            Text(lap?.let { "Lap $it" } ?: "", style = Type.caption)
        }
        Text(
            when {
                text != null && text != AppState.NO_WORDS -> "\u201C$text\u201D"
                app.transcribing == clip.path -> "Transcribing…"
                else -> ""
            },
            style = Type.footnote.copy(color = if (text != null) Ink.primary else Ink.secondary),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Icon(
            if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            if (playing) "Stop" else "Play",
            tint = Ink.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * A panel over the feeds that stays where it's put: first in the biggest space the feeds leave,
 * then wherever its title is dragged, at whatever size its corner is dragged to. With Snap on
 * (Layout), its edges pull onto the screen's, the feeds' and the hinge, as the feeds do.
 */
@Composable
internal fun FloatingPanel(
    app: AppState,
    spot: FloatingSpot,
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
    width: Float,
    height: Float,
    feedScale: Float,
    modifier: Modifier = Modifier,
    /** Its size until it's resized by hand. */
    maxWidth: androidx.compose.ui.unit.Dp = PANEL_MAX_WIDTH,
    maxHeight: androidx.compose.ui.unit.Dp = PANEL_MAX_HEIGHT,
    minWidth: androidx.compose.ui.unit.Dp = PANEL_MIN_WIDTH,
    minHeight: androidx.compose.ui.unit.Dp = PANEL_MIN_HEIGHT,
    /** Optional reserved area that contains the complete panel and its resize gestures. */
    bounds: Area? = null,
    /** Replaces the tall title bar with a slim move/resize strip, used by the timing tower. */
    compactHeader: Boolean = false,
    /** Opacity of the complete panel, including its contents. */
    opacity: Float = 1f,
    /** When locked, its header and resize grip no longer change the panel's place or size. */
    locked: Boolean = false,
    /** Called after a user finishes resizing, so any reserved feed space can be recalculated. */
    onResizeFinished: () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val panelWidth = with(density) {
        minOf(maxWidth, bounds?.let { it.w.toDp() } ?: (width.toDp() - 32.dp))
    }
    val panelHeight = with(density) { minOf(maxHeight, bounds?.let { it.h.toDp() } ?: maxHeight) }
    val effectiveMinWidth = minWidth.coerceAtMost(panelWidth)
    val effectiveMinHeight = minHeight.coerceAtMost(panelHeight)
    if (spot.x < 0f) {
        with(density) {
            val w = panelWidth.toPx()
            val h = panelHeight.toPx()
            val saved = spot.saved()
            if (saved != null && saved.size >= 4 && saved[2] > 0f && saved[3] > 0f) {
                spot.w = saved[2] * width
                spot.h = saved[3] * height
            }
            val gap = if (bounds == null) Layouts.largestFree(app.tileSlots(), width, height)?.takeIf { it.h >= h } else null
            when {
                saved != null -> {
                    spot.x = saved[0] * width
                    spot.y = saved[1] * height
                }
                bounds != null -> {
                    spot.x = bounds.x + (bounds.w - w).coerceAtLeast(0f) / 2f
                    spot.y = bounds.y + (bounds.h - h).coerceAtLeast(0f) / 2f
                }
                gap != null -> {
                    spot.x = gap.x + (gap.w - w).coerceAtLeast(0f) / 2
                    spot.y = gap.y + (gap.h - h) / 2
                }
                else -> {
                    spot.x = (width - w) / 2
                    spot.y = height - h - 120.dp.toPx()
                }
            }
            val minX = bounds?.x ?: 0f
            val minY = bounds?.y ?: 0f
            val maxX = bounds?.let { it.x + it.w - w } ?: (width - w)
            val maxY = bounds?.let { it.y + it.h - h } ?: (height - h)
            spot.x = spot.x.coerceIn(minX, maxX.coerceAtLeast(minX))
            spot.y = spot.y.coerceIn(minY, maxY.coerceAtLeast(minY))
        }
    }
    val shownWidth = if (spot.w > 0f) with(density) {
        spot.w.toDp().coerceIn(effectiveMinWidth, panelWidth)
    } else panelWidth
    val shownHeight = if (spot.h > 0f) with(density) {
        spot.h.toDp().coerceIn(effectiveMinHeight, panelHeight)
    } else panelHeight
    val shownWidthPx = with(density) { shownWidth.toPx() }
    val shownHeightPx = with(density) { shownHeight.toPx() }
    if (spot.w > 0f) spot.w = shownWidthPx
    if (spot.h > 0f) spot.h = shownHeightPx
    val minX = bounds?.x ?: 0f
    val minY = bounds?.y ?: 0f
    spot.x = spot.x.coerceIn(minX, (bounds?.let { it.x + it.w - shownWidthPx } ?: (width - shownWidthPx)).coerceAtLeast(minX))
    spot.y = spot.y.coerceIn(minY, (bounds?.let { it.y + it.h - shownHeightPx } ?: (height - shownHeightPx)).coerceAtLeast(minY))
    val minWidthPx = with(density) { effectiveMinWidth.toPx() }
    // Where the finger has taken the panel (or its corner) since the gesture began, before
    // snapping: snapping works from this, so an edge can be pulled back off a line.
    var rawX by remember { mutableFloatStateOf(0f) }
    var rawY by remember { mutableFloatStateOf(0f) }
    var rawW by remember { mutableFloatStateOf(0f) }
    var rawH by remember { mutableFloatStateOf(0f) }
    var boxW by remember { mutableFloatStateOf(0f) }
    var boxH by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .offset { IntOffset(spot.x.roundToInt(), spot.y.roundToInt()) }
            .then(modifier)
            .graphicsLayer { alpha = opacity.coerceIn(0f, 1f) }
            .onSizeChanged { boxW = it.width.toFloat(); boxH = it.height.toFloat() }
    ) {
        Column(
            Modifier
                .width(shownWidth)
                .height(shownHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.72f))
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (locked) Modifier else Modifier.pointerInput(width, height, feedScale, bounds) {
                            detectDragGestures(
                                onDragStart = { rawX = spot.x; rawY = spot.y },
                                onDragEnd = { spot.store(width, height) },
                            ) { change, drag ->
                                change.consume()
                                rawX += drag.x
                                rawY += drag.y
                                var x = rawX
                                var y = rawY
                                if (app.snapMode) {
                                    val (xs, ys) = snapLines(app, width, height, feedScale)
                                    x += pull(listOf(x, x + boxW), xs, SNAP.toPx())
                                    y += pull(listOf(y, y + boxH), ys, SNAP.toPx())
                                }
                                val minX = bounds?.x ?: 0f
                                val minY = bounds?.y ?: 0f
                                val maxX = bounds?.let { it.x + it.w - boxW } ?: (width - boxW)
                                val maxY = bounds?.let { it.y + it.h - boxH } ?: (height - boxH)
                                spot.x = x.coerceIn(minX, maxX.coerceAtLeast(minX))
                                spot.y = y.coerceIn(minY, maxY.coerceAtLeast(minY))
                            }
                        }
                    )
                    .height(if (compactHeader) 22.dp else HEADER_HEIGHT)
                    .padding(start = if (compactHeader) 6.dp else 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compactHeader) {
                    if (!locked) {
                        Text(
                            "MOVE",
                            style = Type.caption.copy(fontSize = 7.sp, color = Ink.secondary, fontWeight = FontWeight.Bold),
                            modifier = Modifier
                                .padding(horizontal = 7.dp, vertical = 1.dp)
                                .onKeyEvent { event ->
                                    when (event.key) {
                                        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                                            if (event.type == KeyEventType.KeyDown) {
                                                val step = with(density) { 18.dp.toPx() }
                                                val dx = when (event.key) { Key.DirectionLeft -> -step; Key.DirectionRight -> step; else -> 0f }
                                                val dy = when (event.key) { Key.DirectionUp -> -step; Key.DirectionDown -> step; else -> 0f }
                                                val minX = bounds?.x ?: 0f
                                                val minY = bounds?.y ?: 0f
                                                val maxX = bounds?.let { it.x + it.w - boxW } ?: (width - boxW)
                                                val maxY = bounds?.let { it.y + it.h - boxH } ?: (height - boxH)
                                                spot.x = (spot.x + dx).coerceIn(minX, maxX.coerceAtLeast(minX))
                                                spot.y = (spot.y + dy).coerceIn(minY, maxY.coerceAtLeast(minY))
                                            } else if (event.type == KeyEventType.KeyUp) spot.store(width, height)
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .pressable(scaleTo = 1f) {},
                        )
                        Text(
                            "SIZE",
                            style = Type.caption.copy(fontSize = 7.sp, color = Ink.secondary, fontWeight = FontWeight.Bold),
                            modifier = Modifier
                                .padding(horizontal = 7.dp, vertical = 1.dp)
                                .onKeyEvent { event ->
                                    when (event.key) {
                                        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                                            if (event.type == KeyEventType.KeyDown) {
                                                val step = with(density) { 18.dp.toPx() }
                                                val dx = when (event.key) { Key.DirectionLeft -> -step; Key.DirectionRight -> step; else -> 0f }
                                                val dy = when (event.key) { Key.DirectionUp -> -step; Key.DirectionDown -> step; else -> 0f }
                                                val minW = with(density) { effectiveMinWidth.toPx() }
                                                val minH = with(density) { effectiveMinHeight.toPx() }
                                                val maxW = bounds?.let { it.x + it.w - spot.x } ?: (width - spot.x)
                                                val maxH = bounds?.let { it.y + it.h - spot.y } ?: (height - spot.y)
                                                spot.w = (spot.w.takeIf { it > 0f } ?: boxW).let { (it + dx).coerceIn(minW, maxW.coerceAtLeast(minW)) }
                                                spot.h = (spot.h.takeIf { it > 0f } ?: boxH).let { (it + dy).coerceIn(minH, maxH.coerceAtLeast(minH)) }
                                            } else if (event.type == KeyEventType.KeyUp) {
                                                spot.store(width, height)
                                                onResizeFinished()
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .pressable(scaleTo = 1f) {},
                        )
                    }
                    Spacer(Modifier.weight(1f))
                } else {
                    Text(title, style = Type.headline, modifier = Modifier.weight(1f))
                }
                Box(
                    Modifier.size(if (compactHeader) 24.dp else 36.dp).pressable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, closeLabel, tint = Ink.secondary, modifier = Modifier.size(16.dp))
                }
            }
            content()
        }
        // The corner grip: drag to resize, edges snapping like the feeds'.
        if (!locked) Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(36.dp)
                .pointerInput(width, height, feedScale, bounds) {
                    detectDragGestures(
                        onDragStart = { rawW = boxW; rawH = boxH },
                        onDragEnd = { spot.store(width, height); onResizeFinished() },
                    ) { change, drag ->
                        change.consume()
                        rawW += drag.x
                        rawH += drag.y
                        var w = rawW
                        var h = rawH
                        if (app.snapMode) {
                            val (xs, ys) = snapLines(app, width, height, feedScale)
                            w += pull(listOf(spot.x + w), xs, SNAP.toPx())
                            h += pull(listOf(spot.y + h), ys, SNAP.toPx())
                        }
                        val availableWidth = bounds?.let { it.x + it.w - spot.x } ?: (width - spot.x)
                        val availableHeight = bounds?.let { it.y + it.h - spot.y } ?: (height - spot.y)
                        spot.w = w.coerceIn(minWidthPx, availableWidth.coerceAtLeast(minWidthPx))
                        spot.h = h.coerceIn(effectiveMinHeight.toPx(), availableHeight.coerceAtLeast(effectiveMinHeight.toPx()))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                val stroke = 1.5.dp.toPx()
                val c = Color.White.copy(alpha = 0.45f)
                drawLine(c, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), stroke)
                drawLine(c, androidx.compose.ui.geometry.Offset(size.width, size.height / 2), androidx.compose.ui.geometry.Offset(size.width / 2, size.height), stroke)
            }
        }
    }
}
