package app.apexview

import org.junit.Assert.*
import org.junit.Test

class OverlayFlagPulseTest {
    @Test fun flagPulsePreservesLegacyRangeAndReverseEasing() {
        assertEquals(0.3f, overlayFlagPulseAlpha(0), 0.0001f)
        assertEquals(1f, overlayFlagPulseAlpha(OVERLAY_FLAG_PULSE_HALF_NS), 0.0001f)
        assertEquals(0.3f, overlayFlagPulseAlpha(OVERLAY_FLAG_PULSE_HALF_NS * 2), 0.0001f)
        for (millis in 0..4400 step 10) {
            val alpha = overlayFlagPulseAlpha(millis * 1_000_000L)
            assertTrue("alpha=$alpha at $millis ms", alpha in 0.2999f..1.0001f)
        }
        for (millis in 0..1100 step 17) {
            assertEquals(
                overlayFlagPulseAlpha(millis * 1_000_000L),
                overlayFlagPulseAlpha((2200L - millis) * 1_000_000L),
                0.0002f
            )
        }
    }

    @Test fun animationDoesNotInvalidateTheRetainedEventDisplayList() {
        val events = OverlayRecordingValidity()
        val pictureKey = Any() // flags and caption unchanged throughout the animation
        events.markRecorded(pictureKey, 640, 360)
        repeat(250) { frame ->
            val alpha = overlayFlagPulseAlpha(frame * 16_666_667L)
            assertTrue(alpha in 0.2999f..1.0001f)
            assertFalse(events.needsRecord(pictureKey, 640, 360))
            events.markReplayed()
        }
        assertEquals(1L, events.recordings)
        assertEquals(250L, events.replays)
        assertTrue(events.needsRecord(Any(), 640, 360)) // changed flag/pit event
    }
}
