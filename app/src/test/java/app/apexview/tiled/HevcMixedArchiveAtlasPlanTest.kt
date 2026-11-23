package app.apexview.tiled

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real parsed SPS/PPS fixture metadata: this never modifies encrypted sample bytes. */
class HevcMixedArchiveAtlasPlanTest {
    private fun source(): HevcMixedArchiveAtlasPlan.Source {
        val directory = System.getenv("APEXVIEW_HEVC_FIXTURE_DIR")?.let(::File)
        assumeTrue("Independent HEVC fixture directory missing", directory?.isDirectory == true)
        val stream = File(directory!!, "feed_a.hevc")
        assumeTrue("Independent HEVC fixture missing", stream.isFile)
        val nals = HevcBitstream.splitAnnexB(stream.readBytes())
        val vps = HevcParameterSets.parseVps(nals.first { it.type == 32 }.bytes)
        val sps = HevcParameterSets.parseSps(nals.first { it.type == 33 }.bytes)
        val pps = HevcParameterSets.parsePps(nals.first { it.type == 34 }.bytes, sps)
        return HevcMixedArchiveAtlasPlan.Source(vps, sps, pps)
    }

    @Test fun equalSourcesHaveExactlyTwoCodedPicturesAndOneSourceRectPerFeed() {
        val sample = source()
        val selected = HevcMixedArchiveAtlasPlan.choose(listOf(sample, sample),
            maximumWidth = 4096, maximumHeight = 2176)
        assertNotNull(selected)
        val atlas = selected!!
        assertEquals(2L * sample.sps.width * sample.sps.height,
            atlas.codedWidth.toLong() * atlas.codedHeight)
        assertEquals(2, atlas.sourceRects.size)
        assertEquals(2, atlas.cells.size)
        assertTrue(atlas.smallestAreaProven)
        assertEquals(0, atlas.rejectedDecoderCapabilities)
        assertFalse(atlas.searchBudgetLimited)
    }

    @Test fun allDeviceRejectedCandidatesFailClosedRatherThanSwitchingDecoders() {
        val source = source()
        val rejected = HevcMixedArchiveAtlasPlan.choose(listOf(source, source),
            outputEligible = { _, _ -> false })
        assertNull(rejected)
    }

    @Test fun differentSourceWidthCannotInventATileOrExceedBoundaries() {
        val first = source()
        val ctb = first.sps.ctbSize
        val secondWidthCtbs = (first.sps.picWidthInCtbs / 2).coerceAtLeast(1)
        val secondWidth = secondWidthCtbs * ctb
        assumeTrue("Fixture must offer room for a smaller compatible tile",
            secondWidth < first.sps.width)
        val sps = first.sps.copy(width = secondWidth, displayWidth = secondWidth,
            picWidthInCtbs = secondWidthCtbs, conformanceWindowRight = 0)
        val pps = first.pps.copy(numTileColumns = 1, numTileRows = 1,
            tileColumnWidthsInCtbs = listOf(secondWidthCtbs),
            tileRowHeightsInCtbs = listOf(sps.picHeightInCtbs),
            explicitColumnWidthsMinus1 = emptyList(), explicitRowHeightsMinus1 = emptyList())
        val second = HevcMixedArchiveAtlasPlan.Source(first.vps, sps, pps)
        val atlas = HevcMixedArchiveAtlasPlan.choose(listOf(first, second),
            maximumWidth = 4096, maximumHeight = 2176)
        assertNotNull("Compatible uneven source geometry must find a real bounded atlas", atlas)
        if (atlas != null) {
            assertEquals(2, atlas.sourceRects.size)
            assertEquals(secondWidth, atlas.sourceRects[1].width)
            assertTrue(atlas.codedWidth <= 4096 && atlas.codedHeight <= 2176)
            assertTrue(atlas.codedWidth.toLong() * atlas.codedHeight >=
                first.sps.width.toLong() * first.sps.height +
                secondWidth.toLong() * second.sps.height)
            assertTrue(atlas.cells.map { it.feedIndex }.containsAll(listOf(0, 1)))
            assertTrue(atlas.smallestAreaProven || atlas.searchBudgetLimited)
        }
        // A size that cannot contain even the first source must always reject.
        assertNull(HevcMixedArchiveAtlasPlan.choose(listOf(first, second),
            maximumWidth = ctb, maximumHeight = ctb))
    }

    @Test fun invalidOrOverflowingBoundsAreRejectedWithoutThrowing() {
        val first = source()
        assertNull(HevcMixedArchiveAtlasPlan.choose(listOf(first, first), maximumWidth = 0))
        assertNull(HevcMixedArchiveAtlasPlan.choose(listOf(first, first), maximumHeight = 0))
        assertNull(HevcMixedArchiveAtlasPlan.choose(emptyList()))
    }
}
