package app.apexview.tiled

import org.junit.Assert.*
import org.junit.Test

class HevcFeedCompatibilityTest {
    private val matching = HevcFeedCompatibility.Profile("hvc1", 640, 360, 90000, 3600, "cbcs")

    @Test fun acceptsMatchingCompleteProfiles() {
        val result = HevcFeedCompatibility.compare(matching, matching.copy())
        assertTrue(result.compatible)
        assertTrue(result.reasons.isEmpty())
    }

    @Test fun rejectsMissingMetadata() {
        val result = HevcFeedCompatibility.compare(matching, matching.copy(frameDurationTicks = null))
        assertFalse(result.compatible)
        assertTrue(result.reasons.any { it.contains("Frame duration") })
    }

    @Test fun rejectsDimensionTimingCodecAndEncryptionMismatch() {
        val other = matching.copy(codec = "hev1", width = 1280, timescale = 1000,
            frameDurationTicks = 40, encryptionScheme = "cenc")
        val result = HevcFeedCompatibility.compare(matching, other)
        assertFalse(result.compatible)
        assertTrue(result.reasons.size >= 5)
    }

    @Test fun rejectsUnknownCodec() {
        val result = HevcFeedCompatibility.compare(matching.copy(codec = null), matching)
        assertFalse(result.compatible)
    }
}
