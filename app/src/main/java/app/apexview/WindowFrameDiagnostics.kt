package app.apexview

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window

/** Summarizes window render times independently of the video decoder counters. */
internal class WindowFrameDiagnostics(private val window: Window, private val role: String = "player") : AutoCloseable {
    private val thread = HandlerThread("PitwallFrameMetrics").apply { start() }
    private var started = 0L
    private var frames = 0
    private var slow = 0
    private var frozen = 0
    private var firstDraws = 0
    private var missedReports = 0
    private var maxNs = 0L
    // The refresh mode may change during a session. A deadline fixed at
    // construction misclassifies UI frames after phone/TV mode transitions.
    private fun fallbackDeadlineNs(): Long = FrameDeadlinePolicy.durationNs(
        window.decorView.display?.refreshRate ?: 60f
    )
    private val frameDurations = TimingBuckets()
    private val layoutDurations = TimingBuckets()
    private val drawDurations = TimingBuckets()
    private val syncDurations = TimingBuckets()
    private val commandDurations = TimingBuckets()
    private val swapDurations = TimingBuckets()
    private val gpuDurations = TimingBuckets()
    private val windowVsyncIntervals = TimingBuckets()
    private var previousWindowVsyncNs = 0L
    private var lastFrameTimelineVsyncId = -1L
    private var frameTimelineIdGaps = 0
    private var deadlineMissNs = 0L
    private var longestDeadlineMissNs = 0L

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        if (!DiagnosticLogs.recording) {
            reset(0L)
            return@OnFrameMetricsAvailableListener
        }
        val now = SystemClock.elapsedRealtime()
        if (started == 0L) started = now
        val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val frameVsyncNs = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        if (previousWindowVsyncNs > 0L && frameVsyncNs > previousWindowVsyncNs) {
            windowVsyncIntervals.record(frameVsyncNs - previousWindowVsyncNs)
        }
        if (frameVsyncNs > 0L) previousWindowVsyncNs = frameVsyncNs
        if (Build.VERSION.SDK_INT >= 36) {
            // FRAME_TIMELINE_VSYNC_ID is metric 14 on API 36; compileSdk is 35.
            val id = metrics.getMetric(14)
            if (id > 0L) {
                if (lastFrameTimelineVsyncId > 0L && id > lastFrameTimelineVsyncId + 1) frameTimelineIdGaps++
                lastFrameTimelineVsyncId = id
            }
        }
        val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
        frames++
        frameDurations.record(duration)
        layoutDurations.record(metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
        drawDurations.record(metrics.getMetric(FrameMetrics.DRAW_DURATION))
        syncDurations.record(metrics.getMetric(FrameMetrics.SYNC_DURATION))
        commandDurations.record(metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION))
        swapDurations.record(metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION))
        if (Build.VERSION.SDK_INT >= 31) gpuDurations.record(metrics.getMetric(FrameMetrics.GPU_DURATION))
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) firstDraws++
        else {
            val budget = deadline.takeIf { it > 0 } ?: fallbackDeadlineNs()
            if (duration > budget) {
                slow++
                deadlineMissNs += duration - budget
                longestDeadlineMissNs = maxOf(longestDeadlineMissNs, duration - budget)
            }
            if (duration >= 700_000_000L) frozen++
        }
        missedReports += dropped
        maxNs = maxOf(maxNs, duration)
        if (now - started >= 2_000) {
            val total = frameDurations.snapshotAndReset()
            val layout = layoutDurations.snapshotAndReset()
            val draw = drawDurations.snapshotAndReset()
            val sync = syncDurations.snapshotAndReset()
            val command = commandDurations.snapshotAndReset()
            val swap = swapDurations.snapshotAndReset()
            val gpu = gpuDurations.snapshotAndReset()
            val windowVsync = windowVsyncIntervals.snapshotAndReset()
            Log.i("PitwallFrames", "role=$role uptimeMs=$now intervalMs=${now - started} frames=$frames " +
                "slow=$slow frozen700ms=$frozen firstDraws=$firstDraws missedReports=$missedReports " +
                "refreshRate=${window.decorView.display?.refreshRate ?: 0f} maxFrameMs=${maxNs / 1_000_000} " +
                "frameMeanMs=${total.meanMs.toInt()} frameP95UpperMs=${total.percentileUpperMs(95)} " +
                "frameP99UpperMs=${total.percentileUpperMs(99)} frameBinsMs13_19_23_30_45_100=${total.compact()} " +
                "windowIntendedVsyncBins13_19_23_30_45_100=${windowVsync.compact()} " +
                "lastFrameTimelineVsyncId=$lastFrameTimelineVsyncId frameTimelineIdGaps=$frameTimelineIdGaps " +
                "deadlineMissTotalMs=${deadlineMissNs / 1_000_000} deadlineMissMaxMs=${longestDeadlineMissNs / 1_000_000} " +
                "layoutTotalMs=${layout.totalMs.toInt()} layoutMaxMs=${layout.maxMs.toInt()} " +
                "drawTotalMs=${draw.totalMs.toInt()} drawMaxMs=${draw.maxMs.toInt()} " +
                "syncTotalMs=${sync.totalMs.toInt()} syncMaxMs=${sync.maxMs.toInt()} " +
                "commandTotalMs=${command.totalMs.toInt()} commandMaxMs=${command.maxMs.toInt()} " +
                "swapTotalMs=${swap.totalMs.toInt()} swapMaxMs=${swap.maxMs.toInt()} " +
                "gpuTotalMs=${gpu.totalMs.toInt()} gpuMaxMs=${gpu.maxMs.toInt()} " +
                "gpuAvailable=${Build.VERSION.SDK_INT >= 31}")
            reset(now)
        }
    }

    init {
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    }

    private fun reset(now: Long) {
        started = now
        frames = 0
        slow = 0
        frozen = 0
        firstDraws = 0
        missedReports = 0
        maxNs = 0L
        deadlineMissNs = 0L
        longestDeadlineMissNs = 0L
        // Also discard a partial interval when recording is toggled off.
        frameDurations.snapshotAndReset()
        layoutDurations.snapshotAndReset()
        drawDurations.snapshotAndReset()
        syncDurations.snapshotAndReset()
        commandDurations.snapshotAndReset()
        swapDurations.snapshotAndReset()
        gpuDurations.snapshotAndReset()
        windowVsyncIntervals.snapshotAndReset()
        previousWindowVsyncNs = 0L
        lastFrameTimelineVsyncId = -1L
        frameTimelineIdGaps = 0
    }

    override fun close() {
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }
}
