package app.apexview

import app.apexview.tiled.CmafCaptureLifecycle
import kotlinx.coroutines.Job

/** Main-thread owner of composition, retry and exact seek work. */
internal class PlaybackWorkCoordinator {
    var captureJob: Job? = null
    var capture: CmafCaptureLifecycle? = null
    var retryJob: Job? = null
    var seekJob: Job? = null
    var castRestartJob: Job? = null
    val pendingSeek = ControlSeekBatch()

    fun cancelCapture() {
        val old = captureJob
        captureJob = null
        old?.cancel()
    }
    fun cancelRetry() {
        val old = retryJob
        retryJob = null
        old?.cancel()
    }
    fun cancelCastRestart() {
        val old = castRestartJob
        castRestartJob = null
        old?.cancel()
    }
    fun cancelControlSeek() {
        val old = seekJob
        seekJob = null
        pendingSeek.clear()
        old?.cancel()
        cancelCastRestart()
    }
}
