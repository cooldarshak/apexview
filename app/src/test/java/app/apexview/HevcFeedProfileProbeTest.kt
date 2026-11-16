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
        val secondSnapshot = HevcFeedProfileProbe.inspect(
            C.TRACK_TYPE_VIDEO, second, "channel-2", "content-100"
        )
        HevcFeedProfileCapture.record(secondSnapshot)
        HevcFeedProfileCapture.selectTileTestFeeds("content-100", listOf("channel-1", "channel-2"))
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
        val tileDiagnostic = HevcFeedProfileCapture.diagnostic
        assertTrue(tileDiagnostic.contains("channel-1: HEVC_TILE_METADATA"))
        assertTrue(tileDiagnostic.contains("channel-2: HEVC_TILE_METADATA"))
        assertTrue(tileDiagnostic.contains("tilesEnabled=true"))
        assertTrue(tileDiagnostic.contains("numTileColumns=2"))
        assertTrue(tileDiagnostic.contains("numTileRows=1"))
        assertTrue(Regex("ctbPictureWidthInCtbs=\\d+").containsMatchIn(tileDiagnostic))
        assertTrue(Regex("ctbPictureHeightInCtbs=\\d+").containsMatchIn(tileDiagnostic))
        assertTrue(tileDiagnostic.contains("tileColumnWidthsInCtbs="))
        assertTrue(tileDiagnostic.contains("tileRowHeightsInCtbs="))
        assertTrue(tileDiagnostic.contains("deblockingFilterOverrideEnabled="))
        assertTrue(tileDiagnostic.contains("HEVC_TILE_PARSED_PPS_STRUCTURES_IDENTICAL=true"))

        val singlePictureSnapshot = secondSnapshot.copy(
            parsedPps = secondSnapshot.parsedPps.map { pps ->
                val matchingSps = secondSnapshot.parsedSps.single { it.spsId == pps.spsId }
                pps.copy(
                    tilesEnabled = false,
                    numTileColumns = 1,
                    numTileRows = 1,
                    tileColumnWidthsInCtbs = listOf(matchingSps.picWidthInCtbs),
                    tileRowHeightsInCtbs = listOf(matchingSps.picHeightInCtbs)
                )
            }
        )
        HevcFeedProfileCapture.record(singlePictureSnapshot)
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_TILE_ASSEMBLER_GATE=REJECT"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("no multi-tile grid"))
        HevcFeedProfileCapture.record(secondSnapshot)

        val changedPpsSnapshot = secondSnapshot.copy(
            parsedPps = secondSnapshot.parsedPps.map { it.copy(numTileRows = it.numTileRows + 1) }
        )
        HevcFeedProfileCapture.record(changedPpsSnapshot)
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("numTileRows=2"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_TILE_PARSED_PPS_STRUCTURES_IDENTICAL=false"))

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
    fun incompleteFormatCannotPassSyntheticTileAssemblerGate() {
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

        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_FORMAT_MISMATCH"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_TILE_ASSEMBLER_GATE=INCOMPLETE"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("tilesEnabled=unavailable"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("ctbPictureWidthInCtbs=unavailable"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("deblockingFilterOverrideEnabled=unavailable"))
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_TILE_PARSED_PPS_STRUCTURES_IDENTICAL=UNKNOWN"))
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
        assertTrue(HevcFeedProfileCapture.diagnostic.contains("HEVC_FORMAT_MISMATCH"))
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
