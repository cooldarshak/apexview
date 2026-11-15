package app.apexview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class F1FeedProfileProbeEligibilityTest {
    @Test
    fun standardContentPlayCanExposeReadOnlyCodecMetadata() {
        val stream = PlayInfo(
            url = "https://example.invalid/sanitised-test.m3u8",
            licenseUrl = null,
            streamType = "HLS",
            allowHevcProfileProbe = true
        )
        assertTrue(stream.allowHevcProfileProbe)
    }

    @Test
    fun multiFeedSessionDoesNotExposeCodecMetadataToThePocObserver() {
        val session = MultiPlay(
            feeds = mapOf(1 to "https://example.invalid/test.m3u8"),
            licenseUrl = null,
            streamType = "HLS"
        )
        assertFalse(session.stream("https://example.invalid/test.m3u8").allowHevcProfileProbe)
    }
}
