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
    private var firstDraws = 0
    private var missedReports = 0
    private var maxNs = 0L
    private val fallbackDeadlineNs = (1_000_000_000.0 / (window.decorView.display?.refreshRate ?: 60f)).toLong()

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        if (!DiagnosticLogs.recording) {
            reset(0L)
            return@OnFrameMetricsAvailableListener
        }
        val now = SystemClock.elapsedRealtime()
        if (started == 0L) started = now
        val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
        frames++
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) firstDraws++
        else if (duration > (deadline.takeIf { it > 0 } ?: fallbackDeadlineNs)) slow++
        missedReports += dropped
        maxNs = maxOf(maxNs, duration)
        if (now - started >= 2_000) {
            Log.i("PitwallFrames", "role=$role uptimeMs=$now intervalMs=${now - started} frames=$frames " +
                "slow=$slow firstDraws=$firstDraws missedReports=$missedReports maxFrameMs=${maxNs / 1_000_000}")
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
        firstDraws = 0
        missedReports = 0
        maxNs = 0L
    }

    override fun close() {
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }
}
