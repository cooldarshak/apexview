package app.apexview

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import app.apexview.tiled.HevcBitstream

class HevcFeedProfileProbeTest {
    @Test
    fun reportsOnlySanitisedParameterSetMetadataFromSyntheticCsd() {
        val fixturePath = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Synthetic HEVC tile fixture directory is not configured", !fixturePath.isNullOrBlank())
        val feed = File(fixturePath!!, "feed_left.hevc")
        assumeTrue("Synthetic HEVC CSD fixture is unavailable", feed.isFile)

        val parameterSets = HevcBitstream.splitAnnexB(feed.readBytes())
            .filter { it.type in 32..34 }
        val csd = ByteArray(parameterSets.sumOf { it.bytes.size + 4 })
        var offset = 0
        parameterSets.forEach { nal ->
            csd[offset++] = 0
            csd[offset++] = 0
            csd[offset++] = 0
            csd[offset++] = 1
            nal.bytes.copyInto(csd, offset)
            offset += nal.bytes.size
        }
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1")
            .setWidth(256)
            .setHeight(128)
            .setFrameRate(25f)
            .setInitializationData(listOf(csd))
            .build()

        val summary = HevcFeedProfileProbe.summarize(C.TRACK_TYPE_VIDEO, format)
        assertTrue(summary.contains("mime=video/hevc"))
        assertTrue(summary.contains("size=256x128"))
        assertTrue(summary.contains("tileGrid=2x1"))
        assertTrue(summary.contains("parameterNalCounts=1/1/1"))
        assertFalse(summary.contains("license", ignoreCase = true))
        assertFalse(summary.contains("pssh", ignoreCase = true))
        assertFalse(summary.contains("kid", ignoreCase = true))
        assertFalse(summary.contains(" iv=", ignoreCase = true))
        assertFalse(summary.contains("url=", ignoreCase = true))
    }

    @Test
    fun reportsMissingCsdWithoutFailingPlaybackDiagnostics() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1")
            .setWidth(1920)
            .setHeight(1080)
            .build()
        val summary = HevcFeedProfileProbe.summarize(C.TRACK_TYPE_VIDEO, format)

        assertTrue(summary.contains("initializationDataCount=0"))
        assertTrue(summary.contains("vps={unavailable}"))
        assertTrue(summary.contains("sps={unavailable}"))
        assertTrue(summary.contains("pps={unavailable}"))
    }
}
