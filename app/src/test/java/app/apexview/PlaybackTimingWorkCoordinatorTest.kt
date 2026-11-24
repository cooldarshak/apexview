package app.apexview

import kotlinx.coroutines.Job
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
        work.failSpeed(request)
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
        work.failSpeed(first)
        val second = requireNotNull(work.beginSpeed("race"))
        assertFalse(work.completeSpeed(first, emptyMap()))
        assertTrue(work.completeSpeed(second, emptyMap()))
    }
    @Test fun cancelSeekClearsBothJobsAndTargetWithoutTouchingCapture() {
        val work = PlaybackWorkCoordinator()
        val seek = Job()
        val restart = Job()
        val capture = Job()
        val retry = Job()
        work.seekJob = seek
        work.castRestartJob = restart
        work.captureJob = capture
        work.retryJob = retry
        assertTrue(work.pendingSeek.offer(5000))
        work.cancelControlSeek()
        assertTrue(seek.isCancelled)
        assertTrue(restart.isCancelled)
        assertNull(work.pendingSeek.targetMs)
        assertNull(work.seekJob)
        assertNull(work.castRestartJob)
        assertTrue(capture.isActive)
        assertTrue(retry.isActive)
        work.cancelCapture()
        work.cancelRetry()
        assertTrue(capture.isCancelled)
        assertTrue(retry.isCancelled)
    }
}
