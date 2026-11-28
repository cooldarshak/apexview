package app.apexview

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import app.apexview.tiled.HevcArchiveVodSession

/** Keep all Media3 allocator, start, rebuffer, and back-buffer behavior.
 * Only stop further loading sooner when measured archive delivery proves a
 * conservative forward horizon. Never drop buffered samples or change DRM. */
@OptIn(UnstableApi::class)
internal class ArchiveMeasuredLoadControl(
    private val delegate: LoadControl,
    private val policy: ArchiveForwardBufferPolicy,
) : LoadControl {
    // Media3 1.11.1 uses Java default methods for the PlayerId-aware
    // lifecycle and back-buffer API. Kotlin interface delegation is not
    // sufficient: a default can dispatch to the legacy throwing stub.
    // Explicitly forward every current player-facing method, including
    // the two methods ExoPlayer calls inside its constructor.
    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(parameters)
    override fun shouldContinuePreloading(
        playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)


    private val period = Timeline.Period()
    private val window = Timeline.Window()
    private var held = false
    private var generation = Long.MIN_VALUE
    private var reportedTarget = -1L

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val normal = delegate.shouldContinueLoading(parameters)
        val now = android.os.SystemClock.elapsedRealtime()
        val index = parameters.timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
        val archive = index >= 0 && parameters.timeline.getWindow(
            parameters.timeline.getPeriod(index, period).windowIndex, window
        ).mediaItem.localConfiguration?.uri == HevcArchiveVodSession.MANIFEST_URI
        val rebufferRecently = parameters.lastRebufferRealtimeMs != C.TIME_UNSET &&
            now - parameters.lastRebufferRealtimeMs < 120_000
        // Pausing or losing the local output must not silently replace an
        // already measured forward horizon with the default 50-second target.
        // No samples are discarded; rebuffer/retry/unknown lanes still fail open.
        val target = if (archive && !parameters.rebuffering &&
            !rebufferRecently && parameters.targetLiveOffsetUs == C.TIME_UNSET)
            policy.target(now, parameters.playbackSpeed) else null
        if (target == null) {
            held = false
            generation = Long.MIN_VALUE
            if (reportedTarget != -1L) {
                android.util.Log.i("PitwallPlayback", "ARCHIVE_MEDIA3_BUFFER_POLICY targetUs=DEFAULT")
                reportedTarget = -1
            }
            return normal
        }
        if (generation != target.generation) { generation = target.generation; held = false }
        if (reportedTarget != target.highUs) {
            reportedTarget = target.highUs
            android.util.Log.i("PitwallPlayback", "ARCHIVE_MEDIA3_BUFFER_POLICY targetUs=${target.highUs} lowUs=${target.lowUs} basis=MEASURED_SELECTED_CHILDREN_AND_UPSTREAM")
        }
        if (parameters.bufferedDurationUs >= target.highUs) held = true
        if (parameters.bufferedDurationUs < target.lowUs) held = false
        return normal && !held
    }
}
