package app.apexview.scene

import app.apexview.TimingBuckets

/**
 * Bounded codec PTS -> SurfaceTexture -> EGL swap correlation.
 * Never interprets swap submission as physical SurfaceFlinger presentation.
 * Only timestamps are retained, no protected samples or DRM data.
 */
internal class SceneCodecTimestampCorrelation {
    private companion object { const val CAPACITY = 128 }
    @Volatile var active = false
    private val pts = LongArray(CAPACITY) { Long.MIN_VALUE }
    private val releases = LongArray(CAPACITY)
    private val callbacks = LongArray(CAPACITY)
    private var next = 0
    private var pendingLatch = 0L
    private var codecCount = 0L
    private var matched = 0L
    private var unmatched = 0L
    private var submitted = 0L
    private val plannedToLatch = TimingBuckets()
    private val callbackToLatch = TimingBuckets()
    private val latchToSwap = TimingBuckets()

    @Synchronized fun onCodecFrame(ptsUs: Long, plannedNs: Long, callbackNs: Long) {
        if (!active || ptsUs < 0 || ptsUs > Long.MAX_VALUE / 1000L) return
        val slot = next % CAPACITY
        pts[slot] = ptsUs * 1000L
        releases[slot] = plannedNs
        callbacks[slot] = callbackNs
        next++
        codecCount++
    }

    @Synchronized fun onTextureLatched(timestampNs: Long, nowNs: Long) {
        if (!active) return
        pendingLatch = nowNs
        if (timestampNs > 0) for (offset in 0 until minOf(next, CAPACITY)) {
            val slot = (next - 1 - offset) % CAPACITY
            if (pts[slot] != timestampNs) continue
            pts[slot] = Long.MIN_VALUE
            if (releases[slot] in 1L..nowNs) plannedToLatch.record(nowNs - releases[slot])
            if (callbacks[slot] in 1L..nowNs) callbackToLatch.record(nowNs - callbacks[slot])
            matched++
            return
        }
        // A decoder or OEM can rewrite SurfaceTexture timestamps. Do not claim
        // a codec-to-video match if the exact PTS is not present in our bounded ring.
        unmatched++
    }

    @Synchronized fun onSwapSubmitted(nowNs: Long) {
        if (!active || pendingLatch <= 0) return
        if (nowNs >= pendingLatch) {
            latchToSwap.record(nowNs - pendingLatch)
            submitted++
        }
        pendingLatch = 0
    }

    data class Snapshot(
        val codecFrames: Long, val matchedLatches: Long, val unmatchedLatches: Long,
        val swapsAfterLatch: Long, val releaseToLatch: TimingBuckets.Snapshot,
        val callbackToLatch: TimingBuckets.Snapshot, val latchToSwap: TimingBuckets.Snapshot,
    )

    @Synchronized fun snapshotAndReset(): Snapshot {
        val snapshot = Snapshot(codecCount, matched, unmatched, submitted,
            plannedToLatch.snapshotAndReset(), callbackToLatch.snapshotAndReset(),
            latchToSwap.snapshotAndReset())
        codecCount = 0
        matched = 0
        unmatched = 0
        submitted = 0
        return snapshot
    }

    /** A seek, rendition change or EGL epoch must never reuse old PTS mappings. */
    @Synchronized fun reset() {
        pts.fill(Long.MIN_VALUE)
        releases.fill(0)
        callbacks.fill(0)
        next = 0
        pendingLatch = 0
        snapshotAndReset()
    }
}
