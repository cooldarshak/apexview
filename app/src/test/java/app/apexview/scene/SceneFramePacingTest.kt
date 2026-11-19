package app.apexview.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class SceneFramePacingTest {
    @Test fun separatesDecoderDeliveryGapsFromDisplayAndSwapStalls() {
        val stats = SceneFramePacing()
        val start = 1_000_000_000L
        stats.onTextureLatched(start)
        stats.onTextureLatched(start + 20_000_000L)
        stats.onTextureLatched(start + 80_000_000L)
        stats.onDisplayFrame(start, 5_000_000L, newVideo = true)
        stats.onDisplayFrame(start + 16_000_000L, 25_000_000L, newVideo = false)
        stats.onDisplayFrame(start + 80_000_000L, 5_000_000L, newVideo = true)

        val window = stats.snapshotAndReset()
        assertEquals(3, window.latched)
        assertEquals(2, window.presented)
        assertEquals(1, window.callbackGaps)
        assertEquals(1, window.displayGaps)
        assertEquals(1, window.slowSwaps)
        assertEquals(60, window.maxCallbackGapMs)
        assertEquals(80, window.maxDisplayGapMs)
        assertEquals(25, window.maxSwapMs)

        assertEquals(0, stats.snapshotAndReset().latched)
        stats.reset()
        stats.onTextureLatched(start + 200_000_000L)
        assertEquals(0, stats.snapshotAndReset().callbackGaps)
    }

    @Test fun normalFiftyFpsDeliveryDoesNotFlagFalseStalls() {
        val stats = SceneFramePacing()
        repeat(50) { frame ->
            val time = 1_000_000_000L + frame * 20_000_000L
            stats.onTextureLatched(time)
            stats.onDisplayFrame(time, 2_000_000L, newVideo = true)
        }
        val result = stats.snapshotAndReset()
        assertEquals(50, result.latched)
        assertEquals(50, result.presented)
        assertEquals(0, result.callbackGaps)
        assertEquals(0, result.displayGaps)
        assertEquals(0, result.slowSwaps)
    }

    @Test fun slowGlWorkMustNotBeMisreportedAsSlowEglSwap() {
        val stats = SceneFramePacing()
        val time = 1_000_000_000L
        stats.onTextureLatched(time, 22_000_000L, 90_000_000L)
        stats.onTextureLatched(time + 20_000_000L, 1_000_000L, 80_000_000L)
        stats.onDisplayFrame(time, 2_000_000L, true, drawNs = 30_000_000L,
            callbackStartNs = time + 6_000_000L)
        val window = stats.snapshotAndReset()
        assertEquals(0, window.slowSwaps)
        assertEquals(2, window.maxSwapMs)
        assertEquals(1, window.slowDraws)
        assertEquals(30, window.maxDrawMs)
        assertEquals(1, window.slowLatches)
        assertEquals(22, window.maxLatchMs)
        assertEquals(1, window.surfaceTimestampRegressions)
        assertEquals(6, window.maxVsyncCallbackDelayMs)
    }

    @Test fun resetDiscardsOldSurfaceTimestampsAfterOutputRecreation() {
        val stats = SceneFramePacing()
        stats.onTextureLatched(1_000_000_000L, surfaceTimestampNs = 900_000_000L)
        stats.reset()
        stats.onTextureLatched(2_000_000_000L, surfaceTimestampNs = 100_000_000L)
        assertEquals(0, stats.snapshotAndReset().surfaceTimestampRegressions)
    }
}
