package app.apexview

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptivePlaybackCadenceTest {
    private fun output(refresh: Float, thermal: Int, single: Boolean, speed: Float = 1.5f): Float =
        AdaptivePlaybackCadence.outputFps(50.0, speed, refresh, thermal, single)

    @Test fun onlyCoolSingleComposedOutputUsesFull75fpsWhenModeCanPresentIt() {
        assertEquals(75f, output(120f, 0, true), 0f)
        assertEquals(75f, output(96f, 1, true), 0f)
        assertEquals(75f, output(90f, 0, true), 0f)
        assertEquals(50f, output(60f, 0, true), 0f)
        assertEquals(50f, output(0f, 0, true), 0f)
        assertEquals(50f, output(120f, 3, true), 0f)
        assertEquals(50f, output(120f, 0, false), 0f)
        assertEquals(50f, output(144f, 2, true), 0f)
    }

    @Test fun alwaysPreservesNativeSourceCadenceAtNormalSpeed() {
        assertEquals(50f, output(30f, 3, true, 1f), 0f)
        assertEquals(50f, output(60f, 0, false, 1f), 0f)
        assertEquals(25f, AdaptivePlaybackCadence.outputFps(50.0, 0.5f, 60f, 0, true), 0f)
    }

    @Test fun sourceFrameRatesAndInvalidDevicesNeverForceRefreshOrInventSupport() {
        assertEquals(36f, AdaptivePlaybackCadence.outputFps(24.0, 1.5f, 120f, 0, true), 0f)
        assertEquals(24f, AdaptivePlaybackCadence.outputFps(24.0, 1.5f, 30f, 0, true), 0f)
        assertEquals(0f, AdaptivePlaybackCadence.outputFps(Double.NaN, 1.5f, 120f, 0, true), 0f)
        assertEquals(0f, AdaptivePlaybackCadence.outputFps(50.0, Float.NaN, 120f, 0, true), 0f)
        assertEquals(50f, output(Float.NaN, 0, true), 0f)
    }

    @Test fun thermalTransitionImmediatelyRevertsToBoundedOutput() {
        val states = listOf(0, 1, 2, 3, 2, 0)
        assertEquals(listOf(75f, 75f, 50f, 50f, 50f, 75f),
            states.map { output(120f, it, true) })
    }
}
