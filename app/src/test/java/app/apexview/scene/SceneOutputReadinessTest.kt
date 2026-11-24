package app.apexview.scene

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneOutputReadinessTest {
    @Test fun firstComposedFrameMustBeSubmittedBeforeViewerIsReady() {
        val gate = SceneOutputReadiness()
        assertFalse(gate.isReady())
        val surface = gate.invalidate()
        assertFalse(gate.isReady())
        assertTrue(gate.acknowledgeSubmittedFrame(surface))
        assertTrue(gate.isReady())
        assertFalse(gate.acknowledgeSubmittedFrame(surface))
    }

    @Test fun oldGlFrameCannotUnlockNewSurfaceAfterStopResume() {
        val gate = SceneOutputReadiness()
        val oldSurface = gate.invalidate()
        val resumedSurface = gate.invalidate()
        assertFalse(gate.acknowledgeSubmittedFrame(oldSurface))
        assertFalse(gate.isReady())
        assertTrue(gate.acknowledgeSubmittedFrame(resumedSurface))
        assertTrue(gate.isReady())
    }

    @Test fun renditionOrLayoutChangeInvalidatesPreviouslySubmittedPicture() {
        val gate = SceneOutputReadiness()
        val firstLayout = gate.invalidate()
        assertTrue(gate.acknowledgeSubmittedFrame(firstLayout))
        val newLayout = gate.invalidate()
        assertFalse(gate.isReady())
        assertFalse(gate.acknowledgeSubmittedFrame(firstLayout))
        assertTrue(gate.acknowledgeSubmittedFrame(newLayout))
    }

    @Test fun noArbitraryTimeoutCanMarkSceneReady() {
        val gate = SceneOutputReadiness()
        gate.invalidate()
        assertFalse(gate.isReady())
        gate.invalidate()
        assertFalse(gate.isReady())
    }

    @Test fun inFlightSwapRetainsItsOriginalEpochAcrossAtlasChange() {
        val gate = SceneOutputReadiness()
        val glEpoch = gate.invalidate()
        assertTrue(gate.matches(glEpoch))
        // Main thread invalidates while the original GL swap is blocked.
        val replacement = gate.invalidate()
        assertFalse(gate.matches(glEpoch))
        assertFalse(gate.acknowledgeSubmittedFrame(glEpoch))
        assertFalse(gate.isReady())
        assertTrue(gate.acknowledgeSubmittedFrame(replacement))
    }
}
