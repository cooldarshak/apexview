package app.apexview

import app.apexview.tiled.CmafCaptureLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Main-looper playback transitions. Detached capture workers drain before their backing data is reset. */
internal class PlaybackWorkCoordinator(
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Capture(
        val source: CmafCaptureLifecycle,
        val previousCleanup: CompletableDeferred<Unit>?,
        val afterDrain: () -> Unit,
    ) {
        val cleanup = CompletableDeferred<Unit>()
        var job: Job? = null
    }

    private var currentCapture: Capture? = null
    private var latestCleanup: CompletableDeferred<Unit>? = null
    val captureJob: Job? get() = currentCapture?.job
    val capture: CmafCaptureLifecycle? get() = currentCapture?.source
    private var retryJob: Job? = null
    private var seekJob: Job? = null
    private var offerJob: Job? = null
    val offerMutex = Mutex()
    var castRestartJob: Job? = null
        private set
    val pendingSeek = ControlSeekBatch()

    fun prepareCapture(source: CmafCaptureLifecycle, afterDrain: () -> Unit = {}) {
        cancelCapture()
        currentCapture = Capture(source, latestCleanup?.takeUnless { it.isCompleted }, afterDrain).also {
            latestCleanup = it.cleanup
        }
    }

    fun launchCapture(scope: CoroutineScope, work: suspend CoroutineScope.() -> Unit) {
        val owner = checkNotNull(currentCapture)
        check(owner.job == null)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                owner.previousCleanup?.await()
                if (currentCapture === owner) work()
            } finally {
                if (currentCapture === owner) currentCapture = null
            }
        }
        owner.job = job
        job.invokeOnCompletion { drain(owner) }
        job.start()
    }

    fun cancelCapture() {
        val old = currentCapture ?: return
        currentCapture = null
        val job = old.job
        if (job == null) drain(old) else job.cancel()
    }

    private fun drain(owner: Capture) {
        cleanupScope.launch {
            owner.previousCleanup?.await()
            try { owner.source.reset() } finally { owner.afterDrain() }
        }.invokeOnCompletion { owner.cleanup.complete(Unit) }
    }

    fun launchOffer(scope: CoroutineScope, work: suspend CoroutineScope.() -> Unit) {
        cancelOffer()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { work() } finally { if (offerJob === coroutineContext[Job]) offerJob = null }
        }
        offerJob = job
        job.start()
    }

    fun cancelOffer() {
        val old = offerJob
        offerJob = null
        old?.cancel()
    }

    fun scheduleRetry(scope: CoroutineScope, delayMs: Long, retry: () -> Unit) {
        cancelRetry()
        retryJob = scope.launch {
            delay(delayMs)
            retryJob = null
            retry()
        }
    }

    fun cancelRetry() {
        val old = retryJob
        retryJob = null
        old?.cancel()
    }

    fun coalesceSeek(scope: CoroutineScope, targetMs: Long, commit: (Long) -> Unit) {
        if (!pendingSeek.offer(targetMs)) return
        seekJob = scope.launch {
            delay(ControlSeekBatch.WINDOW_MS)
            val latest = pendingSeek.take()
            seekJob = null
            if (latest != null) commit(latest)
        }
    }

    fun scheduleCastRestart(scope: CoroutineScope, restart: () -> Unit) {
        cancelCastRestart()
        castRestartJob = scope.launch {
            delay(1_000)
            castRestartJob = null
            restart()
        }
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
