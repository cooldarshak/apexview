package app.apexview.tiled

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HevcSyntheticFeedAlignmentTest {
    @Test
    fun parsesAndComparesRealFiftyFrameFfmpegSyntheticFeeds() {
        val fixtureDirectory = System.getenv("APEXVIEW_HEVC_FIXTURE_DIR")
        assumeTrue("CI synthetic HEVC fixture directory is not configured", !fixtureDirectory.isNullOrBlank())
        val directory = File(fixtureDirectory!!)
        val feedAFile = File(directory, "feed_a.hevc")
        val feedBFile = File(directory, "feed_b.hevc")
        assumeTrue("Synthetic HEVC feed pair is not present", feedAFile.isFile && feedBFile.isFile)

        val feedABytes = feedAFile.readBytes()
        val feedBBytes = feedBFile.readBytes()
        assertTrue("The two source encodes must be distinct bitstreams", !feedABytes.contentEquals(feedBBytes))
        val feedAHash = MessageDigest.getInstance("SHA-256").digest(feedABytes)
        val feedBHash = MessageDigest.getInstance("SHA-256").digest(feedBBytes)
        assertTrue("Independent feed SHA-256 hashes must differ", !feedAHash.contentEquals(feedBHash))

        val feedAParameterSets = parseSourceParameterSets(feedABytes)
        val feedBParameterSets = parseSourceParameterSets(feedBBytes)
        // HEVC codes complete CTBs (64x64 here), so the coded height is padded to 544.
        // The SPS conformance window must expose the intended 960x540 display rectangle.
        assertEquals(960, feedAParameterSets.first.displayWidth)
        assertEquals(540, feedAParameterSets.first.displayHeight)
        assertEquals(960, feedBParameterSets.first.displayWidth)
        assertEquals(540, feedBParameterSets.first.displayHeight)
        assertEquals(544, feedAParameterSets.first.height)
        assertEquals(544, feedBParameterSets.first.height)
        assertTrue("Feed A must be a 1x1 source picture", !feedAParameterSets.second.tilesEnabled)
        assertTrue("Feed B must be a 1x1 source picture", !feedBParameterSets.second.tilesEnabled)

        val feedA = HevcStreamAlignmentAnalyzer.analyze(feedABytes)
        val feedB = HevcStreamAlignmentAnalyzer.analyze(feedBBytes)
        val report = HevcStreamAlignmentAnalyzer.compare(feedA, feedB)

        assertEquals(50, feedA.size)
        assertEquals(50, feedB.size)
        assertTrue("Synthetic feeds should have identical POC sequences", report.pocAligned)
        assertTrue("Synthetic feeds should have identical slice types and RPS", report.referencesAligned)
        assertTrue(
            "Every synthetic frame signature should align. First differences: " +
                report.mismatches.take(5).joinToString { mismatch ->
                    val first = feedA[mismatch.decodeIndex].pictureIdentity
                    val second = feedB[mismatch.decodeIndex].pictureIdentity
                    "index=${mismatch.decodeIndex} type=${first.nalUnitType}/${second.nalUnitType} " +
                        "pps=${first.ppsId}/${second.ppsId} noOutput=${first.noOutputOfPriorPictures}/${second.noOutputOfPriorPictures} " +
                        "sliceType=${first.sliceType}/${second.sliceType} picOutput=${first.picOutputFlag}/${second.picOutputFlag} " +
                        "colourPlane=${first.colourPlaneId}/${second.colourPlaneId} pocLsb=${first.picOrderCntLsb}/${second.picOrderCntLsb} " +
                        "rps=${first.shortTermRpsFromSps}:${first.shortTermRpsIndex}/${second.shortTermRpsFromSps}:${second.shortTermRpsIndex}"
                },
            report.fullyAligned
        )
        assertTrue("P frames should expose short-term references", feedA.drop(1).any { it.references.isNotEmpty() })
    }

    private fun parseSourceParameterSets(
        annexB: ByteArray
    ): Pair<HevcParameterSets.SpsMetadata, HevcParameterSets.PpsMetadata> {
        val nals = HevcBitstream.splitAnnexB(annexB)
        val sps = HevcParameterSets.parseSps(nals.first { it.type == 33 }.bytes)
        val pps = HevcParameterSets.parsePps(nals.first { it.type == 34 }.bytes, sps)
        return sps to pps
    }
}
