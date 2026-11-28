package app.apexview

/**
 * The Media3 video-output scheduling clock, independent of SurfaceTexture and GL timings.
 * These are planned codec release times, not evidence that SurfaceFlinger presented a frame.
 * Recording is on the renderer thread; epoch invalidation also runs on the player thread.
 */
internal class CodecReleaseTiming {
    private var previousPtsUs = -1L
    private var previousReleaseNs = 0L
    private var frames = 0L
    private var lateCallbacks = 0L
    private val ptsIntervals = TimingBuckets()
    private val releaseIntervals = TimingBuckets()
    private val releaseLead = TimingBuckets()

    @Synchronized fun record(presentationTimeUs: Long, plannedReleaseNs: Long, observedNs: Long) {
        if (previousPtsUs >= 0 && presentationTimeUs > previousPtsUs) {
            val intervalUs = presentationTimeUs - previousPtsUs
            if (intervalUs <= Long.MAX_VALUE / 1000L) ptsIntervals.record(intervalUs * 1000L)
        }
        if (previousReleaseNs > 0 && plannedReleaseNs > previousReleaseNs) {
            releaseIntervals.record(plannedReleaseNs - previousReleaseNs)
        }
        if (plannedReleaseNs >= observedNs) releaseLead.record(plannedReleaseNs - observedNs)
        else lateCallbacks++
        previousPtsUs = presentationTimeUs
        previousReleaseNs = plannedReleaseNs
        frames++
    }

    @Synchronized fun resetEpoch() {
        previousPtsUs = -1L
        previousReleaseNs = 0L
        snapshotAndReset()
    }

    @Synchronized fun snapshotAndReset(): Snapshot {
        val result = Snapshot(frames, lateCallbacks, ptsIntervals.snapshotAndReset(),
            releaseIntervals.snapshotAndReset(), releaseLead.snapshotAndReset())
        frames = 0L
        lateCallbacks = 0L
        return result
    }

    data class Snapshot(
        val frames: Long,
        val callbacksAfterPlannedRelease: Long,
        val ptsIntervals: TimingBuckets.Snapshot,
        val plannedReleaseIntervals: TimingBuckets.Snapshot,
        val plannedReleaseLead: TimingBuckets.Snapshot,
    )
}
