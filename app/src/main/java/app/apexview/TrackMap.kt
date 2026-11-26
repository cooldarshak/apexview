package app.apexview


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Maps archive coordinates onto a box. The track is turned to whichever angle lets it fill the
 * box the most, and y is flipped (the archive's y points up).
 */
class MapFit private constructor(
    private val cos: Float,
    private val sin: Float,
    private val scale: Float,
    private val left: Float,
    private val top: Float,
    private val minX: Float,
    private val maxY: Float,
) {
    fun map(x: Float, y: Float): Offset {
        val rx = x * cos - y * sin
        val ry = x * sin + y * cos
        return Offset(left + (rx - minX) * scale, top + (maxY - ry) * scale)
    }

    companion object {
        /**
         * The fit at whichever angle fills the box best, then turned a further [turn] degrees (the
         * user's own rotation), still fitted to the box.
         */
        fun of(outline: FloatArray, width: Float, height: Float, margin: Float, turn: Float = 0f): MapFit? {
            if (outline.size < 4) return null
            var best: MapFit? = null
            var bestDegrees = 0
            for (degrees in 0 until 180 step 5) {
                val fit = at(outline, width, height, margin, degrees.toFloat())
                if (best == null || fit.scale > best.scale) {
                    best = fit
                    bestDegrees = degrees
                }
            }
            return if (turn == 0f) best else at(outline, width, height, margin, bestDegrees + turn)
        }

        private fun at(outline: FloatArray, width: Float, height: Float, margin: Float, degrees: Float): MapFit {
            val w = width - 2 * margin
            val h = height - 2 * margin
            val a = Math.toRadians(degrees.toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in outline.indices step 2) {
                val rx = outline[i] * c - outline[i + 1] * s
                val ry = outline[i] * s + outline[i + 1] * c
                minX = min(minX, rx); maxX = maxOf(maxX, rx)
                minY = min(minY, ry); maxY = maxOf(maxY, ry)
            }
            val scale = min(w / (maxX - minX), h / (maxY - minY))
            return MapFit(
                c, s, scale,
                left = margin + (w - (maxX - minX) * scale) / 2,
                top = margin + (h - (maxY - minY) * scale) / 2,
                minX = minX, maxY = maxY,
            )
        }
    }

    /** The drawn track's bounding box, in the box's pixels. */
    fun bounds(outline: FloatArray): androidx.compose.ui.geometry.Rect {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in outline.indices step 2) {
            val p = map(outline[i], outline[i + 1])
            l = min(l, p.x); r = maxOf(r, p.x)
            t = min(t, p.y); b = maxOf(b, p.y)
        }
        return androidx.compose.ui.geometry.Rect(l, t, r, b)
    }

    /** True when ([px], [py]), in the box's pixels, is within [reach] of the drawn track line. */
    fun nearLine(outline: FloatArray, px: Float, py: Float, reach: Float): Boolean {
        val n = outline.size / 2
        for (i in 0 until n) {
            val a = map(outline[i * 2], outline[i * 2 + 1])
            val j = (i + 1) % n
            val b = map(outline[j * 2], outline[j * 2 + 1])
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val k = if (len2 == 0f) 0f else (((px - a.x) * dx + (py - a.y) * dy) / len2).coerceIn(0f, 1f)
            if (hypot(a.x + dx * k - px, a.y + dy * k - py) <= reach) return true
        }
        return false
    }
}

private val PUSH_RING = Color(0xFFFFD400)

/**
 * Common draw-time contract for immutable receiver packets and retained map markers.
 * The actual immutable Cast packet and 60Hz mutable map marker remain separate owners.
 */
internal interface OverlayMotionCar {
    val number: String
    val x: Float
    val y: Float
    val state: LapState
}

internal class Car(
    override val number: String, override val x: Float,
    override val y: Float, override val state: LapState,
) : OverlayMotionCar

/** The video keeps its 50 fps clock. Text overlays need fewer Compose updates than the video. */
@Composable
internal fun rememberMapTime(app: AppState): Long {
    if (app.isTv) return rememberTvMapTime { app.mapTimeMs() ?: 0L }
    return rememberMapRenderTime(app)
}

/**
 * Map geometry and marker hit positions do not need to trigger a full Compose pass at 60/120 Hz.
 * Publish static/hit-target/flag state at 10 Hz, while moving car dots continue
 * at up to 60 Hz in their separate Draw-only clock. This cuts repeated TV
 * Compose work under simultaneous video + map + timing tower load.
 */
internal const val MAP_RENDER_INTERVAL_MS = 100L
// Only flag/hit-target state uses this interval. Animated car markers retain
// their independent draw-phase clock on both phone and TV.

/** Sample immutable overlay metadata at most 10 times/second. Recover from seeks. */
internal fun isOverlaySampleDue(frameMs: Long, lastFrameMs: Long): Boolean =
    lastFrameMs == Long.MIN_VALUE || frameMs < lastFrameMs || frameMs - lastFrameMs >= MAP_RENDER_INTERVAL_MS

/**
 * Recompute map layout, flags and clickable targets at most 10 times per
 * second. Car dots do NOT use this clock: their Canvas draw updates continue
 * at display-vsync cadence, independently of Compose. That separation is
 * required for an Android TV video decoder alongside map and tower.
 */
internal const val TV_MAP_FRAME_INTERVAL_NS = 100_000_000L

@Composable
internal fun rememberTvMapTime(read: () -> Long): Long {
    val clock by rememberUpdatedState(read)
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(Unit) {
        // Static flags/targets need only 10Hz. Stop invoking TV's main
        // Choreographer at every 60/120Hz vsync just to discard most frames.
        // Driver markers keep their separate 60Hz Canvas draw-only clock.
        while (true) {
            now = clock()
            delay(TV_MAP_FRAME_INTERVAL_NS / 1_000_000L)
        }
    }
    return now
}

@Composable
internal fun rememberMapRenderTime(app: AppState): Long {
    var now by remember(app) { mutableLongStateOf(app.mapTimeMs() ?: 0L) }
    LaunchedEffect(app) {
        var lastPublishedAtMs = Long.MIN_VALUE
        while (true) {
            // Phone previously waited for a Choreographer frame then slept, adding
            // UI-thread wakeups under six-feed playback. Static map metadata needs
            // elapsed-time sampling, not a display-vsync callback. This does not
            // change the independent draw-only 60Hz car marker clock.
            val sampledAtMs = System.nanoTime() / 1_000_000L
            if (isOverlaySampleDue(sampledAtMs, lastPublishedAtMs)) {
                val sampledTime = app.mapTimeMs() ?: 0L
                if (sampledTime != now) now = sampledTime
                lastPublishedAtMs = sampledAtMs
            }
            val elapsedMs = System.nanoTime() / 1_000_000L
            val untilNextMs = (lastPublishedAtMs + MAP_RENDER_INTERVAL_MS - elapsedMs)
                .coerceAtLeast(1L)
            delay(untilNextMs)
        }
    }
    return now
}

/** Every car on track at [now]; one out of the race (retired, or stopped) is left off every map. */
internal fun carsAt(timing: SessionTiming, now: Long) = if (timing.beforePositions(now)) emptyList() else timing.tracks.mapNotNull { (number, track) ->
    if (timing.laps[number]?.retiredAt(now) == true) return@mapNotNull null
    track.atOrFirst(now)?.let { (x, y) -> Car(number, x, y, timing.states[number]?.at(now) ?: LapState.UNKNOWN) }
}

internal fun TextMeasurer.label(text: String, size: TextUnit, bold: Boolean = true) =
    measure(text, TextStyle(color = Color.White, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))

/** Team-colored dot: a yellow ring on a flying lap in qualifying, small and faded in the pits. */
internal fun DrawScope.drawCar(timing: SessionTiming, car: Car, at: Offset, baseRadius: Float): Float =
    drawCar(timing, car.number, car.state, at, baseRadius)

/** Direct field draw avoids per-frame Car allocation for the 60Hz marker layer. */
internal fun DrawScope.drawCar(timing: SessionTiming, number: String, state: LapState,
    at: Offset, baseRadius: Float): Float {
    val color = Color(timing.drivers[number]?.color ?: android.graphics.Color.WHITE)
    val dim = state == LapState.PIT || state == LapState.KNOCKED_OUT
    val push = timing.showsPushLaps && state == LapState.PUSH
    val radius = when {
        push -> baseRadius * 1.3f
        dim -> baseRadius * 0.7f
        else -> baseRadius
    }
    val alpha = if (dim) 0.4f else 1f
    if (push) drawCircle(PUSH_RING, radius + 3.dp.toPx(), at)
    drawCircle(Color.Black.copy(alpha = alpha), radius + minOf(1.5.dp.toPx(), radius * .45f), at)
    drawCircle(color.copy(alpha = alpha), radius, at)
    return alpha
}

/**
 * Invisible 44dp hit targets that follow the dots. Android TV keeps one focusable target per
 * driver for D-pad navigation. Touch devices use one pointer node and hit-test the same 44dp
 * squares, avoiding a full set of clickable Compose layouts being recomposed on every map frame.
 * Tap still opens the onboard; long-press still toggles the pit-stop marker.
 */
@Composable
internal fun CarTargets(
    app: AppState,
    positions: List<Pair<String, Offset>>,
    onTap: (String) -> Unit = app::showOnboard,
    onHold: (String) -> Unit = { app.pitDriver = if (app.pitDriver == it) null else it },
) {
    if (!app.settings.mapDriverSelection) return
    val target = 44.dp
    val half = with(LocalDensity.current) { target.toPx() / 2 }
    if (app.isTv) {
        DriverFocusTargets(positions, half, onTap, onHold)
    } else {
        val latestPositions = rememberUpdatedState(positions)
        val latestOnTap = rememberUpdatedState(onTap)
        val latestOnHold = rememberUpdatedState(onHold)
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription =
                        "Driver tracker. Tap a driver marker to open that onboard. Long-press a marker to show its pit-stop position."
                }
                .pointerInput(half) {
                    detectTapGestures(
                        onTap = { point ->
                            carTargetAt(latestPositions.value, point, half)?.let { latestOnTap.value(it) }
                        },
                        onLongPress = { point ->
                            carTargetAt(latestPositions.value, point, half)?.let { latestOnHold.value(it) }
                        },
                    )
                }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun DriverFocusTargets(
    positions: List<Pair<String, Offset>>,
    half: Float,
    onTap: (String) -> Unit,
    onHold: (String) -> Unit,
) {
    val target = with(LocalDensity.current) { (half * 2).toDp() }
    val latest = rememberUpdatedState(positions)
    val select = rememberUpdatedState(onTap)
    val hold = rememberUpdatedState(onHold)
    positions.forEachIndexed { index, (number, _) ->
        key(number) {
            Box(remember(index, number, half, target) {
                Modifier.offset {
                    val p = latest.value.getOrNull(index)?.second ?: Offset.Zero
                    IntOffset((p.x - half).roundToInt(), (p.y - half).roundToInt())
                }.size(target).tvFocusRing()
                    .combinedClickable(onLongClick = { hold.value(number) }) { select.value(number) }
            })
        }
    }
}

/** Matches the old square 44dp target and chooses the nearest marker if targets overlap. */
internal fun carTargetAt(
    positions: List<Pair<String, Offset>>,
    point: Offset,
    halfSizePx: Float,
): String? {
    if (halfSizePx <= 0f) return null
    return positions.asSequence()
        .map { (number, at) ->
            val dx = point.x - at.x
            val dy = point.y - at.y
            Triple(number, dx, dy)
        }
        .filter { (_, dx, dy) -> kotlin.math.abs(dx) <= halfSizePx && kotlin.math.abs(dy) <= halfSizePx }
        .minByOrNull { (_, dx, dy) -> dx * dx + dy * dy }
        ?.first
}

private val MAP_MARGIN = 22.dp
/** START and FINISH labels sit as far in from the ribbon as the first row of car names. */
private const val MARK_LABEL_DP = 15

/**
 * True when a touch at ([x], [y]) on the canvas lands on the track map's line (within [reach]),
 * the only part of the map that can be grabbed, so feeds under the rest of its box stay usable.
 */
fun mapLineTouched(app: AppState, timing: SessionTiming, x: Float, y: Float, margin: Float, reach: Float): Boolean {
    val panel = app.activeMapPanel
    if (x < panel.x - reach || y < panel.y - reach || x > panel.x + panel.w + reach || y > panel.y + panel.h + reach) return false
    val fit = MapFit.of(timing.outline, panel.w, panel.h, margin, panel.rotation) ?: return false
    return fit.nearLine(timing.outline, x - panel.x, y - panel.y, reach)
}

fun mapMarginPx(density: androidx.compose.ui.unit.Density) = with(density) { MAP_MARGIN.toPx() }

/**
 * Whether the track map should expose its lock/move/resize controls. On touch devices they appear
 * while arranging (or in a floating workspace). On TV they remain available in docked layouts too,
 * because the remote is the way to move and resize the map.
 */
internal fun shouldShowMapControls(
    isTv: Boolean,
    arranging: Boolean,
    floating: Boolean,
    docked: Boolean,
): Boolean = isTv || (!docked && (arranging || floating))

/** The locked-map control fades away after inactivity on touch devices. */
internal const val MAP_LOCK_CONTROL_AUTO_HIDE_MS = 5_000L

/** Keep the lock button visible while unlocked, temporarily revealed, or used with a TV remote. */
internal fun shouldShowMapLockControl(
    mapLocked: Boolean,
    isTv: Boolean,
    temporarilyVisible: Boolean,
    chromeVisible: Boolean = false,
    lockFocused: Boolean = false,
): Boolean = !mapLocked || temporarilyVisible || (isTv && (chromeVisible || lockFocused))

/**
 * The track map in its own window. While arranging it's moved, resized and turned by its track
 * line (see [mapLineTouched]) or by the move grip; the corner handle resizes it and the lock keeps
 * it still while the feeds under it are arranged. The controls sit at the corners of the track
 * itself, not of the window, which can be much bigger than the track and run off the screen.
 */
@Composable
fun MapPanelView(app: AppState, timing: SessionTiming) {
    val panel = app.activeMapPanel
    val density = LocalDensity.current
    val keepControlsForLayout = app.isTv || app.layoutMode == LayoutMode.TV
    var mapInteractionGeneration by remember(app) { mutableLongStateOf(0L) }
    var lockControlTemporarilyVisible by remember(app) { mutableStateOf(true) }
    var lockFocused by remember(app) { mutableStateOf(false) }

    // Lock the map, then leave the unobtrusive lock affordance visible briefly. Any subsequent
    // touch anywhere in the map panel restarts the five-second window. TV reveals it with playback chrome and retains existing D-pad focus.
    LaunchedEffect(app.mapLocked, mapInteractionGeneration) {
        lockControlTemporarilyVisible = true
        if (app.mapLocked) {
            delay(MAP_LOCK_CONTROL_AUTO_HIDE_MS)
            lockControlTemporarilyVisible = false
        }
    }

    Box(
        Modifier
            .offset { IntOffset(panel.x.roundToInt(), panel.y.roundToInt()) }
            // requiredSize, not size: size() is capped at the screen width, while the track is
            // fitted to the full panel, so a panel wider than the screen lost its right side.
            .requiredSize(with(density) { panel.w.toDp() }, with(density) { panel.h.toDp() })
            .pointerInput(app.mapLocked, app.isTv) {
                if (app.mapLocked && !app.isTv) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.pressed && !it.previousPressed }) {
                                mapInteractionGeneration++
                            }
                        }
                    }
                }
            }
    ) {
        TrackDrawing(app, timing, panel.w, panel.h, panel.rotation)
        val showControls = shouldShowMapControls(
            keepControlsForLayout,
            app.arranging,
            app.combinedOverlaysFloating,
            app.combinedOverlaysDocked && !app.trackLogoAnchored,
        )
        val showLockControl = shouldShowMapLockControl(
            app.mapLocked,
            app.isTv,
            lockControlTemporarilyVisible || app.arranging,
            chromeVisible = app.chromeVisible,
            lockFocused = lockFocused,
        )
        if (showControls && showLockControl) {
            MapControls(app, timing) { lockFocused = it }
        }
    }
}

/** The track map filling whatever box it's given (laptop mode's lower half). */
@Composable
fun TrackMapFill(app: AppState, timing: SessionTiming) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        TrackDrawing(app, timing, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), 0f)
    }
}

/** Lock, move grip and resize handle, at the corners of the drawn track. */
@Composable
private fun MapControls(app: AppState, timing: SessionTiming, onLockFocus: (Boolean) -> Unit) {
    val panel = app.activeMapPanel
    val density = LocalDensity.current
    val bounds = remember(timing.outline, panel.w, panel.h, panel.rotation, density) {
        MapFit.of(timing.outline, panel.w, panel.h, mapMarginPx(density), panel.rotation)?.bounds(timing.outline)
    } ?: return
    Box(Modifier.fillMaxSize()) {
        val control = with(density) { 44.dp.toPx() }
        // Each control stays on screen even if that corner of the track is off it.
        val (screenW, screenH) = app.canvasSize
        fun at(x: Float, y: Float) = Modifier.offset {
            val cx = (panel.x + x).coerceIn(control / 2, (screenW - control / 2).coerceAtLeast(control / 2)) - panel.x
            val cy = (panel.y + y).coerceIn(control / 2, (screenH - control / 2).coerceAtLeast(control / 2)) - panel.y
            IntOffset((cx - control / 2).roundToInt(), (cy - control / 2).roundToInt())
        }

        // Top-right of the track: lock, and the move grip beside it.
        MapControl(at(bounds.right, bounds.top).onFocusChanged { onLockFocus(it.isFocused) }.pressable(scaleTo = 1f, onClick = app::toggleMapLock).tvFocusRing()) {
            Icon(
                if (app.mapLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                if (app.mapLocked) "Unlock the map" else "Lock the map",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        var remoteEdit by remember { mutableStateOf<String?>(null) }
        if (!app.mapLocked) MapControl(
            at(bounds.right - control, bounds.top)
                .onKeyEvent { event ->
                    if (remoteEdit != "move") return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) {
                                val step = with(density) { 18.dp.toPx() }
                                val dx = when (event.key) { Key.DirectionLeft -> -step; Key.DirectionRight -> step; else -> 0f }
                                val dy = when (event.key) { Key.DirectionUp -> -step; Key.DirectionDown -> step; else -> 0f }
                                app.arrangeMap(Offset(dx, dy), 1f, 0f)
                            } else if (event.type == KeyEventType.KeyUp) app.storeMapPanel()
                            true
                        }
                        else -> false
                    }
                }
                .onFocusChanged { if (!it.isFocused && remoteEdit == "move") remoteEdit = null }
                .pressable(scaleTo = 1f) { remoteEdit = if (remoteEdit == "move") null else "move" }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { app.mapGrabbed = true },
                        onDragEnd = { app.mapGrabbed = false; app.storeMapPanel() },
                        onDragCancel = { app.mapGrabbed = false },
                    ) { change, drag ->
                        change.consume()
                        app.arrangeMap(drag, 1f, 0f)
                    }
                }
        ) {
            Icon(Icons.Rounded.OpenWith, "Move the map", tint = Color.White, modifier = Modifier.size(18.dp))
        }
        // Bottom-right of the track: resize, keeping the track's shape and centre.
        if (!app.mapLocked) MapControl(
            at(bounds.right, bounds.bottom)
                .onKeyEvent { event ->
                    if (remoteEdit != "resize") return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) {
                                val factor = when (event.key) {
                                    Key.DirectionRight, Key.DirectionDown -> 1.035f
                                    else -> 0.965f
                                }
                                app.arrangeMap(Offset.Zero, factor, 0f)
                            } else if (event.type == KeyEventType.KeyUp) app.storeMapPanel()
                            true
                        }
                        else -> false
                    }
                }
                .onFocusChanged { if (!it.isFocused && remoteEdit == "resize") remoteEdit = null }
                .semantics { contentDescription = "Resize the map" }
                .pressable(scaleTo = 1f) { remoteEdit = if (remoteEdit == "resize") null else "resize" }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { app.mapGrabbed = true },
                        onDragEnd = { app.mapGrabbed = false; app.storeMapPanel() },
                        onDragCancel = { app.mapGrabbed = false },
                    ) { change, drag ->
                        change.consume()
                        // Read live: the gesture outlives each size it passes through.
                        val span = (panel.w + panel.h).coerceAtLeast(1f)
                        app.arrangeMap(Offset.Zero, 1f + (drag.x + drag.y) / span, 0f)
                    }
                }
        ) {
            Canvas(Modifier.size(14.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), stroke)
                drawLine(Color.White, Offset(size.width, size.height / 2), Offset(size.width / 2, size.height), stroke)
            }
        }
    }
}

/** A 44dp target with a small dark disc behind its icon, so it reads over any picture. */
@Composable
private fun MapControl(modifier: Modifier, icon: @Composable () -> Unit) {
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(30.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) { icon() }
    }
}

/**
 * A line across the track at [p]: [across] runs over the track, [along] with it. The finish is
 * chequered, the start plain white.
 */
private fun DrawScope.drawLineMark(p: Offset, across: Offset, along: Offset, half: Float, chequered: Boolean) {
    if (!chequered) {
        drawLine(Color.Black, p - across * (half + 1.5f), p + across * (half + 1.5f), 5.dp.toPx())
        drawLine(Color.White, p - across * half, p + across * half, 3.dp.toPx())
        return
    }
    val s = 3.dp.toPx()
    val count = (2 * half / s).roundToInt().coerceAtLeast(2)
    val start = p - across * (count * s / 2) - along * s
    for (i in 0 until count) for (row in 0..1) {
        val corner = start + across * (i * s) + along * (row * s)
        val square = Path().apply {
            moveTo(corner.x, corner.y)
            (corner + across * s).let { lineTo(it.x, it.y) }
            (corner + across * s + along * s).let { lineTo(it.x, it.y) }
            (corner + along * s).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(square, if ((i + row) % 2 == 0) Color.White else Color.Black)
    }
}

/** Start and finish as fractions of a lap, merged into one when they're within 15 m. */
private class LineMarks(timing: SessionTiming) {
    val start = timing.startLine?.takeIf { timing.hasStartFinish }
    val together = start != null && minOf(start, 1f - start) * timing.geometry.length < 150f
    val finishLabel = if (together) "START · FINISH" else "FINISH"
}

/** Circuit outline, start and finish, turn numbers and the cars, fitted into a w x h box. */
@Composable
private fun TrackDrawing(app: AppState, timing: SessionTiming, width: Float, height: Float, turn: Float) {
    val now = rememberMapRenderTime(app)
    // Hit targets and 60 Hz drawing use separate chronological cursors.
    // Alternating their clocks in one cursor caused unnecessary seek resets.
    val drawSampler = remember(timing, app.settings.nativeRustStitching) {
        OverlayPositionSampler(timing, OverlayPerformanceProbe("phone-track-draw-position"), app.settings.nativeRustStitching)
    }
    val cars = if (app.settings.mapDriverSelection || app.pitDriver != null) {
        val hitSampler = remember(timing, app.settings.nativeRustStitching) {
            OverlayPositionSampler(timing, OverlayPerformanceProbe("phone-track-position"), app.settings.nativeRustStitching)
        }
        remember(timing, now, hitSampler) {
            android.os.Trace.beginSection("PitwallMapCars")
            try { hitSampler.sample(now) } finally { android.os.Trace.endSection() }
        }
    } else emptyList()
    // The screen clock is read ONLY by the foreground Canvas draw lambda.
    // Its updates invalidate drawing without recomposing the map subtree.
    val drawClock = rememberOverlayDrawClock(timing, { app.mapTimeMs() ?: 0L },
        "phone-map-motion-clock")
    val motion = remember(timing, drawSampler, drawClock) {
        OverlayMotionSource(drawClock, drawSampler::sampleForDraw)
    }
    val stop = app.pitDriver?.let { driver ->
        val projection = remember(timing.geometry, app.settings.nativeRustStitching) {
            if (app.settings.nativeRustStitching) NativeCircuitProjection(timing.geometry) else null
        }
        val projected = projection?.project(cars) == true
        pitStopNow(rememberPitBasis(app, timing, now), driver, cars.mapIndexed { i, car ->
            car.number to if (projected) requireNotNull(projection).fractions[i]
                else timing.geometry.progress(car.x, car.y)
        })
    }
    TrackDrawingContent(timing, width, height, turn, app.mapOpacity, app.mapDotSizeDp, app.mapLabelSizeSp,
        now, cars, stop, timing.flags.at(now, app.settings.nativeRustStitching), targets = if (app.settings.mapDriverSelection) {
            { targets -> CarTargets(app, targets) }
        } else null, motion = motion, nativeRequested = app.settings.nativeRustStitching)
}

/** Pure current-state rendering, shared with the LAN receiver without replay-history parsing. */
@Composable
internal fun TrackDrawingContent(timing: SessionTiming, width: Float, height: Float, turn: Float,
    opacity: Float, dotSizeDp: Float, labelSizeSp: Float, now: Long, currentCars: List<Car>,
    stop: PitStopNow?, flags: FlagPicture, leader: String? = null,
    motion: OverlayMotionSource? = null,
    nativeRequested: Boolean = false,
    targets: (@Composable (List<Pair<String, Offset>>) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val fit = remember(timing.outline, width, height, turn, density) {
        MapFit.of(timing.outline, width, height, margin = mapMarginPx(density), turn = turn)
    } ?: return
    val nativeGeometry = remember(timing.geometry, nativeRequested) {
        if (nativeRequested) NativeCircuitProjection(timing.geometry) else null
    }
    fun progress(x: Float, y: Float) = nativeGeometry?.progress(x, y) ?: timing.geometry.progress(x, y)
    val circuitGeometry = timing.geometry
    fun geometryPoint(f: Float) = nativeGeometry?.pointAt(f) ?: circuitGeometry.pointAt(f)
    val path = remember(fit) {
        Path().apply {
            val o = timing.outline
            for (i in o.indices step 2) {
                val p = fit.map(o[i], o[i + 1])
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
    }
    val measurer = rememberTextMeasurer()
    val names = remember(timing.drivers, labelSizeSp, measurer) {
        timing.drivers.mapValues { measurer.label(it.value.tla, labelSizeSp.sp) }
    }
    val turns = remember(timing.corners, measurer) { timing.corners.associate { it.number to measurer.label("${it.number}", 9.sp, bold = false) } }
    val marks = remember(timing.geometry, timing.hasStartFinish, timing.startLine) { LineMarks(timing) }
    val finishLabel = remember(marks, measurer) { measurer.label(marks.finishLabel, 8.sp) }
    val startLabel = remember(measurer) { measurer.label("START", 8.sp) }
    fun lapPoint(f: Float) = geometryPoint(f).let { g -> fit.map(g[0], g[1]) }
    val sectors = remember(fit, timing.marshalSectors, nativeGeometry) { sectorPaths(timing, ::progress, ::lapPoint) }
    val scLabel = remember(measurer) { measurer.label("SC", 8.sp) }
    val staticProbe = remember(fit) { OverlayPerformanceProbe("track-static-displaylist") }
    val dynamicProbe = remember(fit) { OverlayPerformanceProbe("track-car-flag-draw") }
    val staticLayer = rememberGraphicsLayer()
    val staticValidity = remember(staticLayer) { OverlayRecordingValidity() }
    val eventLayer = rememberGraphicsLayer()
    val eventValidity = remember(eventLayer) { OverlayRecordingValidity() }
    val cars = remember(fit, currentCars) { currentCars.map { it to fit.map(it.x, it.y) } }
    val pitLabel = remember(measurer) { measurer.label("PIT", 8.sp) }
    val stopLine = stop?.let { pitStopLine(timing, it) } ?: noPositionsLine(timing, now)
    val stopText = remember(stopLine, measurer) { stopLine?.let { measurer.label(it, 13.sp) } }
    val pulse = rememberOverlayFlagPulse(flags.track.ending)
    val caption = rememberCaption(measurer, flags, now)
    // Leader and flags are part of the 10Hz metadata sample. Never redo 24
    // driver lap lookups on the 60Hz draw thread in either map style.
    val currentSafetyLeader = remember(timing, now, leader, flags.track.safetyCar) {
        if (flags.track.safetyCar) leader ?: leaderAt(timing, now) else null
    }
    val safetyCar = if (!flags.track.safetyCar) null else currentSafetyLeader
        ?.let { leader -> cars.firstOrNull { it.first.number == leader }?.first }
        ?.let { lapPoint(progress(it.x, it.y) + SAFETY_CAR_AHEAD) }
    // Only a changed event/flag/pit marker re-records this middle layer.
    val eventKey = remember(fit, nativeGeometry, measurer, sectors, flags.track, flags.yellow,
        flags.resumption?.announcedUtc, flags.resumption?.atUtc, flags.resumption?.what,
        caption, stop?.number, stop?.carProgress, stop?.ghostProgress,
        stop?.outBehind, stopText) { Any() }
    val eventProbe = remember(fit) { OverlayPerformanceProbe("track-event-displaylist") }

    // Keep the circuit and its labels in their own recorded layer. Moving cars only redraw
    // the foreground, with no offscreen alpha buffer around the entire track map.
    val corners = timing.corners
    val showFinish = timing.hasStartFinish && timing.outline.size >= 4
    val circuit: DrawScope.() -> Unit = remember(fit, path, corners, turns, marks.start, nativeGeometry,
        showFinish, finishLabel, startLabel) {
        {
            drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(7.dp.toPx()))
            drawPath(path, Color.White.copy(alpha = 0.6f), style = Stroke(3.dp.toPx()))
            val center = Offset(width / 2, height / 2)

            fun mark(f: Float, chequered: Boolean, label: TextLayoutResult) {
                val g = geometryPoint(f)
                val p = fit.map(g[0], g[1])
                val ahead = fit.map(g[0] + g[2] * 10f, g[1] + g[3] * 10f)
                val len = hypot(ahead.x - p.x, ahead.y - p.y).coerceAtLeast(0.001f)
                val along = Offset((ahead.x - p.x) / len, (ahead.y - p.y) / len)
                val across = Offset(-along.y, along.x)
                drawLineMark(p, across, along, 9.dp.toPx(), chequered)
                val out = outward(p, center) * 20.dp.toPx()
                drawText(label, topLeft = p + out - Offset(label.size.width / 2f, label.size.height / 2f))
            }
            if (showFinish) {
                mark(0f, chequered = true, finishLabel)
                marks.start?.takeIf { !marks.together }?.let { mark(it, chequered = false, startLabel) }
            }

            corners.forEach { corner ->
                val label = turns[corner.number] ?: return@forEach
                val p = fit.map(corner.x, corner.y)
                val at = p + outward(p, center) * 14.dp.toPx()
                drawText(label, topLeft = at - Offset(label.size.width / 2f, label.size.height / 2f), alpha = 0.75f)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        val layer = Modifier.fillMaxSize().graphicsLayer {
            alpha = opacity
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        Canvas(layer) {
            val start = System.nanoTime()
            val pixelWidth = size.width.toInt().coerceAtLeast(1)
            val pixelHeight = size.height.toInt().coerceAtLeast(1)
            var changed = 0
            if (staticValidity.needsRecord(circuit, pixelWidth, pixelHeight)) {
                staticLayer.record(density = this, layoutDirection = layoutDirection,
                    size = IntSize(pixelWidth, pixelHeight)) { circuit() }
                staticValidity.markRecorded(circuit, pixelWidth, pixelHeight)
                changed = 1
            } else staticValidity.markReplayed()
            drawLayer(staticLayer)
            staticProbe.record(System.nanoTime() - start, timing.corners.size, changed)
        }
        // The transient flag remains BELOW pit annotations and moving cars.
        // The retained circuit and event lists never re-record for a pulse frame.
        if (flags.track.ending) {
            val pulseProbe = remember(fit) { OverlayPerformanceProbe("track-flag-pulse-draw") }
            Canvas(layer) {
                val started = System.nanoTime()
                val alpha = pulse.value
                drawFlags(flags, path, sectors, 3.5.dp.toPx(), alpha)
                drawCaption(flags, caption, width / 2, height - 4.dp.toPx(), alpha)
                pulseProbe.record(System.nanoTime() - started, 1, 1)
            }
        }
        Canvas(layer) {
            val start = System.nanoTime()
            val w = size.width.toInt().coerceAtLeast(1)
            val h = size.height.toInt().coerceAtLeast(1)
            var changed = 0
            if (eventValidity.needsRecord(eventKey, w, h)) {
                eventLayer.record(density = this, layoutDirection = layoutDirection,
                    size = IntSize(w, h)) {
                    if (!flags.track.ending) drawFlags(flags, path, sectors, 3.5.dp.toPx(), 1f)
                    drawSectorGlyphs(flags, sectors, 14.dp.toPx()) { f ->
                        lapPoint(f).let { p ->
                            p + outward(p, Offset(width / 2, height / 2)) * 18.dp.toPx()
                        }
                    }
                    if (!flags.track.ending) drawCaption(flags, caption, width / 2, height - 4.dp.toPx(), 1f)
                    stopText?.let { text ->
                        val above = caption?.let { it.size.height + 6.dp.toPx() } ?: 0f
                        drawText(text, topLeft = Offset(width / 2 - text.size.width / 2f,
                            height - 4.dp.toPx() - above - text.size.height))
                    }
                    stop?.let {
                        drawPitStop(it, Color(timing.drivers[it.number]?.color
                            ?: android.graphics.Color.WHITE), pitLabel) { f -> lapPoint(f) }
                    }
                }
                eventValidity.markRecorded(eventKey, w, h)
                changed = 1
            } else eventValidity.markReplayed()
            drawLayer(eventLayer)
            eventProbe.record(System.nanoTime() - start, timing.marshalSectors.size, changed)
        }
        Canvas(layer) {
            val start = System.nanoTime()
            // Dynamic car motion reads the video clock in Draw, never Composition.
            val movingCars = motion?.let { it.carsAt(it.positionMs.value) }
            if (movingCars != null) {
                val radius = dotSizeDp.dp.toPx()
                movingCars.forEach { car ->
                    val p = fit.map(car.x, car.y)
                    val alpha = drawCar(timing, car.number, car.state, p, radius)
                    if (car.number == stop?.number) drawCircle(Color.White, radius + 4.dp.toPx(),
                        p, style = Stroke(2.dp.toPx()))
                    names[car.number]?.let {
                        drawText(it, topLeft = Offset(p.x + radius + minOf(3.dp.toPx(), radius),
                            p.y - it.size.height / 2f), alpha = alpha)
                    }
                }
                if (flags.track.safetyCar) {
                    val safetyLeader = currentSafetyLeader
                    movingCars.firstOrNull { it.number == safetyLeader }?.let { car ->
                        val point = lapPoint(progress(car.x, car.y) + SAFETY_CAR_AHEAD)
                        drawSafetyCar(point, 10.dp.toPx(), scLabel)
                    }
                }
            } else {
                cars.forEach { (car, p) ->
                    val r = dotSizeDp.dp.toPx()
                    val alpha = drawCar(timing, car, p, r)
                    if (car.number == stop?.number) drawCircle(Color.White, r + 4.dp.toPx(), p,
                        style = Stroke(2.dp.toPx()))
                    names[car.number]?.let {
                        drawText(it, topLeft = Offset(p.x + r + minOf(3.dp.toPx(), r),
                            p.y - it.size.height / 2f), alpha = alpha)
                    }
                }
                safetyCar?.let { drawSafetyCar(it, 10.dp.toPx(), scLabel) }
            }
            dynamicProbe.record(System.nanoTime() - start, movingCars?.size ?: cars.size,
                movingCars?.size ?: cars.size)
        }
    }
    if (targets != null) targets(remember(cars) { cars.map { (car, p) -> car.number to p } })
}

private val FlagYellow = Color(0xFFFFD60A)
private val FlagRed = Color(0xFFFF3B30)
/** How far ahead of the leader the safety car is drawn, as a fraction of the lap. */
private const val SAFETY_CAR_AHEAD = 0.015f

/** One marshal sector's stretch of the lap, and where it starts (as a lap fraction). */
private class SectorShape(val path: Path, val from: Float)

/**
 * Each marshal sector's stretch of the lap, keyed by sector number. [point] places a lap
 * fraction on screen; a sector runs from its own start to the next one's.
 */
private fun sectorPaths(timing: SessionTiming, progress: (Float, Float) -> Float, point: (Float) -> Offset): Map<Int, SectorShape> {
    val starts = timing.marshalSectors.sortedBy { it.number }.map { it.number to progress(it.x, it.y) }
    if (starts.size < 2) return emptyMap()
    return starts.mapIndexed { i, (number, from) ->
        var to = starts[(i + 1) % starts.size].second
        if (to <= from) to += 1f
        val steps = ((to - from) * 400).toInt().coerceAtLeast(6)
        number to SectorShape(
            Path().apply {
                for (k in 0..steps) {
                    val p = point(from + (to - from) * k / steps)
                    if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            },
            from,
        )
    }.toMap()
}

/**
 * Race control's flags over the drawn track: all of it red under a red flag, yellow under the
 * safety car or VSC (pulsing once it's coming in), otherwise just the yellow sectors (double
 * yellows thicker).
 */
private fun DrawScope.drawFlags(flags: FlagPicture, whole: Path, sectors: Map<Int, SectorShape>, width: Float, pulse: Float) {
    when (flags.track) {
        TrackCondition.RED -> drawPath(whole, FlagRed, style = Stroke(width))
        TrackCondition.CLEAR -> flags.yellow.forEach { (sector, level) ->
            sectors[sector]?.let {
                drawPath(it.path, FlagYellow, style = Stroke(if (level >= 2) width * 1.6f else width, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
        else -> drawPath(whole, FlagYellow, style = Stroke(width), alpha = if (flags.track.ending) pulse else 1f)
    }
}

/** A small flag on a pole, [size] tall, centred on [at]. */
private fun DrawScope.drawFlagGlyph(at: Offset, size: Float, color: Color, alpha: Float = 1f) {
    val w = size * 0.62f
    val left = at.x - w / 2
    val top = at.y - size / 2
    val cloth = Path().apply {
        moveTo(left, top)
        cubicTo(left + w * 0.35f, top - size * 0.08f, left + w * 0.65f, top + size * 0.1f, left + w, top)
        lineTo(left + w, top + size * 0.45f)
        cubicTo(left + w * 0.65f, top + size * 0.55f, left + w * 0.35f, top + size * 0.37f, left, top + size * 0.45f)
        close()
    }
    val shade = Color.Black.copy(alpha = 0.7f * alpha)
    val pole = size * 0.11f
    drawLine(shade, Offset(left, top), Offset(left, top + size), strokeWidth = pole + 3f)
    drawPath(cloth, shade, style = Stroke(3f))
    drawLine(color, Offset(left, top), Offset(left, top + size), strokeWidth = pole, alpha = alpha)
    drawPath(cloth, color, alpha = alpha)
}

/** A yellow flag where each yellow sector starts; [place] puts a lap fraction's flag on screen. */
private fun DrawScope.drawSectorGlyphs(flags: FlagPicture, sectors: Map<Int, SectorShape>, size: Float, place: (Float) -> Offset) {
    if (flags.track != TrackCondition.CLEAR) return
    flags.yellow.keys.forEach { sector -> sectors[sector]?.let { drawFlagGlyph(place(it.from), size, FlagYellow) } }
}

/**
 * The line at the foot of the map ("Red flag · Q3 resumes in 4:12"), centred on [x] with its foot
 * at [bottom], after a flag in the condition's colour. Pulses with the yellow when it's ending.
 */
private fun DrawScope.drawCaption(flags: FlagPicture, caption: TextLayoutResult?, x: Float, bottom: Float, pulse: Float) {
    caption ?: return
    val color = when (flags.track) {
        TrackCondition.RED -> FlagRed
        TrackCondition.CLEAR -> Color.White
        else -> FlagYellow
    }
    val alpha = if (flags.track.ending) pulse else 1f
    val glyph = if (flags.track != TrackCondition.CLEAR) caption.size.height * 0.9f else 0f
    val gap = if (glyph > 0f) 7.dp.toPx() else 0f
    val left = x - (glyph * 0.62f + gap + caption.size.width) / 2
    if (glyph > 0f) drawFlagGlyph(Offset(left + glyph * 0.31f, bottom - caption.size.height / 2f), glyph, color, alpha)
    drawText(
        caption,
        color = color,
        topLeft = Offset(left + glyph * 0.62f + gap, bottom - caption.size.height),
        alpha = alpha,
        shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f),
    )
}

/** The safety car: a yellow dot marked SC. F1 doesn't send its position, so it rides just ahead of the leader. */
private fun DrawScope.drawSafetyCar(at: Offset, radius: Float, label: TextLayoutResult) {
    drawCircle(Color.Black, radius + minOf(1.5.dp.toPx(), radius * .45f), at)
    drawCircle(FlagYellow, radius, at)
    drawText(label, color = Color.Black, topLeft = at - Offset(label.size.width / 2f, label.size.height / 2f))
}

/** The race leader's car number at [now], from the timing cards. */
internal fun leaderAt(timing: SessionTiming, now: Long): String? {
    // Use the same lap interval as DriverLaps.at(), but read only the required
    // position: full CardState construction also calculates unrelated lap,
    // sector and running-time fields. Preserve first-in-map tie breaking.
    var first: String? = null
    var minPosition = 0
    for ((number, laps) in timing.laps) {
        val index = laps.times.floorIndex(now)
        if (index < 0) continue
        val position = laps.states[index].position ?: continue
        if (first == null || position < minPosition) {
            first = number
            minPosition = position
        }
    }
    return first
}

/** The caption for [flags] at [now], measured once per change (the countdown ticks each second). */
@Composable
private fun rememberCaption(measurer: TextMeasurer, flags: FlagPicture, now: Long): TextLayoutResult? {
    val text = flags.caption(now)
    return remember(text) { text?.let { measurer.label(it, 15.sp) } }
}

private fun outward(p: Offset, center: Offset): Offset {
    val d = p - center
    val len = hypot(d.x, d.y)
    return if (len < 1f) Offset(0f, -1f) else d / len
}

/**
 * The lap unrolled onto a loop just inside the screen's edge, clockwise from start/finish at the
 * top center. The loop's corners follow the screen's own rounded corners. Shows running order,
 * gaps and overtakes without covering the feeds.
 */
internal class Ribbon(
    private val l: Float,
    private val t: Float,
    private val r: Float,
    private val b: Float,
    radius: Float,
    /** Which way cars travel round the loop; set to match the track map's direction. */
    private val clockwise: Boolean = true,
) {
    private val rr = radius.coerceIn(0f, minOf(r - l, b - t) / 2)
    private val top = (r - l) - 2 * rr
    private val side = (b - t) - 2 * rr
    private val arc = (Math.PI * rr / 2).toFloat()
    val perimeter = 2 * top + 2 * side + 4 * arc
    /** Start/finish sits at the middle of the top edge. */
    private val shift = top / 2

    /** Preserve the old exact ribbon geometry without allocating a Pair on every
     * marker and every inward-label lookup (24 drivers at up to 60 Hz). */
    private fun distance(f: Float): Float =
        ((if (clockwise) f else -f) * perimeter + shift).mod(perimeter)

    private fun cornerPoint(cx: Float, cy: Float, startDeg: Float, distance: Float): Offset {
        val angle = Math.toRadians((startDeg + 90f * distance / arc).toDouble())
        return Offset(cx + rr * kotlin.math.cos(angle).toFloat(),
            cy + rr * kotlin.math.sin(angle).toFloat())
    }

    private fun cornerInward(cx: Float, cy: Float, startDeg: Float, distance: Float): Offset {
        val p = cornerPoint(cx, cy, startDeg, distance)
        val normal = Offset(cx - p.x, cy - p.y)
        return if (rr > 0f) normal / rr else normal
    }

    fun point(f: Float): Offset {
        var d = distance(f)
        if (d < top) return Offset(l + rr + d, t)
        d -= top
        if (d < arc) return cornerPoint(r - rr, t + rr, -90f, d)
        d -= arc
        if (d < side) return Offset(r, t + rr + d)
        d -= side
        if (d < arc) return cornerPoint(r - rr, b - rr, 0f, d)
        d -= arc
        if (d < top) return Offset(r - rr - d, b)
        d -= top
        if (d < arc) return cornerPoint(l + rr, b - rr, 90f, d)
        d -= arc
        if (d < side) return Offset(l, b - rr - d)
        d -= side
        return cornerPoint(l + rr, t + rr, 180f, d)
    }

    /** Unit vector pointing into the screen. */
    fun inward(f: Float): Offset {
        var d = distance(f)
        if (d < top) return Offset(0f, 1f)
        d -= top
        if (d < arc) return cornerInward(r - rr, t + rr, -90f, d)
        d -= arc
        if (d < side) return Offset(-1f, 0f)
        d -= side
        if (d < arc) return cornerInward(r - rr, b - rr, 0f, d)
        d -= arc
        if (d < top) return Offset(0f, -1f)
        d -= top
        if (d < arc) return cornerInward(l + rr, b - rr, 90f, d)
        d -= arc
        if (d < side) return Offset(1f, 0f)
        d -= side
        return cornerInward(l + rr, t + rr, 180f, d)
    }

    fun path() = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(l, t, r, b, androidx.compose.ui.geometry.CornerRadius(rr, rr)))
    }
}

@Composable
fun EdgeMap(app: AppState, timing: SessionTiming, width: Float, height: Float, inset: Float, screenCornerRadius: Float) {
    val now = rememberMapRenderTime(app)
    val drawSampler = remember(timing, app.settings.nativeRustStitching) {
        OverlayPositionSampler(timing, OverlayPerformanceProbe("phone-edge-draw-position"), app.settings.nativeRustStitching)
    }
    val cars = if (app.settings.mapDriverSelection || app.pitDriver != null) {
        val hitSampler = remember(timing, app.settings.nativeRustStitching) {
            OverlayPositionSampler(timing, OverlayPerformanceProbe("phone-edge-position"), app.settings.nativeRustStitching)
        }
        remember(timing, now, hitSampler) {
            android.os.Trace.beginSection("PitwallEdgeCars")
            try { hitSampler.sample(now) } finally { android.os.Trace.endSection() }
        }
    } else emptyList()
    val drawClock = rememberOverlayDrawClock(timing, { app.mapTimeMs() ?: 0L },
        "phone-map-motion-clock")
    val motion = remember(timing, drawSampler, drawClock) {
        OverlayMotionSource(drawClock, drawSampler::sampleForDraw)
    }
    val stop = app.pitDriver?.let { driver ->
        val projection = remember(timing.geometry, app.settings.nativeRustStitching) {
            if (app.settings.nativeRustStitching) NativeCircuitProjection(timing.geometry) else null
        }
        val projected = projection?.project(cars) == true
        pitStopNow(rememberPitBasis(app, timing, now), driver, cars.mapIndexed { i, car ->
            car.number to if (projected) requireNotNull(projection).fractions[i]
                else timing.geometry.progress(car.x, car.y)
        })
    }
    EdgeDrawingContent(timing, width, height, inset, screenCornerRadius, app.mapOpacity, now,
        cars, stop, timing.flags.at(now, app.settings.nativeRustStitching), dotSizeDp = app.mapDotSizeDp, labelSizeSp = app.mapLabelSizeSp,
        targets = if (app.settings.mapDriverSelection) {
            { targets -> CarTargets(app, targets) }
        } else null, motion = motion, nativeRequested = app.settings.nativeRustStitching)
}

@Composable
internal fun EdgeDrawingContent(timing: SessionTiming, width: Float, height: Float, inset: Float,
    screenCornerRadius: Float, opacity: Float, now: Long, currentCars: List<Car>,
    stop: PitStopNow?, flags: FlagPicture, leader: String? = null,
    dotSizeDp: Float, labelSizeSp: Float,
    motion: OverlayMotionSource? = null,
    nativeRequested: Boolean = false,
    targets: (@Composable (List<Pair<String, Offset>>) -> Unit)? = null,
) {
    val fallback = with(LocalDensity.current) { 24.dp.toPx() }
    // The screen's own corner radius. Up to 0.15 it was concentric (half the inset smaller),
    // which read as a tighter curve than the glass.
    val ribbon = remember(width, height, inset, screenCornerRadius, fallback, timing.geometry.counterClockwise) {
        Ribbon(
            inset / 2, inset / 2, width - inset / 2, height - inset / 2,
            if (screenCornerRadius > 0f) screenCornerRadius else fallback,
            // Same direction the cars travel on the track map, whichever way the circuit runs.
            clockwise = !timing.geometry.counterClockwise,
        )
    }
    val nativeGeometry = remember(timing.geometry, nativeRequested) {
        if (nativeRequested) NativeCircuitProjection(timing.geometry) else null
    }
    fun progress(x: Float, y: Float) = nativeGeometry?.progress(x, y) ?: timing.geometry.progress(x, y)
    val measurer = rememberTextMeasurer()
    val names = remember(timing.drivers, labelSizeSp, measurer) {
        timing.drivers.mapValues { measurer.label(it.value.tla, labelSizeSp.sp) }
    }
    val turns = remember(timing.corners, timing.geometry, measurer, nativeGeometry) {
        timing.corners.map { progress(it.x, it.y) to measurer.label("T${it.number}", 8.sp, bold = false) }
    }
    val marks = remember(timing.geometry, timing.hasStartFinish, timing.startLine) { LineMarks(timing) }
    val finishLabel = remember(marks, measurer) { measurer.label(marks.finishLabel, 8.sp) }
    val startLabel = remember(measurer) { measurer.label("START", 8.sp) }
    val sectors = remember(ribbon, timing.geometry, timing.marshalSectors, nativeGeometry) { sectorPaths(timing, ::progress, ribbon::point) }
    val scLabel = remember(measurer) { measurer.label("SC", 7.sp) }
    val hitProjection = remember(timing.geometry, nativeRequested) { OverlayEdgeProjection(nativeRequested) }
    val cars = remember(timing.geometry, currentCars, hitProjection) {
        val count = hitProjection.update(currentCars, timing.geometry)
        List(count) { i -> hitProjection.car(i) to hitProjection.fraction(i) }
    }
    val pulse = rememberOverlayFlagPulse(flags.track.ending)
    val caption = rememberCaption(measurer, flags, now)
    // Leader and flags are part of the 10Hz metadata sample. Never redo 24
    // driver lap lookups on the 60Hz draw thread in either map style.
    val currentSafetyLeader = remember(timing, now, leader, flags.track.safetyCar) {
        if (flags.track.safetyCar) leader ?: leaderAt(timing, now) else null
    }
    val safetyCar = if (!flags.track.safetyCar) null else currentSafetyLeader
        ?.let { leader -> cars.firstOrNull { it.first.number == leader }?.second }
        ?.let { it + SAFETY_CAR_AHEAD }
    val pitLabel = remember(measurer) { measurer.label("PIT", 7.sp) }
    val stopLine = stop?.let { pitStopLine(timing, it) } ?: noPositionsLine(timing, now)
    val stopText = remember(stopLine, measurer) { stopLine?.let { measurer.label(it, 13.sp) } }
    val eventKey = remember(ribbon, nativeGeometry, measurer, sectors, flags.track, flags.yellow,
        flags.resumption?.announcedUtc, flags.resumption?.atUtc, flags.resumption?.what,
        caption, stop?.number, stop?.carProgress, stop?.ghostProgress,
        stop?.outBehind, stopText) { Any() }
    val eventProbe = remember(ribbon) { OverlayPerformanceProbe("edge-event-displaylist") }

    val line = remember(ribbon) { ribbon.path() }
    val staticProbe = remember(ribbon) { OverlayPerformanceProbe("edge-static-displaylist") }
    val dynamicProbe = remember(ribbon) { OverlayPerformanceProbe("edge-car-flag-draw") }
    val staticLayer = rememberGraphicsLayer()
    val staticValidity = remember(staticLayer) { OverlayRecordingValidity() }
    val eventLayer = rememberGraphicsLayer()
    val eventValidity = remember(eventLayer) { OverlayRecordingValidity() }
    val movingProjection = remember(ribbon, nativeRequested) { OverlayEdgeProjection(nativeRequested) }
    val reusedCollisionRows = remember(ribbon) { FloatArray(3) }
    // Track outline, turn glyphs and start/finish marks are independent of the moving cars.
    // Give them a separate retained Compose draw layer, as the regular map already does.
    val taken = remember(ribbon, marks, finishLabel, startLabel, timing.hasStartFinish) {
        val marksTaken = mutableListOf<Pair<Float, Float>>()
        if (timing.hasStartFinish) {
            marksTaken += 0f to finishLabel.size.width.toFloat()
            marks.start?.takeIf { !marks.together }?.let {
                marksTaken += it * ribbon.perimeter to startLabel.size.width.toFloat()
            }
        }
        marksTaken
    }
    val circuit: DrawScope.() -> Unit = remember(ribbon, line, turns, marks, finishLabel,
        startLabel, timing.hasStartFinish) {
        {
            drawPath(line, Color.Black.copy(alpha = 0.5f), style = Stroke(6.dp.toPx()))
            drawPath(line, Color.White.copy(alpha = 0.5f), style = Stroke(2.dp.toPx()))

            fun mark(f: Float, chequered: Boolean, label: TextLayoutResult) {
                val p = ribbon.point(f)
                val n = ribbon.inward(f)
                drawLineMark(p, n, Offset(-n.y, n.x), 8.dp.toPx(), chequered)
                val at = p + n * MARK_LABEL_DP.dp.toPx()
                drawText(label, topLeft = at - Offset(label.size.width / 2f, label.size.height / 2f))
            }
            if (timing.hasStartFinish) {
                mark(0f, chequered = true, finishLabel)
                marks.start?.takeIf { !marks.together }?.let { mark(it, chequered = false, startLabel) }
            }
            turns.forEach { (f, label) ->
                val p = ribbon.point(f)
                val n = ribbon.inward(f)
                drawLine(Color.White, p - n * 4.dp.toPx(), p + n * 4.dp.toPx(), 1.dp.toPx())
                val at = p + n * 13.dp.toPx()
                drawText(label, topLeft = at - Offset(label.size.width / 2f, label.size.height / 2f), alpha = 0.7f)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        val layer = Modifier.fillMaxSize().graphicsLayer {
            alpha = opacity
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
        Canvas(layer) {
            val start = System.nanoTime()
            val pixelWidth = size.width.toInt().coerceAtLeast(1)
            val pixelHeight = size.height.toInt().coerceAtLeast(1)
            var changed = 0
            if (staticValidity.needsRecord(circuit, pixelWidth, pixelHeight)) {
                staticLayer.record(density = this, layoutDirection = layoutDirection,
                    size = IntSize(pixelWidth, pixelHeight)) { circuit() }
                staticValidity.markRecorded(circuit, pixelWidth, pixelHeight)
                changed = 1
            } else staticValidity.markReplayed()
            drawLayer(staticLayer)
            staticProbe.record(System.nanoTime() - start, timing.corners.size, changed)
        }
        if (flags.track.ending) {
            val pulseProbe = remember(ribbon) { OverlayPerformanceProbe("edge-flag-pulse-draw") }
            Canvas(layer) {
                val started = System.nanoTime()
                val alpha = pulse.value
                drawFlags(flags, line, sectors, 2.5.dp.toPx(), alpha)
                drawCaption(flags, caption, width / 2,
                    height - inset / 2 - 56.dp.toPx(), alpha)
                pulseProbe.record(System.nanoTime() - started, 1, 1)
            }
        }
        Canvas(layer) {
            val start = System.nanoTime()
            val w = size.width.toInt().coerceAtLeast(1)
            val h = size.height.toInt().coerceAtLeast(1)
            var changed = 0
            if (eventValidity.needsRecord(eventKey, w, h)) {
                eventLayer.record(density = this, layoutDirection = layoutDirection,
                    size = IntSize(w, h)) {
                    if (!flags.track.ending) drawFlags(flags, line, sectors, 2.5.dp.toPx(), 1f)
                    drawSectorGlyphs(flags, sectors, 12.dp.toPx()) { f ->
                        ribbon.point(f) + ribbon.inward(f) * 12.dp.toPx()
                    }
                    if (!flags.track.ending) drawCaption(flags, caption, width / 2,
                        height - inset / 2 - 56.dp.toPx(), 1f)
                    stopText?.let { text ->
                        val above = caption?.let { it.size.height + 6.dp.toPx() } ?: 0f
                        drawText(text, topLeft = Offset(width / 2 - text.size.width / 2f,
                            height - inset / 2 - 56.dp.toPx() - above - text.size.height))
                    }
                    stop?.let {
                        drawPitStop(it, Color(timing.drivers[it.number]?.color
                            ?: android.graphics.Color.WHITE), pitLabel, ribbon::point)
                    }
                }
                eventValidity.markRecorded(eventKey, w, h)
                changed = 1
            } else eventValidity.markReplayed()
            drawLayer(eventLayer)
            eventProbe.record(System.nanoTime() - start, timing.marshalSectors.size, changed)
        }
        Canvas(layer) {
            val start = System.nanoTime()
            val movingCars = motion?.let { it.carsAt(it.positionMs.value) }
            // Labels step further inward when cars bunch up, so a pack stays readable.
            reusedCollisionRows.fill(-Float.MAX_VALUE)
            val lastAt = reusedCollisionRows
            val motionCount = if (movingCars == null) 0
                else movingProjection.updateForDraw(movingCars, timing.geometry)
            val count = if (movingCars == null) cars.size else motionCount
            for (index in 0 until count) {
                val number = if (movingCars == null) cars[index].first.number
                    else movingProjection.drawCar(index).number
                val state = if (movingCars == null) cars[index].first.state
                    else movingProjection.drawCar(index).state
                val f = if (movingCars == null) cars[index].second
                    else movingProjection.fraction(index)
                val p = ribbon.point(f)
                val radius = dotSizeDp.dp.toPx()
                val alpha = drawCar(timing, number, state, p, radius)
                if (number == stop?.number) drawCircle(Color.White,
                    radius + 4.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                val label = names[number] ?: continue
                val along = f * ribbon.perimeter
                val gap = label.size.width + 6.dp.toPx()
                var nearMark = false
                for (i in taken.indices) {
                    val mark = taken[i]
                    val distance = abs(along - mark.first)
                    if (minOf(distance, ribbon.perimeter - distance) <
                        (mark.second + label.size.width) / 2 + 4.dp.toPx()) {
                        nearMark = true
                        break
                    }
                }
                var level = 2
                for (row in 0..2) {
                    if ((row > 0 || !nearMark) && along - lastAt[row] > gap) {
                        level = row
                        break
                    }
                }
                lastAt[level] = along
                val at = p + ribbon.inward(f) * (14 + level * 13).dp.toPx()
                drawText(label, topLeft = at - Offset(label.size.width / 2f,
                    label.size.height / 2f), alpha = alpha)
            }
            if (movingCars != null && flags.track.safetyCar) {
                val safetyLeader = currentSafetyLeader
                movingCars.firstOrNull { it.number == safetyLeader }?.let { car ->
                    val f = progress(car.x, car.y) + SAFETY_CAR_AHEAD
                    drawSafetyCar(ribbon.point(f), 7.5.dp.toPx(), scLabel)
                }
            } else safetyCar?.let { drawSafetyCar(ribbon.point(it), 7.5.dp.toPx(), scLabel) }
            dynamicProbe.record(System.nanoTime() - start, count, count)
        }
    }
    if (targets != null) targets(remember(cars, ribbon) { cars.map { (car, f) -> car.number to ribbon.point(f) } })
}

/**
 * A stop now for the held car, on a map whose lap runs through [point]: the stretch of track it
 * would drop back over, shaded, and a ring where it would come out.
 */
internal fun DrawScope.drawPitStop(stop: PitStopNow, color: Color, label: TextLayoutResult, point: (Float) -> Offset) {
    val span = (stop.carProgress - stop.ghostProgress).mod(1f)
    val path = Path()
    for (i in 0..PIT_STEPS) {
        val p = point(stop.ghostProgress + span * i / PIT_STEPS)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, PIT_PURPLE.copy(alpha = 0.6f), style = Stroke(10.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    val out = point(stop.ghostProgress)
    drawCircle(Color.Black.copy(alpha = 0.6f), 8.dp.toPx(), out)
    drawCircle(color, 7.dp.toPx(), out, style = Stroke(2.5.dp.toPx()))
    drawText(label, topLeft = out - Offset(label.size.width / 2f, label.size.height + 10.dp.toPx()))
}

/** "If HAM stops now, out 2.1 s behind NOR": the line under a map while a car is held. */
/**
 * Live, before the first car positions: F1's live timing only sends what happens from the moment
 * Apexview joins, and F1 publishes the earlier part once the session is over.
 */
internal fun noPositionsLine(timing: SessionTiming, now: Long): String? {
    if (timing.archivePartial && timing.firstTrackUtc != null &&
        (now < timing.firstTrackUtc || now > requireNotNull(timing.lastTrackUtc))) {
        return "Loading driver positions for this replay point…"
    }
    if (!timing.beforePositions(now)) return null
    val minutes = ((timing.positionsFromUtc!! - now) / 60_000L).coerceAtLeast(1)
    return "No car positions for the next $minutes min, until Apexview joined live timing"
}

internal fun pitStopLine(timing: SessionTiming, stop: PitStopNow): String {
    fun tla(number: String) = timing.drivers[number]?.tla ?: number
    return "If ${tla(stop.number)} stops now" + (stop.outBehind?.let { (n, ms) -> ", out %.1f s behind %s".format(ms / 1000f, tla(n)) } ?: "")
}

private const val PIT_STEPS = 48
internal val PIT_PURPLE = Color(0xFF7A3E9D)
