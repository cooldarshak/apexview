package app.apexview.scene

import org.junit.Assert.*
import org.junit.Test

class SceneFrameDemandTest {
    @Test fun severalCodecCallbacksNeedOnlyOneDisplayFrame() {
        val frames = SceneFrameDemand()
        assertTrue(frames.videoArrived())
        assertTrue(frames.hasPendingVideo())
        repeat(12) { assertFalse(frames.videoArrived()) }
        assertTrue(frames.beginFrame())
        assertFalse(frames.hasPendingVideo())
        assertTrue(frames.videoArrived())
        assertTrue(frames.beginFrame())
    }

    @Test fun aPausedVideoCanRedrawWithoutConsumingAnotherCodecImage() {
        val frames = SceneFrameDemand()
        assertTrue(frames.requestDraw())
        assertFalse(frames.requestDraw())
        assertFalse(frames.beginFrame())
        assertTrue(frames.videoArrived())
        assertFalse(frames.requestDraw())
        assertTrue(frames.beginFrame())
    }

    @Test fun unavailableOutputPreservesLastVideoWithoutBusyLoop() {
        val frames = SceneFrameDemand()
        assertTrue(frames.videoArrived())
        assertTrue(frames.beginFrame())
        frames.onDrawUnavailable(hadNewVideo = true)
        assertFalse(frames.hasPendingVideo()) // No callback remains scheduled.
        assertTrue(frames.requestDraw()) // A later valid output explicitly resumes.
        assertTrue(frames.hasPendingVideo())
        assertTrue(frames.beginFrame())
        assertFalse(frames.hasPendingVideo())
    }

    @Test fun unavailableLayoutRedrawDoesNotInventDecoderPictures() {
        val frames = SceneFrameDemand()
        assertTrue(frames.requestDraw())
        assertFalse(frames.beginFrame())
        frames.onDrawUnavailable(hadNewVideo = false)
        assertTrue(frames.requestDraw())
        assertFalse(frames.beginFrame())
        frames.onDrawUnavailable(hadNewVideo = false)
        frames.reset()
        assertTrue(frames.videoArrived())
        assertTrue(frames.beginFrame())
    }

    @Test fun resetDiscardsStaleCallbacksAcrossSurfaceRecreation() {
        val frames = SceneFrameDemand()
        assertTrue(frames.videoArrived())
        frames.reset()
        assertTrue(frames.requestDraw())
        assertFalse(frames.beginFrame())
    }

    @Test fun coalescedLatchesAreCountedSeparatelyFromSubmissions() {
        val demand = SceneFrameDemand()
        org.junit.Assert.assertTrue(demand.videoArrived())
        org.junit.Assert.assertFalse(demand.videoArrived())
        org.junit.Assert.assertFalse(demand.videoArrived())
        org.junit.Assert.assertEquals(2L, demand.takeCoalescedVideoArrivals())
        org.junit.Assert.assertTrue(demand.beginFrame())
        org.junit.Assert.assertEquals(0L, demand.takeCoalescedVideoArrivals())
        org.junit.Assert.assertTrue(demand.videoArrived())
        demand.reset()
        org.junit.Assert.assertEquals(0L, demand.takeCoalescedVideoArrivals())
    }
    @Test fun atlasChangeDiscardsOldPictureWithoutLosingScheduledCallback() {
        val demand = SceneFrameDemand()
        assertTrue(demand.videoArrived())
        demand.discardPendingVideo()
        assertFalse(demand.hasPendingVideo())
        assertFalse(demand.beginFrame())
        assertTrue(demand.videoArrived())
        assertTrue(demand.beginFrame())
    }
}
