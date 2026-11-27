package app.apexview

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import app.apexview.tiled.HevcArchiveVodSession

/** Keep all Media3 allocator, start, rebuffer, and back-buffer behavior.
 * Only stop further loading sooner when measured archive delivery proves a
 * conservative forward horizon. Never drop buffered samples or change DRM. */
@OptIn(UnstableApi::class)
internal class ArchiveMeasuredLoadControl(
    private val delegate: LoadControl,
    private val policy: ArchiveForwardBufferPolicy,
) : LoadControl by delegate {
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
        val target = if (archive && parameters.playWhenReady && !parameters.rebuffering &&
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
            android.util.Log.i("PitwallPlayback", "ARCHIVE_MEDIA3_BUFFER_POLICY targetUs=${target.highUs} lowUs=${target.lowUs} basis=MEASURED_VIDEO_AUDIO")
        }
        if (parameters.bufferedDurationUs >= target.highUs) held = true
        if (parameters.bufferedDurationUs < target.lowUs) held = false
        return normal && !held
    }
}
