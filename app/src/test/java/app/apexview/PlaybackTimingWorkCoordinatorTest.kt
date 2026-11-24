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
