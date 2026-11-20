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

    @Test fun resetDiscardsStaleCallbacksAcrossSurfaceRecreation() {
        val frames = SceneFrameDemand()
        assertTrue(frames.videoArrived())
        frames.reset()
        assertTrue(frames.requestDraw())
        assertFalse(frames.beginFrame())
    }
}
