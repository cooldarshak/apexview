package app.apexview

import android.graphics.RenderNode
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos

/**
 * Pure cache invalidation contract used by both retained map display lists and
 * native tower RenderNodes. A new key OR pixel-size change always re-records.
 * The cache token is ordinary owned data, never mutable Compose snapshot state.
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
        owner !== key || width != pixelWidth || height != pixelHeight

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
internal class RetainedTowerRenderNode {
    val node = RenderNode("PitwallTowerRow")
    val validity = OverlayRecordingValidity()
    fun beginIfChanged(key: Any, width: Int, height: Int): AndroidCanvas? {
        // Android can evict a RenderNode's display list while the row is offscreen.
        // Never replay a vanished list: refresh it even when the visual key is stable.
        if (!validity.needsRecord(key, width, height) && node.hasDisplayList()) {
            validity.markReplayed()
            return null
        }
        node.setPosition(0, 0, width, height)
        return node.beginRecording(width, height)
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
            withFrameNanos { frame ->
                if (isOverlayDrawFrameDue(frame, previousFrame)) {
                    val started = System.nanoTime()
                    val videoPosition = clock()
                    val changed = if (videoPosition != position.longValue) 1 else 0
                    position.longValue = videoPosition
                    previousFrame = frame
                    probe.record(System.nanoTime() - started, 1, changed)
                }
            }
        }
    }
    return position
}

/** Local sources and Pitwall Cast frames expose exactly the same draw API. */
internal class OverlayMotionSource(
    val positionMs: State<Long>,
    val carsAt: (Long) -> List<Car>,
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
