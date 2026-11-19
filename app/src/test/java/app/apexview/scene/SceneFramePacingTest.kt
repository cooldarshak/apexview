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
}
