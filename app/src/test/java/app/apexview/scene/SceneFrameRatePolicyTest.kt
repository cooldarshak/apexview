package app.apexview.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SceneFrameRatePolicyTest {
    @Test fun suppliesActualVideoCadenceWithoutRoundingToDisplayRefresh() {
        assertEquals(50f, SceneFrameRatePolicy.hint(50.0), 0.0001f)
        assertEquals(29.97f, SceneFrameRatePolicy.hint(29.97), 0.0001f)
        assertEquals(59.94f, SceneFrameRatePolicy.hint(59.94), 0.0001f)
    }

    @Test fun bothVideoRendererModesUseSourceCadenceTimesPlaybackSpeed() {
        for (source in listOf(25.0, 50.0, 60.0)) {
            for (speed in listOf(0.5f, 1f, 1.5f, 2f)) {
                assertEquals((source * speed).toFloat(),
                    SceneFrameRatePolicy.hint(source, speed), 0.0001f)
            }
        }
    }

    @Test fun acceleratedPlaybackHintsRealPresentationRateAndRejectsInvalidSpeed() {
        assertEquals(75f, SceneFrameRatePolicy.hint(50.0, 1.5f), 0.0001f)
        assertEquals(100f, SceneFrameRatePolicy.hint(50.0, 2f), 0.0001f)
        assertEquals(25f, SceneFrameRatePolicy.hint(50.0, 0.5f), 0.0001f)
        assertEquals(0f, SceneFrameRatePolicy.hint(50.0, Float.NaN), 0.0001f)
    }

    @Test fun composedHintMatchesThinnedOutputWhileMediaClockStillRunsAtSeventyFive() {
        assertEquals(50f, SceneFrameRatePolicy.composedOutputHint(50.0, 1f), 0f)
        assertEquals(50f, SceneFrameRatePolicy.composedOutputHint(50.0, 1.5f), 0f)
        assertEquals(50f, SceneFrameRatePolicy.composedOutputHint(50.0, 2f), 0f)
        assertEquals(25f, SceneFrameRatePolicy.composedOutputHint(50.0, 0.5f), 0f)
        assertEquals(24f, SceneFrameRatePolicy.composedOutputHint(24.0, 2f), 0f)
        assertEquals(0f, SceneFrameRatePolicy.composedOutputHint(Double.NaN, 1.5f), 0f)
        assertEquals(75f, SceneFrameRatePolicy.hint(50.0, 1.5f), 0f)
    }

    @Test fun refreshMultipleUsesRealPlaybackSpeedInsteadOfOnlySourceFps() {
        assertTrue(SceneFrameRatePolicy.isIntegerRefreshMultiple(100f, 50.0, 1f))
        assertTrue(SceneFrameRatePolicy.isIntegerRefreshMultiple(50f, 50.0, 1f))
        assertFalse(SceneFrameRatePolicy.isIntegerRefreshMultiple(120f, 50.0, 1f))
        assertFalse(SceneFrameRatePolicy.isIntegerRefreshMultiple(100f, 50.0, 1.5f))
        assertTrue(SceneFrameRatePolicy.isIntegerRefreshMultiple(75f, 50.0, 1.5f))
        assertTrue(SceneFrameRatePolicy.isIntegerRefreshMultiple(150f, 50.0, 1.5f))
        assertFalse(SceneFrameRatePolicy.isIntegerRefreshMultiple(0f, 50.0))
        assertFalse(SceneFrameRatePolicy.isIntegerRefreshMultiple(Float.NaN, 50.0))
        assertFalse(SceneFrameRatePolicy.isIntegerRefreshMultiple(120f, 0.0))
    }

    @Test fun seamlessCandidatesNeverConsiderDifferentResolutionOrInventSupport() {
        val active = SceneFrameRatePolicy.Mode(1, 1920, 1080, 120f)
        val available = listOf(active,
            SceneFrameRatePolicy.Mode(2, 1920, 1080, 100f),
            SceneFrameRatePolicy.Mode(3, 1920, 1080, 50f),
            SceneFrameRatePolicy.Mode(4, 1280, 720, 100f),
            SceneFrameRatePolicy.Mode(5, 1920, 1080, 75f),
            SceneFrameRatePolicy.Mode(6, 1920, 1080, 150f))
        // 150Hz is an exact 3:1 multiple of a 50fps source. Reject only
        // unsupported modes, mismatched resolution and nonintegral cadence.
        assertEquals(listOf(3, 2, 6),
            SceneFrameRatePolicy.matchingSeamlessModes(active, available, 50.0, 1f).map { it.id })
        assertEquals(listOf(5, 6),
            SceneFrameRatePolicy.matchingSeamlessModes(active, available, 50.0, 1.5f).map { it.id })
        assertTrue(SceneFrameRatePolicy.matchingSeamlessModes(active, emptyList(), 50.0, 1f).isEmpty())
        assertTrue(SceneFrameRatePolicy.matchingSeamlessModes(active, available, Double.NaN, 1f).isEmpty())
    }

    @Test fun oneHundredFiftyHertzIsThreeSlotsAtOneXAndTwoAtOnePointFiveX() {
        assertTrue(SceneFrameRatePolicy.isIntegerRefreshMultiple(150f, 50.0, 1f))
        assertTrue(SceneFrameRatePolicy.isIntegerRefreshMultiple(150f, 50.0, 1.5f))
        assertFalse(SceneFrameRatePolicy.isIntegerRefreshMultiple(120f, 50.0, 1f))
        assertFalse(SceneFrameRatePolicy.isIntegerRefreshMultiple(120f, 50.0, 1.5f))
    }

    @Test fun alreadyMatchingModeIsKeptFirstWithoutForcingAChange() {
        val active = SceneFrameRatePolicy.Mode(4, 1920, 1080, 100f)
        val available = listOf(
            SceneFrameRatePolicy.Mode(5, 1920, 1080, 50f), active,
            SceneFrameRatePolicy.Mode(6, 1920, 1080, 120f))
        assertEquals(4, SceneFrameRatePolicy.matchingSeamlessModes(active, available, 50.0, 1f).first().id)
    }

    @Test fun adaptiveModeHintMatchesDecoderOutputAndThermalFallback() {
        assertEquals(75f, SceneFrameRatePolicy.composedOutputHint(50.0, 1.5f, 120f, 0), 0f)
        assertEquals(75f, SceneFrameRatePolicy.composedOutputHint(50.0, 1.5f, 96f, 1), 0f)
        assertEquals(50f, SceneFrameRatePolicy.composedOutputHint(50.0, 1.5f, 60f, 0), 0f)
        assertEquals(50f, SceneFrameRatePolicy.composedOutputHint(50.0, 1.5f, 120f, 3), 0f)
        assertEquals(50f, SceneFrameRatePolicy.composedOutputHint(50.0, 1.5f), 0f)
        assertEquals(75f, SceneFrameRatePolicy.hint(50.0, 1.5f), 0f)
    }

    @Test fun rejectsUnknownOrInvalidCadenceInsteadOfForcingARefreshSwitch() {
        listOf(0.0, -50.0, Double.NaN, Double.POSITIVE_INFINITY, 300.0).forEach {
            assertEquals(0f, SceneFrameRatePolicy.hint(it), 0f)
        }
    }
}
