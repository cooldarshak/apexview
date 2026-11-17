package app.apexview.tiled

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveDashSegmentWindowTest {
    @Test fun liveRewindUsesTheAvailableTimelineWithoutNeedingFiniteVodDuration() {
        val xml = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="1970-01-01T00:00:00Z" minBufferTime="PT1S">
          <Period start="PT0S"><AdaptationSet mimeType="video/mp4" codecs="hvc1.1.6.L123.B0">
          <Representation id="hd" width="1280" height="720" bandwidth="3000000">
          <SegmentTemplate initialization="init.mp4" media="seg-${'$'}Number${'$'}.m4s" timescale="1"><SegmentTimeline><S t="42" d="6" r="2"/></SegmentTimeline></SegmentTemplate>
          </Representation></AdaptationSet></Period></MPD>"""
        val mpd = DashManifestParser().parse(Uri.parse("https://example.test/live.mpd"), xml.byteInputStream())
        val index = requireNotNull(mpd.getPeriod(0).adaptationSets.single().representations.single().index)
        assertTrue(mpd.dynamic)
        val chosen = LiveDashSegmentWindow.near(index, C.TIME_UNSET, 0, 60_000_000, 48_000_000, 0, 0)
        assertEquals(listOf(48_000_000L), chosen.map { it.absoluteStartUs })
        val futurePrefetch = LiveDashSegmentWindow.near(index, C.TIME_UNSET, 0, 60_000_000, 90_000_000, 0, 0)
        assertEquals(54_000_000L, futurePrefetch.single().absoluteStartUs)
        assertThrows(PermanentCaptureException::class.java) {
            LiveDashSegmentWindow.near(index, C.TIME_UNSET, 0, 60_000_000, 1_000_000, 0, 0)
        }
    }
}
