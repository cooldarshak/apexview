package app.apexview.tiled

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import app.apexview.ManifestProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class F1DashFeedCaptureTest {
    private fun capture(body: String) = F1DashFeedCapture(
        "ApexView-test", "test-auth", "test-entitlement",
        dataSourceFactory = DataSource.Factory { ByteArrayDataSource(body.toByteArray()) }
    )
    private fun feeds(suffix: String = "") = listOf("channel-1004", "channel-1033").map {
        F1DashFeedCapture.Feed(it, "https://example.test/$it$suffix?token=NEVER_LOG")
    }

    @Test fun extensionlessHlsResponseFailsPermanentlyWithoutDecoderHandoff() = runBlocking {
        val capture = capture("#EXTM3U\n#EXT-X-VERSION:7\n")
        val snapshot = capture.captureAtPosition("test-hls", feeds(), 0)
        assertEquals(false, snapshot.failure?.retryable)
        assertTrue(snapshot.detail.orEmpty().contains("actual manifest is HLS"))
        assertFalse(snapshot.detail.orEmpty().contains("NEVER_LOG"))
        assertTrue(snapshot.actualManifests.values.contains(ManifestProtocol.HLS))
    }

    @Test fun mpdUrlReturningHlsIsNotPassedToTheDashParser() = runBlocking {
        val snapshot = capture("#EXTM3U\n#EXT-X-VERSION:7\n").captureAtPosition("test-mpd-hls", feeds(".mpd"), 0)
        assertEquals(false, snapshot.failure?.retryable)
        assertTrue(snapshot.detail.orEmpty().contains("actual manifest is HLS"))
    }

    @Test fun dashWithoutHevcFailsBeforeAnyInitializationOrDecoderRelease() = runBlocking {
        val body = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT60S" minBufferTime="PT1S">
            <Period start="PT0S"><AdaptationSet mimeType="video/mp4" codecs="avc1.640028">
            <Representation id="avc" width="960" height="540" bandwidth="1000000">
            <SegmentTemplate initialization="init.mp4" media="seg-${'$'}Number${'$'}.m4s" duration="2"/>
            </Representation></AdaptationSet></Period></MPD>"""
        val snapshot = capture(body).captureAtPosition("test-no-hevc", feeds(".mpd"), 0)
        assertEquals(false, snapshot.failure?.retryable)
        assertTrue(snapshot.detail.orEmpty(), snapshot.detail.orEmpty().contains("no supported HEVC"))
    }

    @Test fun htmlResponseIsAnActionablePermanentFailure() = runBlocking {
        val snapshot = capture("<html>Access denied</html>").captureAtPosition("test-html", feeds(), 0)
        assertEquals(false, snapshot.failure?.retryable)
        assertTrue(snapshot.detail.orEmpty().contains("not a DASH manifest"))
    }

    @Test fun networkReadCanRetryWithoutLeakingItsSignedUrl() = runBlocking {
        val capture = F1DashFeedCapture("ApexView-test", "test-auth", "test-entitlement",
            dataSourceFactory = DataSource.Factory { throw IOException("https://example.test/?token=NEVER_LOG") })
        val snapshot = capture.captureAtPosition("test-network", feeds(), 0)
        assertEquals(true, snapshot.failure?.retryable)
        assertTrue(snapshot.detail.orEmpty().contains("network read failed"))
        assertFalse(snapshot.detail.orEmpty().contains("NEVER_LOG"))
    }
}
