package app.apexview.tiled

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedChangeLogTest {
    @Test fun unchangedQualityDoesNotLogFourTimesPerSecond() {
        val diagnostics = BoundedChangeLog(5_000)
        assertTrue(diagnostics.shouldLog("feedA=1920x1080,feedB=960x540", 1_000))
        for (offset in 250L..4_750L step 250L) {
            assertFalse(diagnostics.shouldLog("feedA=1920x1080,feedB=960x540", 1_000 + offset))
        }
        assertTrue(diagnostics.shouldLog("feedA=1920x1080,feedB=960x540", 6_000))
    }

    @Test fun sourceRenditionChangeAndClockResetAlwaysLog() {
        val diagnostics = BoundedChangeLog()
        assertTrue(diagnostics.shouldLog("540p", 5_000))
        assertFalse(diagnostics.shouldLog("540p", 5_050))
        assertTrue(diagnostics.shouldLog("720p", 5_075))
        assertFalse(diagnostics.shouldLog("720p", 5_100))
        assertTrue(diagnostics.shouldLog("720p", 100))
    }
}
