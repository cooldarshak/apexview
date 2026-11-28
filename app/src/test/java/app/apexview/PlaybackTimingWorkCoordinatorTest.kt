package app.apexview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlaybackTimingWorkCoordinatorTest {
    @Test fun partialReplayWithResolvedPathCanLoadCarDataIndependently() {
        assertTrue(TimingWorkCoordinator.canRequestArchiveSpeeds(false, "https://example.invalid/archive/"))
        assertFalse(TimingWorkCoordinator.canRequestArchiveSpeeds(true, "https://example.invalid/archive/"))
        assertFalse(TimingWorkCoordinator.canRequestArchiveSpeeds(false, ""))
    }

    @Test fun reopeningSameContentInvalidatesTimingAndSpeedOwners() {
        val work = TimingWorkCoordinator()
        val first = work.begin(42)
        val timing = Job()
        val speed = Job()
        work.timingJob = timing
        val request = requireNotNull(work.beginSpeed("race"))
        work.speedJob = speed
        val second = work.begin(42)
        assertNotSame(first, second)
        assertFalse(work.owns(first))
        assertTrue(work.owns(second))
        assertTrue(timing.isCancelled)
        assertTrue(speed.isCancelled)
        val current = requireNotNull(work.beginSpeed("race"))
        assertFalse(work.completeSpeed(request, emptyMap()))
        assertFalse(work.failSpeed(request))
        assertEquals("race", work.speedsFor)
        assertTrue(work.completeSpeed(current, emptyMap()))
        work.close()
        assertFalse(work.owns(second))
        assertNull(work.contentId)
        assertNull(work.speedsFor)
    }
    @Test fun failedSpeedLoadCanRetryButDuplicateCannot() {
        val work = TimingWorkCoordinator()
        work.begin(7)
        val first = requireNotNull(work.beginSpeed("race"))
        assertNull(work.beginSpeed("race"))
        assertTrue(work.failSpeed(first))
        val second = requireNotNull(work.beginSpeed("race"))
        assertFalse(work.completeSpeed(first, emptyMap()))
        assertTrue(work.completeSpeed(second, emptyMap()))
    }
    @Test fun cancelSeekClearsTheLatestTargetAndStillAllowsAReplacementSeek() = runBlocking {
        val work = PlaybackWorkCoordinator(this)
        val cancelled = CompletableDeferred<Long>()
        work.coalesceSeek(this, 5000) { cancelled.complete(it) }
        work.scheduleCastRestart(this) { cancelled.complete(0) }
        work.cancelControlSeek()
        assertNull(work.pendingSeek.targetMs)
        assertNull(work.castRestartJob)
        val result = CompletableDeferred<Long>()
        work.coalesceSeek(this, 6000) { result.complete(it) }
        work.coalesceSeek(this, 7001) { error("A coalesced seek must use its existing owner") }
        assertEquals(7001L, withTimeout(1500) { result.await() })
        assertFalse(cancelled.isCompleted)
    }

    @Test fun rapidQualityChoicesUseOnlyTheLatestCallbackAndStopCancelsPending() = runBlocking {
        val work = PlaybackWorkCoordinator(this)
        val committed = mutableListOf<Int>()
        val result = CompletableDeferred<Unit>()
        work.coalesceQualityRestart(this) { committed += 1080 }
        work.coalesceQualityRestart(this) { committed += 540 }
        work.coalesceQualityRestart(this) { committed += 720; result.complete(Unit) }
        withTimeout(1500) { result.await() }
        assertEquals(listOf(720), committed)
        work.coalesceQualityRestart(this) { committed += 360 }
        work.cancelQualityRestart()
        kotlinx.coroutines.delay(200)
        assertEquals(listOf(720), committed)
    }

    @Test fun replacingOfferWaitsForCancelledIoFinally() = runBlocking {
        val work = PlaybackWorkCoordinator(this)
        val started = CompletableDeferred<Unit>()
        val drain = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val replacement = CompletableDeferred<Unit>()
        work.launchOffer(this) {
            started.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { drain.await(); finished.complete(Unit) }
            }
        }
        started.await()
        work.launchOffer(this) { error("superseded offer ran") }
        work.launchOffer(this) { assertTrue(finished.isCompleted); replacement.complete(Unit) }
        yield()
        assertFalse(replacement.isCompleted)
        drain.complete(Unit)
        withTimeout(1500) { replacement.await() }
    }

    @Test fun failedRetirementBlocksReplacementButRunsIndependentCleanup() = runBlocking {
        val work = PlaybackWorkCoordinator(this)
        val extra = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val nextReset = CompletableDeferred<Unit>()
        val first = object : app.apexview.tiled.CmafCaptureLifecycle {
            override fun reset() { error("vendor failed to retire") }
        }
        val second = object : app.apexview.tiled.CmafCaptureLifecycle {
            override fun reset() { nextReset.complete(Unit) }
        }
        work.prepareCapture(first) { extra.complete(Unit) }
        work.prepareCapture(second)
        work.launchCapture(this, onRetirementFailure = { blocked.complete(Unit) }) {
            error("replacement ran before verified retirement")
        }
        withTimeout(1500) { blocked.await(); extra.await(); nextReset.await() }
    }

    @Test fun captureIsRetiredOnlyAfterItsOldWorkerStopsAndReplacementWaits() = runBlocking {
        val work = PlaybackWorkCoordinator(this)
        val firstStarted = CompletableDeferred<Unit>()
        val firstFinished = CompletableDeferred<Unit>()
        val allowDrain = CompletableDeferred<Unit>()
        val firstErased = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val first = object : app.apexview.tiled.CmafCaptureLifecycle {
            override fun reset() { assertTrue(firstFinished.isCompleted); firstErased.complete(Unit) }
        }
        val second = object : app.apexview.tiled.CmafCaptureLifecycle {
            override fun reset() = Unit
        }
        work.prepareCapture(first)
        work.launchCapture(this) {
            firstStarted.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { allowDrain.await(); firstFinished.complete(Unit) }
            }
        }
        firstStarted.await()
        work.prepareCapture(second)
        work.launchCapture(this) { replacementStarted.complete(Unit) }
        yield()
        assertSame(second, work.capture)
        assertFalse(firstErased.isCompleted)
        assertFalse(replacementStarted.isCompleted)
        allowDrain.complete(Unit)
        withTimeout(1500) { replacementStarted.await() }
        assertTrue(firstErased.isCompleted)
    }
}
