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
        assertFalse(isOverlaySampleDue(1_099, 1_000))
        assertTrue(isOverlaySampleDue(1_100, 1_000))
        assertTrue(isOverlaySampleDue(1_200, 1_100))
    }

    @Test fun restartsCleanlyAfterClockReset() {
        assertTrue(isOverlaySampleDue(100, 1_000))
        assertFalse(isOverlaySampleDue(100, 100))
    }

    @Test fun staticOverlaySamplingStaysNearTenHzWhileDrawClockRemainsSixtyHz() {
        // The slow Compose clock is exclusively for static event/layout state.
        // Canvas driver-marker motion runs on a separate 60 Hz draw clock.
        assertEquals(100L, MAP_RENDER_INTERVAL_MS)
        assertEquals(100_000_000L, TV_MAP_FRAME_INTERVAL_NS)
        assertEquals(MAP_RENDER_INTERVAL_MS, TV_MAP_FRAME_INTERVAL_NS / 1_000_000L)
        assertTrue(isOverlayDrawFrameDue(15_000_000L, 0L))
        // Simulate the coroutine sleeping after a Choreographer callback, then
        // awaiting the next 60/120 Hz vsync.
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
            assertTrue("$refreshHz Hz published only $published", published in 9..11)
        }
        assertEquals(98L, MAP_FRAME_WAIT_MS)
    }
}
