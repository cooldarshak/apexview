package app.apexview.scene

import app.apexview.TimingBuckets

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
        val noNewVideoDraws: Long,
        val pacingDeferrals: Long,
        val callbackIntervals: TimingBuckets.Snapshot,
        val textureTimestampIntervals: TimingBuckets.Snapshot,
        val displayIntervals: TimingBuckets.Snapshot,
        val allVsyncIntervals: TimingBuckets.Snapshot,
        val eglBindDurations: TimingBuckets.Snapshot,
        val latchDurations: TimingBuckets.Snapshot,
        val glDrawDurations: TimingBuckets.Snapshot,
        val eglSwapDurations: TimingBuckets.Snapshot,
        val callbackDelays: TimingBuckets.Snapshot,
        val glQueueDelays: TimingBuckets.Snapshot,
    )

    private var lastCallbackNs = 0L
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
    private var noNewVideoDraws = 0L
    private var pacingDeferrals = 0L
    private var lastVsyncNs = 0L
    private val callbackIntervals = TimingBuckets()
    private val textureTimestampIntervals = TimingBuckets()
    private val displayIntervals = TimingBuckets()
    private val allVsyncIntervals = TimingBuckets()
    private val eglBindDurations = TimingBuckets()
    private val latchDurations = TimingBuckets()
    private val glDrawDurations = TimingBuckets()
    private val eglSwapDurations = TimingBuckets()
    private val callbackDelays = TimingBuckets()
    private val glQueueDelays = TimingBuckets()

    /** Measures handler enqueue-to-run delay, not SurfaceFlinger presentation latency. */
    fun onGlQueueProbe(delayNs: Long) { glQueueDelays.record(delayNs) }

    fun onTextureLatched(nowNs: Long, latchDurationNs: Long = 0L, surfaceTimestampNs: Long = 0L,
                         eglBindNs: Long = 0L, callbackArrivalNs: Long = nowNs) {
        if (lastCallbackNs > 0 && callbackArrivalNs > lastCallbackNs) {
            callbackIntervals.record(callbackArrivalNs - lastCallbackNs)
            val gapMs = (callbackArrivalNs - lastCallbackNs) / 1_000_000L
            maxCallbackGapMs = maxOf(maxCallbackGapMs, gapMs)
            if (gapMs > 45) callbackGaps++
        }
        lastCallbackNs = callbackArrivalNs
        latched++
        val latchMs = latchDurationNs.coerceAtLeast(0L) / 1_000_000L
        latchDurations.record(latchDurationNs)
        eglBindDurations.record(eglBindNs)
        maxLatchMs = maxOf(maxLatchMs, latchMs)
        if (latchMs > 18L) slowLatches++
        if (lastSurfaceTimestampNs > 0L && surfaceTimestampNs > 0L && surfaceTimestampNs < lastSurfaceTimestampNs) {
            surfaceTimestampRegressions++
        }
        if (lastSurfaceTimestampNs > 0L && surfaceTimestampNs > lastSurfaceTimestampNs) {
            textureTimestampIntervals.record(surfaceTimestampNs - lastSurfaceTimestampNs)
        }
        if (surfaceTimestampNs > 0L) lastSurfaceTimestampNs = surfaceTimestampNs
    }

    fun onDisplayFrame(frameTimeNs: Long, swapNs: Long, newVideo: Boolean,
                       drawNs: Long = 0L, callbackStartNs: Long = frameTimeNs) {
        if (lastVsyncNs > 0 && frameTimeNs > lastVsyncNs) allVsyncIntervals.record(frameTimeNs - lastVsyncNs)
        lastVsyncNs = frameTimeNs
        eglSwapDurations.record(swapNs)
        val swapMs = swapNs.coerceAtLeast(0L) / 1_000_000L
        maxSwapMs = maxOf(maxSwapMs, swapMs)
        if (swapMs > 18) slowSwaps++
        val drawMs = drawNs.coerceAtLeast(0L) / 1_000_000L
        glDrawDurations.record(drawNs)
        maxDrawMs = maxOf(maxDrawMs, drawMs)
        if (drawMs > 18L) slowDraws++
        if (callbackStartNs >= frameTimeNs) {
            callbackDelays.record(callbackStartNs - frameTimeNs)
            maxVsyncCallbackDelayMs = maxOf(maxVsyncCallbackDelayMs,
                (callbackStartNs - frameTimeNs) / 1_000_000L)
        }
        if (!newVideo) { noNewVideoDraws++; return }
        if (lastPresentNs > 0 && frameTimeNs > lastPresentNs) {
            displayIntervals.record(frameTimeNs - lastPresentNs)
            val gapMs = (frameTimeNs - lastPresentNs) / 1_000_000L
            maxDisplayGapMs = maxOf(maxDisplayGapMs, gapMs)
            if (gapMs > 45) displayGaps++
        }
        lastPresentNs = frameTimeNs
        presented++
    }

    fun onPacingDeferred() { pacingDeferrals++ }

    fun snapshotAndReset(): Snapshot {
        val result = Snapshot(latched, presented, callbackGaps, displayGaps, slowSwaps,
            slowDraws, slowLatches, maxCallbackGapMs, maxDisplayGapMs, maxSwapMs,
            maxDrawMs, maxLatchMs, maxVsyncCallbackDelayMs, surfaceTimestampRegressions,
            noNewVideoDraws, pacingDeferrals, callbackIntervals.snapshotAndReset(), textureTimestampIntervals.snapshotAndReset(),
            displayIntervals.snapshotAndReset(), allVsyncIntervals.snapshotAndReset(),
            eglBindDurations.snapshotAndReset(), latchDurations.snapshotAndReset(),
            glDrawDurations.snapshotAndReset(), eglSwapDurations.snapshotAndReset(),
            callbackDelays.snapshotAndReset(), glQueueDelays.snapshotAndReset())
        // Retain timestamps so a boundary between windows does not hide a long stall.
        latched = 0; presented = 0; callbackGaps = 0; displayGaps = 0; slowSwaps = 0
        slowDraws = 0; slowLatches = 0
        noNewVideoDraws = 0
        pacingDeferrals = 0
        maxCallbackGapMs = 0; maxDisplayGapMs = 0; maxSwapMs = 0
        maxDrawMs = 0; maxLatchMs = 0; maxVsyncCallbackDelayMs = 0; surfaceTimestampRegressions = 0
        return result
    }

    fun reset() {
        lastCallbackNs = 0; lastPresentNs = 0; lastSurfaceTimestampNs = 0; lastVsyncNs = 0
        snapshotAndReset()
    }
}
