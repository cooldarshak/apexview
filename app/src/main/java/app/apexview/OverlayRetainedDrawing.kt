package app.apexview

import android.graphics.RenderNode
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

/**
 * Pure cache invalidation contract used by both retained map display lists and
 * native tower RenderNodes. A new key OR pixel-size change always re-records.
 * The cache token is ordinary owned data, never mutable Compose snapshot state.
 * Immutable, structurally equal visual keys must reuse the recording even when
 * recreated by a timing snapshot. Identity-only comparisons were forcing the
 * same static layers to re-record, adding pressure to the shared phone/TV UI.
 */
internal class OverlayRecordingValidity {
    private var owner: Any? = null
    private var width = -1
    private var height = -1
    var recordings = 0L
        private set
    var replays = 0L
        private set

    fun needsRecord(key: Any, pixelWidth: Int, pixelHeight: Int): Boolean =
        owner != key || width != pixelWidth || height != pixelHeight

    fun markRecorded(key: Any, pixelWidth: Int, pixelHeight: Int) {
        owner = key
        width = pixelWidth
        height = pixelHeight
        recordings++
    }
    fun markReplayed() { replays++ }
}

/**
 * Hardware-backed native tower row cache. Build the RenderNode only when its
 * style, timing fields or pixel dimensions change. The drawing fallback uses
 * the exact same Android Canvas commands when hardware acceleration is off.
 */
/**
 * The base region includes the full-width highlight; sectors and tyres never
 * draw beyond their narrow column bounds. Physical pixel bounds intentionally
 * overlap by ~1dp at edges so antialiasing and font scaling cannot clip labels.
 */
internal data class TowerRegionBounds(val left: Int, val right: Int) {
    val width: Int get() = right - left
}
internal fun towerRegionBounds(region: Int, rowWidthPx: Int, unit: Float): TowerRegionBounds {
    require(region in 0..2 && rowWidthPx > 0 && unit > 0f)
    if (region == 0) return TowerRegionBounds(0, rowWidthPx)
    val from = if (region == 1) 199f else 329f
    val to = if (region == 1) 333f else 363f
    val left = kotlin.math.floor(from * unit).toInt().coerceIn(0, rowWidthPx - 1)
    val right = kotlin.math.ceil(to * unit).toInt().coerceIn(left + 1, rowWidthPx)
    return TowerRegionBounds(left, right)
}

internal class RetainedTowerRenderNode {
    val node = RenderNode("PitwallTowerRow")
    val validity = OverlayRecordingValidity()
    fun beginIfChanged(key: Any, width: Int, height: Int,
        region: Int = 0, unit: Float = 1f
    ): AndroidCanvas? {
        // Android may evict lists while rows are scrolled off-screen.
        if (!validity.needsRecord(key, width, height) && node.hasDisplayList()) {
            validity.markReplayed()
            return null
        }
        // Calculate bounds only when re-recording: steady 50/60 Hz replay
        // must not allocate region descriptors per driver per frame.
        val bounds = towerRegionBounds(region, width, unit)
        node.setPosition(bounds.left, 0, bounds.right, height)
        return node.beginRecording(bounds.width, height).also {
            // Use the original full-row coordinates in a bounded native node.
            it.translate(-bounds.left.toFloat(), 0f)
        }
    }
    fun finish(key: Any, width: Int, height: Int) {
        node.endRecording()
        validity.markRecorded(key, width, height)
    }
    fun discard() {
        node.discardDisplayList()
    }
}

/** Keep playback motion state out of the Composition read phase. */
internal fun isOverlayDrawFrameDue(frameNanos: Long, previousNanos: Long): Boolean =
    previousNanos == Long.MIN_VALUE || frameNanos < previousNanos ||
        frameNanos - previousNanos >= OVERLAY_DRAW_INTERVAL_NS

private const val OVERLAY_DRAW_INTERVAL_NS = 15_000_000L // 60 Hz cap on 120 Hz panels

/** Skip an immediately discarded 120Hz callback, subtracting time already
 * spent in the current frame. Keep the 15ms gate and the video clock intact. */
internal fun overlayDrawRearmDelayMs(frameNanos: Long, nowNanos: Long): Long {
    if (nowNanos < frameNanos) return 0L
    val elapsed = nowNanos - frameNanos
    return ((9_000_000L - elapsed).coerceAtLeast(0L) + 999_999L) / 1_000_000L
}

/**
 * The caller MUST pass the State itself to Canvas; only the Canvas draw lambda
 * reads .value. This schedules draw invalidation rather than recomposition.
 */
@Composable
internal fun rememberOverlayDrawClock(
    owner: Any, readVideoTimeMs: () -> Long,
    probeOwner: String = "overlay-motion-clock"
): State<Long> {
    val clock by rememberUpdatedState(readVideoTimeMs)
    val position = remember(owner) { mutableLongStateOf(clock()) }
    val probe = remember(probeOwner) { OverlayPerformanceProbe(probeOwner) }
    LaunchedEffect(owner) {
        var previousFrame = Long.MIN_VALUE
        while (true) {
            val rearm = withFrameNanos { frame ->
                if (isOverlayDrawFrameDue(frame, previousFrame)) {
                    val started = System.nanoTime()
                    val videoPosition = clock()
                    val changed = if (videoPosition != position.longValue) 1 else 0
                    position.longValue = videoPosition
                    previousFrame = frame
                    probe.record(System.nanoTime() - started, 1, changed)
                    overlayDrawRearmDelayMs(frame, System.nanoTime())
                } else 0L
            }
            if (rearm > 0L) delay(rearm)
        }
    }
    return position
}

/** Local sources and Pitwall Cast frames expose exactly the same draw API. */
internal class OverlayMotionSource(
    val positionMs: State<Long>,
    // List is covariant: both allocation-bounded local draw markers and the
    // immutable Cast packet car snapshots execute on this same motion path.
    val carsAt: (Long) -> List<OverlayMotionCar>,
)

/** Three independently recorded tower regions: lap/telemetry, sectors and tyres. */
internal class RetainedTowerRowLayers {
    val regions = arrayOf(
        RetainedTowerRenderNode(),
        RetainedTowerRenderNode(),
        RetainedTowerRenderNode(),
    )
    fun discard() = regions.forEach { it.discard() }
}

/** Draw-phase flag animation, independently invalidated from the retained flag/event layer. */
internal const val OVERLAY_FLAG_PULSE_HALF_NS = 1_100_000_000L

internal fun overlayFlagPulseAlpha(elapsedNs: Long): Float {
    val cycleNs = OVERLAY_FLAG_PULSE_HALF_NS * 2
    val phase = (elapsedNs.coerceAtLeast(0L) % cycleNs).toFloat() / OVERLAY_FLAG_PULSE_HALF_NS
    val portion = if (phase <= 1f) phase else 2f - phase
    return 0.3f + 0.7f * EaseOut.transform(portion)
}

/** Pass this State into Canvas; read .value only inside the Canvas draw lambda. */
@Composable
internal fun rememberOverlayFlagPulse(active: Boolean): State<Float> {
    val pulse = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(active) {
        if (!active) {
            pulse.floatValue = 1f
            return@LaunchedEffect
        }
        pulse.floatValue = 0.3f
        var beginning = Long.MIN_VALUE
        var previous = Long.MIN_VALUE
        while (true) {
            val rearm = withFrameNanos { frame ->
                if (isOverlayDrawFrameDue(frame, previous)) {
                    if (beginning == Long.MIN_VALUE) beginning = frame
                    pulse.floatValue = overlayFlagPulseAlpha(frame - beginning)
                    previous = frame
                    overlayDrawRearmDelayMs(frame, System.nanoTime())
                } else 0L
            }
            if (rearm > 0L) delay(rearm)
        }
    }
    return pulse
}
