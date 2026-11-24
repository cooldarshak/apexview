package app.apexview.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class BakedCastCapturePacingTest {
    @Test fun encoderAndCommentaryShareSourceTimeWhenPlaybackAcceleratesOrPauses() {
        val clock = BakedCastMediaClock()
        assertEquals(0L, clock.timestampNs(100_000L))
        assertEquals(40_000_000L, clock.timestampNs(100_040L))
        assertEquals(100_000_000L, clock.timestampNs(100_100L))
        org.junit.Assert.assertFalse(clock.canCapture(100_100L))
        org.junit.Assert.assertFalse(clock.canCapture(100_099L))
        assertEquals(160_000_000L, clock.timestampNs(100_160L))
        assertEquals(100_000L, clock.originMs)
    }

    @Test fun castPlacementMatchesNestedViewerPivotsAtBothEdges() {
        val fit = BakedCastVideoPlacement.viewer(sideScale = 0.8f, feedScale = 0.75f, upwardShiftFraction = 0.05f)
        assertEquals(0.1f, fit.left, 0.00001f)
        assertEquals(0.26f, fit.top, 0.00001f)
        assertEquals(0.6f, fit.scale, 0.00001f)
    }
    @Test fun sustainedSlowCaptureBacksOffAndFastCaptureRecovers() {
        val pacing = BakedCastCapturePacing.Controller()
        val start = 1_000_000_000L
        repeat(20) { org.junit.Assert.assertTrue(pacing.nextDelayMs(start, start + 80_000_000L) >= 4L) }
        repeat(40) { pacing.nextDelayMs(start, start + 10_000_000L) }
        assertEquals(30L, pacing.nextDelayMs(start, start + 10_000_000L))
    }

    @Test fun nextCaptureDoesNotOverrunTwentyFiveFpsOrBlockVideoDecoder() {
        val start = 1_000_000_000L
        assertEquals(40L, BakedCastCapturePacing.delayMs(start, start))
        assertEquals(25L, BakedCastCapturePacing.delayMs(start, start + 15_000_000L))
        assertEquals(0L, BakedCastCapturePacing.delayMs(start, start + 60_000_000L))
        assertEquals(0L, BakedCastCapturePacing.delayMs(start, start + 250_000_000L))
    }
}
