package app.apexview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySampleScheduleTest {
    @Test fun avoidsRedrawingTextAtEveryDisplayRefresh() {
        assertTrue(isOverlaySampleDue(1_000, Long.MIN_VALUE))
        assertFalse(isOverlaySampleDue(1_008, 1_000))
        assertFalse(isOverlaySampleDue(1_016, 1_000))
        assertFalse(isOverlaySampleDue(1_032, 1_000))
        assertTrue(isOverlaySampleDue(1_033, 1_000))
        assertTrue(isOverlaySampleDue(1_050, 1_017))
    }

    @Test fun restartsCleanlyAfterClockReset() {
        assertTrue(isOverlaySampleDue(100, 1_000))
        assertFalse(isOverlaySampleDue(100, 100))
    }

    @Test fun waitForNextOverlayTickPreservesAboutThirtyHzAtSixtyAndOneTwentyHz() {
        // Simulate the coroutine sleeping after one Choreographer callback, then
        // awaiting the next vsync; this must not turn the 30 Hz overlay into 15 Hz.
        for (refreshHz in listOf(60, 120)) {
            var published = 0
            var last = Long.MIN_VALUE
            var nowNs = 0L
            val vsyncNs = 1_000_000_000L / refreshHz
            while (nowNs < 1_000_000_000L) {
                val frameMs = nowNs / 1_000_000L
                if (isOverlaySampleDue(frameMs, last)) {
                    published++
                    last = frameMs
                }
                val wakeNs = nowNs + MAP_FRAME_WAIT_MS * 1_000_000L
                nowNs = ((wakeNs + vsyncNs - 1) / vsyncNs) * vsyncNs
            }
            assertTrue("$refreshHz Hz published only $published", published in 27..31)
        }
        assertEquals(30L, MAP_FRAME_WAIT_MS)
    }
}
