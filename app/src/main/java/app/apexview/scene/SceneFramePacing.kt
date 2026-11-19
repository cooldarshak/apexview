package app.apexview.scene

/** GL-thread-only presentation counters. Media3's decoder-drop count alone misses jitter. */
internal class SceneFramePacing {
    data class Snapshot(
        val latched: Long,
        val presented: Long,
        val callbackGaps: Long,
        val displayGaps: Long,
        val slowSwaps: Long,
        val slowDraws: Long,
        val slowLatches: Long,
        val maxCallbackGapMs: Long,
        val maxDisplayGapMs: Long,
        val maxSwapMs: Long,
        val maxDrawMs: Long,
        val maxLatchMs: Long,
        val maxVsyncCallbackDelayMs: Long,
        val surfaceTimestampRegressions: Long,
    )

    private var lastLatchNs = 0L
    private var lastPresentNs = 0L
    private var latched = 0L
    private var presented = 0L
    private var callbackGaps = 0L
    private var displayGaps = 0L
    private var slowSwaps = 0L
    private var slowDraws = 0L
    private var slowLatches = 0L
    private var maxCallbackGapMs = 0L
    private var maxDisplayGapMs = 0L
    private var maxSwapMs = 0L
    private var maxDrawMs = 0L
    private var maxLatchMs = 0L
    private var maxVsyncCallbackDelayMs = 0L
    private var lastSurfaceTimestampNs = 0L
    private var surfaceTimestampRegressions = 0L

    fun onTextureLatched(nowNs: Long, latchDurationNs: Long = 0L, surfaceTimestampNs: Long = 0L) {
        if (lastLatchNs > 0 && nowNs > lastLatchNs) {
            val gapMs = (nowNs - lastLatchNs) / 1_000_000L
            maxCallbackGapMs = maxOf(maxCallbackGapMs, gapMs)
            if (gapMs > 45) callbackGaps++
        }
        lastLatchNs = nowNs
        latched++
        val latchMs = latchDurationNs.coerceAtLeast(0L) / 1_000_000L
        maxLatchMs = maxOf(maxLatchMs, latchMs)
        if (latchMs > 18L) slowLatches++
        if (lastSurfaceTimestampNs > 0L && surfaceTimestampNs > 0L && surfaceTimestampNs < lastSurfaceTimestampNs) {
            surfaceTimestampRegressions++
        }
        if (surfaceTimestampNs > 0L) lastSurfaceTimestampNs = surfaceTimestampNs
    }

    fun onDisplayFrame(frameTimeNs: Long, swapNs: Long, newVideo: Boolean,
                       drawNs: Long = 0L, callbackStartNs: Long = frameTimeNs) {
        val swapMs = swapNs.coerceAtLeast(0L) / 1_000_000L
        maxSwapMs = maxOf(maxSwapMs, swapMs)
        if (swapMs > 18) slowSwaps++
        val drawMs = drawNs.coerceAtLeast(0L) / 1_000_000L
        maxDrawMs = maxOf(maxDrawMs, drawMs)
        if (drawMs > 18L) slowDraws++
        if (callbackStartNs >= frameTimeNs) {
            maxVsyncCallbackDelayMs = maxOf(maxVsyncCallbackDelayMs,
                (callbackStartNs - frameTimeNs) / 1_000_000L)
        }
        if (!newVideo) return
        if (lastPresentNs > 0 && frameTimeNs > lastPresentNs) {
            val gapMs = (frameTimeNs - lastPresentNs) / 1_000_000L
            maxDisplayGapMs = maxOf(maxDisplayGapMs, gapMs)
            if (gapMs > 45) displayGaps++
        }
        lastPresentNs = frameTimeNs
        presented++
    }

    fun snapshotAndReset(): Snapshot {
        val result = Snapshot(latched, presented, callbackGaps, displayGaps, slowSwaps,
            slowDraws, slowLatches, maxCallbackGapMs, maxDisplayGapMs, maxSwapMs,
            maxDrawMs, maxLatchMs, maxVsyncCallbackDelayMs, surfaceTimestampRegressions)
        // Retain timestamps so a boundary between windows does not hide a long stall.
        latched = 0; presented = 0; callbackGaps = 0; displayGaps = 0; slowSwaps = 0
        slowDraws = 0; slowLatches = 0
        maxCallbackGapMs = 0; maxDisplayGapMs = 0; maxSwapMs = 0
        maxDrawMs = 0; maxLatchMs = 0; maxVsyncCallbackDelayMs = 0; surfaceTimestampRegressions = 0
        return result
    }

    fun reset() {
        lastLatchNs = 0; lastPresentNs = 0; lastSurfaceTimestampNs = 0
        snapshotAndReset()
    }
}
