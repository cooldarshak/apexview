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
    // Media3 frame metadata and the SurfaceTexture listener run on different
    // loopers. An OEM may deliver the texture before its matching metadata.
    // Retain bounded unmatched PTS and reconcile the later callback exactly.
    private val earlyLatchPts = LongArray(CAPACITY) { Long.MIN_VALUE }
    private val earlyLatchTimes = LongArray(CAPACITY)
    private var nextEarlyLatch = 0
    private var codecCount = 0L
    private var matched = 0L
    private var unmatched = 0L
    private var lateMatched = 0L
    private var submitted = 0L
    private val plannedToLatch = TimingBuckets()
    private val callbackToLatch = TimingBuckets()
    private val latchToSwap = TimingBuckets()

    @Synchronized fun onCodecFrame(ptsUs: Long, plannedNs: Long, callbackNs: Long) {
        if (!active || ptsUs < 0 || ptsUs > Long.MAX_VALUE / 1000L) return
        val ptsNs = ptsUs * 1000L
        codecCount++
        // A texture may have been latched before Media3's metadata callback.
        // Attribute it to the exact PTS, not to callback arrival order. When
        // the callback follows the latch, callback-to-latch is negative and
        // must NOT be recorded as a measured latency.
        for (offset in 0 until minOf(nextEarlyLatch, CAPACITY)) {
            val index = (nextEarlyLatch - 1 - offset) % CAPACITY
            if (earlyLatchPts[index] != ptsNs) continue
            earlyLatchPts[index] = Long.MIN_VALUE
            val latchedAtNs = earlyLatchTimes[index]
            if (plannedNs in 1L..latchedAtNs) plannedToLatch.record(latchedAtNs - plannedNs)
            if (callbackNs in 1L..latchedAtNs) callbackToLatch.record(latchedAtNs - callbackNs)
            matched++
            lateMatched++
            return
        }
        val slot = next % CAPACITY
        pts[slot] = ptsNs
        releases[slot] = plannedNs
        callbacks[slot] = callbackNs
        next++
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
        // Missing *at latch time* is not necessarily a permanent mismatch:
        // Media3's callback can arrive later on another looper. Keep this
        // separate from successful late matches and outstanding PTS.
        unmatched++
        if (timestampNs > 0) {
            val slot = nextEarlyLatch % CAPACITY
            earlyLatchPts[slot] = timestampNs
            earlyLatchTimes[slot] = nowNs
            nextEarlyLatch++
        }
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
        val lateMatchedLatches: Long, val outstandingLatches: Int,
    )

    @Synchronized fun snapshotAndReset(): Snapshot {
        val outstanding = earlyLatchPts.count { it != Long.MIN_VALUE }
        val snapshot = Snapshot(codecCount, matched, unmatched, submitted,
            plannedToLatch.snapshotAndReset(), callbackToLatch.snapshotAndReset(),
            latchToSwap.snapshotAndReset(), lateMatched, outstanding)
        codecCount = 0
        matched = 0
        unmatched = 0
        lateMatched = 0
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
        earlyLatchPts.fill(Long.MIN_VALUE)
        earlyLatchTimes.fill(0)
        nextEarlyLatch = 0
        snapshotAndReset()
    }
}
