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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive

/** Main-looper playback transitions. Detached capture workers drain before their backing data is reset. */
internal class PlaybackWorkCoordinator(
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Capture(
        val source: CmafCaptureLifecycle,
        val previousCleanup: CompletableDeferred<Boolean>?,
        val afterDrain: () -> Unit,
    ) {
        val cleanup = CompletableDeferred<Boolean>()
        var job: Job? = null
    }

    private var currentCapture: Capture? = null
    private var latestCleanup: CompletableDeferred<Boolean>? = null
    val captureJob: Job? get() = currentCapture?.job
    val capture: CmafCaptureLifecycle? get() = currentCapture?.source
    private var retryJob: Job? = null
    private var seekJob: Job? = null
    private var qualityRestartJob: Job? = null
    private var offerJob: Job? = null
    val offerMutex = Mutex()
    private val offerLifecycleMutex = Mutex()
    var castRestartJob: Job? = null
        private set
    val pendingSeek = ControlSeekBatch()

    fun prepareCapture(source: CmafCaptureLifecycle, afterDrain: () -> Unit = {}) {
        cancelCapture()
        currentCapture = Capture(source, latestCleanup, afterDrain).also {
            latestCleanup = it.cleanup
        }
    }

    fun launchCapture(scope: CoroutineScope, onRetirementFailure: () -> Unit = {},
                      work: suspend CoroutineScope.() -> Unit) {
        val owner = checkNotNull(currentCapture)
        check(owner.job == null)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val retired = owner.previousCleanup?.await() != false
                if (currentCapture === owner) {
                    if (retired) work() else onRetirementFailure()
                }
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
            val prior = owner.previousCleanup?.await() != false
            // Every independent release runs even when another fails. Keep
            // failure sticky for this process owner; do not start another
            // capture on a graph whose previous backing may still be leased.
            val source = runCatching { owner.source.reset() }
            val extra = runCatching { owner.afterDrain() }
            owner.cleanup.complete(prior && source.isSuccess && extra.isSuccess)
        }.invokeOnCompletion { owner.cleanup.complete(false) }
    }

    fun launchOffer(scope: CoroutineScope, work: suspend CoroutineScope.() -> Unit) {
        cancelOffer()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Cancellation is a request, not proof that the previous offer's IO has drained.
                offerLifecycleMutex.withLock {
                    coroutineContext.ensureActive()
                    if (offerJob === coroutineContext[Job]) work()
                }
            } finally { if (offerJob === coroutineContext[Job]) offerJob = null }
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

    /** One pending quality replacement on the main looper. The first request
     * establishes the short bounded window; subsequent choices replace its
     * callback without opening another capture or decoder. */
    private var pendingQualityRestart: (() -> Unit)? = null

    fun coalesceQualityRestart(scope: CoroutineScope, restart: () -> Unit) {
        pendingQualityRestart = restart
        if (qualityRestartJob?.isActive == true) return
        qualityRestartJob = scope.launch(start = CoroutineStart.LAZY) {
            delay(150L)
            val latest = pendingQualityRestart
            pendingQualityRestart = null
            qualityRestartJob = null
            latest?.invoke()
        }
        qualityRestartJob?.start()
    }

    fun cancelQualityRestart() {
        val old = qualityRestartJob
        qualityRestartJob = null
        pendingQualityRestart = null
        old?.cancel()
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
