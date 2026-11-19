package app.apexview.scene

/** GL-thread-only presentation counters. Media3's decoder-drop count alone misses jitter. */
internal class SceneFramePacing {
    data class Snapshot(
        val latched: Long,
        val presented: Long,
        val callbackGaps: Long,
        val displayGaps: Long,
        val slowSwaps: Long,
        val maxCallbackGapMs: Long,
        val maxDisplayGapMs: Long,
        val maxSwapMs: Long,
    )

    private var lastLatchNs = 0L
    private var lastPresentNs = 0L
    private var latched = 0L
    private var presented = 0L
    private var callbackGaps = 0L
    private var displayGaps = 0L
    private var slowSwaps = 0L
    private var maxCallbackGapMs = 0L
    private var maxDisplayGapMs = 0L
    private var maxSwapMs = 0L

    fun onTextureLatched(nowNs: Long) {
        if (lastLatchNs > 0 && nowNs > lastLatchNs) {
            val gapMs = (nowNs - lastLatchNs) / 1_000_000L
            maxCallbackGapMs = maxOf(maxCallbackGapMs, gapMs)
            if (gapMs > 45) callbackGaps++
        }
        lastLatchNs = nowNs
        latched++
    }

    fun onDisplayFrame(frameTimeNs: Long, swapNs: Long, newVideo: Boolean) {
        val swapMs = swapNs.coerceAtLeast(0L) / 1_000_000L
        maxSwapMs = maxOf(maxSwapMs, swapMs)
        if (swapMs > 18) slowSwaps++
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
            maxCallbackGapMs, maxDisplayGapMs, maxSwapMs)
        // Retain timestamps so a boundary between windows does not hide a long stall.
        latched = 0; presented = 0; callbackGaps = 0; displayGaps = 0; slowSwaps = 0
        maxCallbackGapMs = 0; maxDisplayGapMs = 0; maxSwapMs = 0
        return result
    }

    fun reset() {
        lastLatchNs = 0; lastPresentNs = 0
        snapshotAndReset()
    }
}
