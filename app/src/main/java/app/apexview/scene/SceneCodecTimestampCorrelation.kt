package app.apexview.scene

import app.apexview.TimingBuckets

/**
 * Bounded exact codec PTS or planned release timestamp -> SurfaceTexture -> EGL swap correlation.
 * Never interprets swap submission as physical SurfaceFlinger presentation.
 * Only timestamps are retained, no protected samples or DRM data.
 */
internal class SceneCodecTimestampCorrelation {
    private companion object {
        const val CAPACITY = 128
        // Even with reordered looper callbacks, metadata whose *planned* codec
        // release is over two 50fps frames after an already latched image
        // cannot safely be attributed to that old image with a reused PTS.
        const val MAX_FUTURE_PLANNED_RELEASE_NS = 40_000_000L
    }
    @Volatile var active = false
    private var surfaceOwner: Any? = null
    private val pipelineHealth = ScenePipelineHealth()
    private var pipelineRevision = 0L

    fun allowsAcceleratedOutput(nowNs: Long): Boolean = active && pipelineHealth.allows(nowNs)

    @Synchronized fun resetPipelineHealth(owner: Any?) {
        if (owner != null && surfaceOwner === owner) {
            pipelineRevision++
            pipelineHealth.reset()
        }
    }

    @Synchronized fun pipelineWindowRevision(owner: Any?): Long =
        if (owner != null && surfaceOwner === owner && active) pipelineRevision else -1L

    @Synchronized fun recordPipelineWindow(owner: Any?, nowNs: Long, durationNs: Long,
                                            expectedFps: Float, metrics: SceneFramePacing.Snapshot,
                                            revision: Long) {
        if (owner == null || surfaceOwner !== owner || !active || revision != pipelineRevision) return
        pipelineHealth.observe(nowNs, durationNs, expectedFps, metrics.latched,
            metrics.presented, metrics.callbackGaps, metrics.displayGaps, metrics.slowSwaps,
            metrics.slowDraws, metrics.slowLatches, metrics.unavailableDraws)
    }

    // The Media3 application looper installs the actual decoder output surface.
    // A retired renderer's GL callbacks must not reset or consume a newer surface's data.
    @Synchronized fun attach(owner: Any) {
        if (surfaceOwner === owner && active) return
        reset()
        surfaceOwner = owner
        active = true
    }
    @Synchronized fun detach(owner: Any) {
        if (surfaceOwner !== owner) return
        shutdown()
    }
    @Synchronized fun shutdown() {
        active = false
        surfaceOwner = null
        reset()
    }
    @Synchronized fun reset(owner: Any?) {
        if (owner != null && surfaceOwner === owner) reset()
    }
    @Synchronized fun onTextureLatched(owner: Any?, timestampNs: Long, nowNs: Long) {
        if (owner != null && surfaceOwner === owner) onTextureLatched(timestampNs, nowNs)
    }
    @Synchronized fun onSwapSubmitted(owner: Any?, nowNs: Long) {
        if (owner != null && surfaceOwner === owner) onSwapSubmitted(nowNs)
    }
    /** The independent input may latch a newer image while the sampled image is swapping. */
    @Synchronized fun onSampledSwapSubmitted(owner: Any?, nowNs: Long, sampledLatchNs: Long) {
        if (owner == null || surfaceOwner !== owner || !active || sampledLatchNs <= 0L ||
            sampledLatchNs <= lastSubmittedLatchNs || nowNs < sampledLatchNs) return
        latchToSwap.record(nowNs - sampledLatchNs)
        submitted++
        lastSubmittedLatchNs = sampledLatchNs
        if (pendingLatch == sampledLatchNs) pendingLatch = 0L
    }
    @Synchronized fun snapshotAndReset(owner: Any?): Snapshot? =
        if (owner != null && surfaceOwner === owner && active) snapshotAndReset() else null
    private val pts = LongArray(CAPACITY) { Long.MIN_VALUE }
    private val releases = LongArray(CAPACITY)
    private val callbacks = LongArray(CAPACITY)
    private var next = 0
    private var pendingLatch = 0L
    private var lastSubmittedLatchNs = 0L
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
    private var ptsOnly = 0L
    private var releaseOnly = 0L
    private var bothDomains = 0L
    private var ambiguous = 0L
    private var duplicate = 0L
    private var lastMatchedTimestamp = Long.MIN_VALUE
    private var lastObservedLatchNs = Long.MIN_VALUE

    private fun candidate(timestamp: Long, ptsNs: Long, plannedNs: Long, latchNs: Long): Boolean =
        timestamp > 0L && (timestamp == ptsNs || (plannedNs > 0L && timestamp == plannedNs)) &&
            !(plannedNs > latchNs && plannedNs - latchNs > MAX_FUTURE_PLANNED_RELEASE_NS)

    private fun recordMatch(timestamp: Long, ptsNs: Long, plannedNs: Long, callbackNs: Long, latchNs: Long) {
        if (timestamp == ptsNs && timestamp == plannedNs) bothDomains++
        else if (timestamp == ptsNs) ptsOnly++
        else releaseOnly++
        if (plannedNs in 1L..latchNs) plannedToLatch.record(latchNs - plannedNs)
        if (callbackNs in 1L..latchNs) callbackToLatch.record(latchNs - callbackNs)
        matched++
    }
    private val plannedToLatch = TimingBuckets()
    private val callbackToLatch = TimingBuckets()
    private val latchToSwap = TimingBuckets()

    @Synchronized fun onCodecFrame(ptsUs: Long, plannedNs: Long, callbackNs: Long) {
        if (!active || ptsUs < 0 || ptsUs > Long.MAX_VALUE / 1000L) return
        val ptsNs = ptsUs * 1000L
        codecCount++
        // Accept only an exact, unique candidate across both timestamp domains.
        // Never infer an offset or pair by callback arrival order.
        var candidateIndex = -1
        var candidateCount = 0
        for (offset in 0 until minOf(nextEarlyLatch, CAPACITY)) {
            val index = (nextEarlyLatch - 1 - offset) % CAPACITY
            if (!candidate(earlyLatchPts[index], ptsNs, plannedNs, earlyLatchTimes[index])) continue
            candidateIndex = index
            candidateCount++
        }
        if (candidateCount == 1) {
            val timestamp = earlyLatchPts[candidateIndex]
            val latchedAtNs = earlyLatchTimes[candidateIndex]
            earlyLatchPts[candidateIndex] = Long.MIN_VALUE
            recordMatch(timestamp, ptsNs, plannedNs, callbackNs, latchedAtNs)
            if (lastObservedLatchNs == latchedAtNs) lastMatchedTimestamp = timestamp
            lateMatched++
            return
        }
        if (candidateCount > 1) ambiguous++
        val slot = next % CAPACITY
        pts[slot] = ptsNs
        releases[slot] = plannedNs
        callbacks[slot] = callbackNs
        next++
    }

    @Synchronized fun onTextureLatched(timestampNs: Long, nowNs: Long) {
        if (!active) return
        // SurfaceTexture may deliver the same image more than once. A repeat
        // of an already matched timestamp cannot claim another queued frame.
        if (timestampNs > 0 && timestampNs == lastMatchedTimestamp) {
            duplicate++
            unmatched++
            return
        }
        lastObservedLatchNs = nowNs
        pendingLatch = nowNs
        var candidateSlot = -1
        var candidateCount = 0
        if (timestampNs > 0) for (offset in 0 until minOf(next, CAPACITY)) {
            val slot = (next - 1 - offset) % CAPACITY
            if (pts[slot] == Long.MIN_VALUE ||
                !candidate(timestampNs, pts[slot], releases[slot], nowNs)) continue
            candidateSlot = slot
            candidateCount++
        }
        if (candidateCount == 1) {
            recordMatch(timestampNs, pts[candidateSlot], releases[candidateSlot], callbacks[candidateSlot], nowNs)
            pts[candidateSlot] = Long.MIN_VALUE
            lastMatchedTimestamp = timestampNs
            return
        }
        if (candidateCount > 1) {
            // Do not retain an ambiguous latch for later opportunistic matching.
            ambiguous++
            unmatched++
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
        val ptsOnlyMatches: Long, val releaseOnlyMatches: Long, val bothDomainMatches: Long,
        val ambiguousMatches: Long, val duplicateLatches: Long,
    )

    @Synchronized fun snapshotAndReset(): Snapshot {
        val outstanding = earlyLatchPts.count { it != Long.MIN_VALUE }
        val snapshot = Snapshot(codecCount, matched, unmatched, submitted,
            plannedToLatch.snapshotAndReset(), callbackToLatch.snapshotAndReset(),
            latchToSwap.snapshotAndReset(), lateMatched, outstanding,
            ptsOnly, releaseOnly, bothDomains, ambiguous, duplicate)
        codecCount = 0
        matched = 0
        unmatched = 0
        lateMatched = 0
        submitted = 0
        ptsOnly = 0; releaseOnly = 0; bothDomains = 0; ambiguous = 0; duplicate = 0
        return snapshot
    }

    /** A seek, rendition change or EGL epoch must never reuse old PTS mappings. */
    @Synchronized fun reset() {
        pipelineRevision++
        pipelineHealth.reset()
        pts.fill(Long.MIN_VALUE)
        releases.fill(0)
        callbacks.fill(0)
        next = 0
        pendingLatch = 0
        lastSubmittedLatchNs = 0L
        lastMatchedTimestamp = Long.MIN_VALUE
        lastObservedLatchNs = Long.MIN_VALUE
        earlyLatchPts.fill(Long.MIN_VALUE)
        earlyLatchTimes.fill(0)
        nextEarlyLatch = 0
        snapshotAndReset()
    }
}
