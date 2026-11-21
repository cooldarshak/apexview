package app.apexview.tiled

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Verify that direct NAL consumption does not change HEVC tile payloads or generated metadata. */
class HevcDirectNalCompositionTest {
    @Test fun directNalHandoffMatchesAnnexBComposerByteForByte() {
        val fixtures = System.getenv("APEXVIEW_HEVC_FIXTURE_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("HEVC fixtures are not configured", fixtures != null)
        val streams = listOf("feed_a.hevc", "feed_b.hevc")
            .map { File(fixtures!!, it).readBytes() }
        val grid = HevcOutputGridDimensions(columns = 2, rows = 1)
        val cells = listOf(
            HevcTizenGridComposer.CellSelection(0, 0, 0, 0),
            HevcTizenGridComposer.CellSelection(1, 0, 1, 0)
        )
        val original = HevcTizenGridComposer.composeAnnexBStreams(
            streams, grid, cells, frameRateNumerator = 25,
            includeAnnexBOutput = false, emitSampleData = false
        )
        val direct = HevcTizenGridComposer.composeNalUnits(
            streams.map { HevcBitstream.splitAnnexB(it) }, grid, cells,
            frameRateNumerator = 25, includeAnnexBOutput = false, emitSampleData = false
        )
        assertArrayEquals(original.outputParameters.vps, direct.outputParameters.vps)
        assertArrayEquals(original.outputParameters.sps, direct.outputParameters.sps)
        assertArrayEquals(original.outputParameters.pps, direct.outputParameters.pps)
        assertEquals(original.codedWidth, direct.codedWidth)
        assertEquals(original.codedHeight, direct.codedHeight)
        assertEquals(original.sourcePictureIdentities, direct.sourcePictureIdentities)
        assertEquals(original.pictures.size, direct.pictures.size)
        original.pictures.zip(direct.pictures).forEach { (before, after) ->
            assertEquals(before.fullPoc, after.fullPoc)
            assertEquals(before.evidence, after.evidence)
            assertEquals(before.vclNals.size, after.vclNals.size)
            before.vclNals.zip(after.vclNals).forEach { (left, right) ->
                assertArrayEquals(left, right)
            }
        }
    }
}
