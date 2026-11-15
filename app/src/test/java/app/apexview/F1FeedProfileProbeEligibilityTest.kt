package app.apexview

import org.junit.Assert.assertEquals
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
    fun authorisedMultiFeedSessionExposesSanitisedPerChannelCodecMetadata() {
        val session = MultiPlay(
            feeds = mapOf(
                1 to "https://example.invalid/main.m3u8",
                2 to "https://example.invalid/data.m3u8"
            ),
            licenseUrl = null,
            streamType = "HLS"
        )
        val main = session.stream("https://example.invalid/main.m3u8", 1, 12345L)
        val data = session.stream("https://example.invalid/data.m3u8", 2, 12345L)

        assertTrue(main.allowHevcProfileProbe)
        assertTrue(data.allowHevcProfileProbe)
        assertEquals("channel-1", main.profileLabel)
        assertEquals("channel-2", data.profileLabel)
        assertEquals("content-12345", main.profileGroup)
        assertEquals(main.profileGroup, data.profileGroup)
        assertEquals(session.licenseUrl, main.licenseUrl)
    }
}
