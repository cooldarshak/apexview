package app.apexview.tiled

import java.io.File
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

        val feedA = HevcStreamAlignmentAnalyzer.analyze(feedAFile.readBytes())
        val feedB = HevcStreamAlignmentAnalyzer.analyze(feedBFile.readBytes())
        val report = HevcStreamAlignmentAnalyzer.compare(feedA, feedB)

        assertEquals(50, feedA.size)
        assertEquals(50, feedB.size)
        assertTrue("Synthetic feeds should have identical POC sequences", report.pocAligned)
        if (!report.referencesAligned) {
            println("HEVC reference-alignment mismatches: ${report.mismatches.take(8)}")
            feedA.take(8).forEachIndexed { index, frame ->
                println("HEVC feed A frame $index refs=${frame.references}")
                println("HEVC feed B frame $index refs=${feedB.getOrNull(index)?.references}")
            }
        }
        assertTrue("Synthetic feeds should have identical slice types and RPS", report.referencesAligned)
        assertTrue("Every synthetic frame signature should align", report.fullyAligned)
        assertTrue("P frames should expose short-term references", feedA.drop(1).any { it.references.isNotEmpty() })
    }
}
