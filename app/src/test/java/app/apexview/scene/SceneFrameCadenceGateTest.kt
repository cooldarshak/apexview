package app.apexview.scene

import org.junit.Assert.*
import org.junit.Test

class SceneFrameCadenceGateTest {
    @Test fun preventsEightMillisecondBurstsAt120HzWithoutLosingPendingVideo() {
        val gate = SceneFrameCadenceGate()
        val demand = SceneFrameDemand()
        val first = 1_000_000_000L
        assertTrue(demand.videoArrived())
        assertFalse(gate.shouldDefer(first, 50.0, 120f))
        assertTrue(demand.beginFrame())
        gate.onVideoDraw(first)

        assertTrue(demand.videoArrived())
        assertTrue(gate.shouldDefer(first + 8_333_333L, 50.0, 120f))
        assertTrue(demand.hasPendingVideo())
        assertFalse(demand.videoArrived()) // latest image replaces previous image
        assertFalse(gate.shouldDefer(first + 16_666_666L, 50.0, 120f))
        assertTrue(demand.beginFrame())
        gate.onVideoDraw(first + 16_666_666L)
        assertFalse(demand.hasPendingVideo())
    }

    @Test fun unchangedOn60HzTvAndOtherMediaFrameRates() {
        val gate = SceneFrameCadenceGate()
        gate.onVideoDraw(1_000_000_000L)
        assertFalse(gate.shouldDefer(1_008_333_333L, 50.0, 60f))
        assertFalse(gate.shouldDefer(1_008_333_333L, 25.0, 120f))
        assertFalse(gate.shouldDefer(1_008_333_333L, Double.NaN, 120f))
        assertFalse(gate.shouldDefer(1_008_333_333L, 50.0, Float.NaN))
    }

    @Test fun repeatedShortCallbacksMustEventuallyRender() {
        val gate = SceneFrameCadenceGate()
        gate.onVideoDraw(1_000_000_000L)
        assertTrue(gate.shouldDefer(1_001_000_000L, 50.0, 120f))
        assertTrue(gate.shouldDefer(1_002_000_000L, 50.0, 120f))
        assertFalse(gate.shouldDefer(1_003_000_000L, 50.0, 120f))
    }

    @Test fun acceleratedPlaybackUsesActualThinnedOutputNotUnthinnedMediaClock() {
        val gate = SceneFrameCadenceGate()
        gate.onVideoDraw(1_000_000_000L)
        assertTrue(gate.shouldDefer(1_008_333_333L, 50.0, 120f, 1.5f))
        assertTrue(gate.shouldDefer(1_008_333_333L, 50.0, 120f, 2f))
        assertFalse(gate.shouldDefer(1_008_333_333L, 50.0, 60f, 1.5f))
        assertTrue(gate.shouldDefer(1_008_333_333L, 50.0, 120f, 1f))
    }

    @Test fun coolAdaptiveModeAt75fpsDoesNotImposeStale50fpsPacing() {
        val gate = SceneFrameCadenceGate()
        gate.onVideoDraw(1_000_000_000L)
        assertFalse(gate.shouldDefer(1_008_333_333L, 50.0, 120f, 1.5f, 0))
        assertFalse(gate.shouldDefer(1_008_333_333L, 50.0, 96f, 1.5f, 1))
        assertTrue(gate.shouldDefer(1_008_333_333L, 50.0, 120f, 1.5f, 3))
        assertFalse(gate.shouldDefer(1_008_333_333L, 50.0, 60f, 1.5f, 0))
    }

    @Test fun fractional90HzCadenceDoesNotForce45fpsCeiling() {
        val gate = SceneFrameCadenceGate()
        gate.onVideoDraw(1_000_000_000L)
        assertFalse(gate.shouldDefer(1_011_111_111L, 50.0, 90f))
        assertTrue(gate.shouldDefer(1_010_000_000L, 50.0, 100f))
        assertFalse(gate.shouldDefer(1_020_000_000L, 50.0, 100f))
    }

    @Test fun full75fpsAt150HzUsesTwoSlotsRatherThanThe50fpsGuard() {
        val gate = SceneFrameCadenceGate()
        gate.onVideoDraw(1_000_000_000L)
        assertTrue(gate.shouldDefer(1_006_666_666L, 50.0, 150f, 1.5f, 0))
        assertFalse(gate.shouldDefer(1_013_333_333L, 50.0, 150f, 1.5f, 0))
    }

    @Test fun seekAndSurfaceResetDiscardPreviousTimestamp() {
        val gate = SceneFrameCadenceGate()
        gate.onVideoDraw(2_000_000_000L)
        assertTrue(gate.shouldDefer(2_008_000_000L, 50.0, 120f))
        gate.reset()
        assertFalse(gate.shouldDefer(2_008_000_000L, 50.0, 120f))
        gate.onVideoDraw(3_000_000_000L)
        assertFalse(gate.shouldDefer(2_000_000_000L, 50.0, 120f))
    }
}
