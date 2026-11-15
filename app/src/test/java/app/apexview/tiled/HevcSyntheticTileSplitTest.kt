package app.apexview.tiled

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HevcSyntheticTileSplitTest {
    @Test fun separatesAndReassemblesTwoComplementaryTilesFromFiftyPictures() {
        val fixturePath = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
        assumeTrue("Kvazaar tile fixture directory is not configured", !fixturePath.isNullOrBlank())
        val directory = File(fixturePath!!)
        val source = File(directory, "source_tiled.hevc")
        val leftFile = File(directory, "feed_left.hevc")
        val rightFile = File(directory, "feed_right.hevc")
        val rebuiltFile = File(directory, "reassembled.hevc")
        assumeTrue(
            "Synthetic complementary tile fixture files are missing",
            source.isFile && leftFile.isFile && rightFile.isFile && rebuiltFile.isFile
        )

        val sourceBytes = source.readBytes()
        val leftNals = HevcBitstream.splitAnnexB(leftFile.readBytes())
        val rightNals = HevcBitstream.splitAnnexB(rightFile.readBytes())
        val sourceNals = HevcBitstream.splitAnnexB(sourceBytes)
        val rebuiltBytes = rebuiltFile.readBytes()

        val sourceVcl = sourceNals.filter { it.type in 0..31 }
        val leftVcl = leftNals.filter { it.type in 0..31 }
        val rightVcl = rightNals.filter { it.type in 0..31 }
        assertEquals(100, sourceVcl.size)
        assertEquals(50, leftVcl.size)
        assertEquals(50, rightVcl.size)

        // Left feed carries the first tile of each picture. Right feed carries the
        // continuation tile slice and is intentionally not a standalone full picture.
        assertTrue(leftVcl.all { (it.bytes[2].toInt() and 0x80) != 0 })
        assertTrue(rightVcl.all { (it.bytes[2].toInt() and 0x80) == 0 })

        for (units in listOf(sourceNals, leftNals, rightNals)) {
            assertTrue(units.any { it.type == 32 })
            assertTrue(units.any { it.type == 33 })
            assertTrue(units.any { it.type == 34 })
        }

        val spsNal = sourceNals.single { it.type == 33 }
        val ppsNal = sourceNals.single { it.type == 34 }
        val sps = HevcParameterSets.parseSps(spsNal.bytes)
        val pps = HevcParameterSets.parsePps(ppsNal.bytes, sps)
        assertEquals(256, sps.width)
        assertEquals(128, sps.height)
        assertTrue(pps.tilesEnabled)
        assertFalse(pps.entropyCodingSyncEnabled)
        assertEquals(2, pps.numTileColumns)
        assertEquals(1, pps.numTileRows)
        assertEquals(listOf(2, 2), pps.tileColumnWidthsInCtbs)

        val sourceFrames = HevcStreamAlignmentAnalyzer.analyze(sourceBytes)
        val rebuiltFrames = HevcStreamAlignmentAnalyzer.analyze(rebuiltBytes)
        assertEquals(50, sourceFrames.size)
        assertEquals(sourceFrames, rebuiltFrames)
        assertTrue(sourceFrames.all { it.sliceSegmentAddresses.size == 2 })
        assertTrue(sourceFrames.all { it.sliceSegmentAddresses.distinct().size == 2 })
    }
}
