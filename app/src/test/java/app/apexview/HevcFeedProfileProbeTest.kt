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
        assertTrue(summary.contains("parameterSetFingerprint="))
        assertFalse(summary.contains("license", ignoreCase = true))
        assertFalse(summary.contains("pssh", ignoreCase = true))
        assertFalse(summary.contains("kid", ignoreCase = true))
        assertFalse(summary.contains(" iv=", ignoreCase = true))
        assertFalse(summary.contains("url=", ignoreCase = true))
    }

    @Test
    fun comparesTheTwoComplementaryTileFeedProfilesThroughTheStrictGate() {
        val fixturePath = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Synthetic HEVC tile fixture directory is not configured", !fixturePath.isNullOrBlank())

        fun formatFor(fileName: String): Format {
            val feed = File(fixturePath!!, fileName)
            assumeTrue("Synthetic HEVC feed $fileName is unavailable", feed.isFile)
            val parameterSets = HevcBitstream.splitAnnexB(feed.readBytes()).filter { it.type in 32..34 }
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
            return Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs("hvc1")
                .setWidth(256)
                .setHeight(128)
                .setFrameRate(25f)
                .setInitializationData(listOf(csd))
                .build()
        }

        val first = formatFor("feed_left.hevc")
        val second = formatFor("feed_right.hevc")
        HevcFeedProfileCapture.record(
            HevcFeedProfileProbe.inspect(C.TRACK_TYPE_VIDEO, first, "channel-1", "content-100")
        )
        HevcFeedProfileCapture.updateMediaChunkDuration("content-100", "channel-1", "HLS", 6_000L)
        HevcFeedProfileCapture.updateDrm("content-100", "channel-1", "L1", keysLoaded = true)
        HevcFeedProfileCapture.updateVideoDecoder("content-100", "channel-1", "c2.qti.hevc.decoder")
        HevcFeedProfileCapture.updateFirstFrameRendered("content-100", "channel-1")
        HevcFeedProfileCapture.record(
            HevcFeedProfileProbe.inspect(C.TRACK_TYPE_VIDEO, second, "channel-2", "content-100")
        )
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_FORMAT_MATCH"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("\nHEVC_TILE_ASSEMBLER_GATE=PASS"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("channel-1: DRM="))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("channel-2: DRM="))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("CMAF timing"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("channel-1"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("channel-2"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("chunksMs=[6000]"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("keysLoadedEventObserved=true"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("decoder=c2.qti.hevc.decoder"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("firstFrameRendered=true"))

        HevcFeedProfileCapture.record(
            HevcFeedProfileProbe.inspect(C.TRACK_TYPE_VIDEO, second, "channel-3", "content-101")
        )
        assertTrue(HevcFeedProfileCapture.diagnostic.startsWith("HEVC_PROFILE_CAPTURED\nchannel-3:"))
    }

    @Test
    fun reportsCodecProfileMismatchWithoutClaimingCmafCompatibility() {
        val first = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1")
            .setWidth(1920)
            .setHeight(1080)
            .build()
        val second = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1")
            .setWidth(1280)
            .setHeight(720)
            .build()

        HevcFeedProfileCapture.record(
            HevcFeedProfileProbe.inspect(C.TRACK_TYPE_VIDEO, first, "channel-a", "content-200")
        )
        HevcFeedProfileCapture.record(
            HevcFeedProfileProbe.inspect(C.TRACK_TYPE_VIDEO, second, "channel-b", "content-200")
        )
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_FORMAT_MISMATCH"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("dimensions differ"))
    }

    @Test
    fun reportsPerFeedWaitStateAndDecoderEvidenceBeforeVideoInputFormatArrives() {
        val group = "content-wait-state-301"
        HevcFeedProfileCapture.registerFeed(group, "channel-main")
        HevcFeedProfileCapture.registerFeed(group, "channel-onboard")
        HevcFeedProfileCapture.updateDrm(group, "channel-main", "L1", keysLoaded = true)
        HevcFeedProfileCapture.updateVideoDecoder(group, "channel-main", "c2.qti.hevc.decoder")
        HevcFeedProfileCapture.updateFirstFrameRendered(group, "channel-main")

        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_PROFILE_WAITING_FOR_VIDEO_INPUT_FORMAT"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("channel-main: WAITING_FOR_VIDEO_INPUT_FORMAT"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("DRM=L1"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("keysLoadedEventObserved=true"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("decoder=c2.qti.hevc.decoder"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("firstFrameRendered=true"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("channel-onboard: WAITING_FOR_VIDEO_INPUT_FORMAT"))

        val noCsdFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1")
            .setWidth(1920)
            .setHeight(1080)
            .build()
        HevcFeedProfileCapture.record(
            HevcFeedProfileProbe.inspect(C.TRACK_TYPE_VIDEO, noCsdFormat, "channel-main", group)
        )
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_PROFILE_CAPTURED"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_PROFILE_WAITING_FOR_VIDEO_INPUT_FORMAT"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("channel-onboard: WAITING_FOR_VIDEO_INPUT_FORMAT"))
    }
    @Test
    fun formatMatchDoesNotImplySyntheticTileAssemblerCompatibility() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.L93.B0")
            .setWidth(960)
            .setHeight(540)
            .setFrameRate(50f)
            .build()
        val group = "content-format-is-not-tile-proof"
        HevcFeedProfileCapture.record(
            HevcFeedProfileProbe.inspect(C.TRACK_TYPE_VIDEO, format, "channel-a", group)
        )
        HevcFeedProfileCapture.record(
            HevcFeedProfileProbe.inspect(C.TRACK_TYPE_VIDEO, format, "channel-b", group)
        )

        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_FORMAT_MATCH"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_TILE_ASSEMBLER_GATE=INCOMPLETE"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("secure-surface feasibility remain unverified"))
    }
    @Test
    fun tileTestWaitsForAndComparesOnlyTheTwoSelectedChannels() {
        val group = "content-manual-tile-test-44"
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.L93.B0")
            .setWidth(960)
            .setHeight(540)
            .setFrameRate(50f)
            .build()
        HevcFeedProfileCapture.record(
            HevcFeedProfileProbe.inspect(C.TRACK_TYPE_VIDEO, format, "channel-1001", group)
        )
        HevcFeedProfileCapture.record(
            HevcFeedProfileProbe.inspect(C.TRACK_TYPE_VIDEO, format, "channel-1002", group)
        )
        HevcFeedProfileCapture.record(
            HevcFeedProfileProbe.inspect(C.TRACK_TYPE_VIDEO, format, "channel-1003", group)
        )
        HevcFeedProfileCapture.selectTileTestFeeds(group, listOf("channel-1001", "channel-1003"))

        assertTrue(HevcFeedProfileCapture.diagnostic.contains("channel-1001"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("channel-1003"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_FORMAT_MATCH"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_TILE_ASSEMBLER_GATE=INCOMPLETE"))
        assertTrue(!HevcFeedProfileCapture.diagnostic.contains("channel-1002:"))
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
