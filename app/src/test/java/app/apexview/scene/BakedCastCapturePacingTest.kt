package app.apexview.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class BakedCastCapturePacingTest {
    @Test fun nextCaptureDoesNotOverrunTwentyFiveFpsOrBlockVideoDecoder() {
        val start = 1_000_000_000L
        assertEquals(40L, BakedCastCapturePacing.delayMs(start, start))
        assertEquals(25L, BakedCastCapturePacing.delayMs(start, start + 15_000_000L))
        assertEquals(0L, BakedCastCapturePacing.delayMs(start, start + 60_000_000L))
        assertEquals(0L, BakedCastCapturePacing.delayMs(start, start + 250_000_000L))
    }
}
