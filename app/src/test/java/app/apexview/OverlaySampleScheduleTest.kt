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
        // The low-rate metadata sampler is independent of a 60/120Hz video
        // display clock. Irregular coroutine wakeups must not produce a burst
        // of map recompositions, and missed intervals must recover cleanly.
        val wakeupsMs = listOf(0L, 100L, 207L, 314L, 418L, 524L, 632L, 738L, 844L, 951L)
        var published = 0
        var lastPublishedMs = Long.MIN_VALUE
        for (timeMs in wakeupsMs) {
            if (isOverlaySampleDue(timeMs, lastPublishedMs)) {
                published++
                lastPublishedMs = timeMs
            }
        }
        assertEquals(10, published)
        assertFalse(isOverlaySampleDue(952L, lastPublishedMs))
    }
}
