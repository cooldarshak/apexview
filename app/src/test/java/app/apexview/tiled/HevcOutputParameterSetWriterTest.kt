package app.apexview.tiled

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HevcOutputParameterSetWriterTest {
    @Test
    fun buildsFreshTwoByOneOutputGridFromIndependentNonTiledSourceParameters() {
        val directory = fixtureDirectory()
        assumeTrue("Synthetic HEVC source fixture directory is not configured", directory != null)
        val source = readParameterSets(File(directory!!, "feed_a.hevc"))
        val sps = HevcParameterSets.parseSps(source.second)
        val pps = HevcParameterSets.parsePps(source.third, sps)
        assertFalse("Input feed PPS is deliberately non-tiled", pps.tilesEnabled)
        assertFalse("Input feed PPS must not use unsupported WPP coding", pps.entropyCodingSyncEnabled)

        val ctbWidth = sps.picWidthInCtbs
        val ctbHeight = sps.picHeightInCtbs
        val output = HevcOutputParameterSetWriter.generate(
            source.first, source.second, source.third,
            HevcOutputParameterSetWriter.Request(
                vpsId = (HevcParameterSets.parseVps(source.first).vpsId + 1) % 16,
                spsId = (sps.spsId + 1) % 16,
                ppsId = (pps.ppsId + 1) % 64,
                codedWidth = sps.width * 2,
                codedHeight = sps.height,
                tileColumnWidthsInCtbs = listOf(ctbWidth, ctbWidth),
                tileRowHeightsInCtbs = listOf(ctbHeight),
                loopFilterAcrossTilesEnabled = false,
                frameRateNumerator = 50
            )
        )

        val outputVps = HevcParameterSets.parseVps(output.vps)
        val outputSps = HevcParameterSets.parseSps(output.sps)
        val outputPps = HevcParameterSets.parsePps(output.pps, outputSps)
        assertEquals(output.levelIdc, outputVps.profileTierLevel.levelIdc)
        assertEquals(output.levelIdc, outputSps.profileTierLevel.levelIdc)
        assertEquals(1920, outputSps.width)
        assertEquals(544, outputSps.height)
        assertEquals(1920, outputSps.displayWidth)
        assertEquals(540, outputSps.displayHeight)
        assertEquals(listOf(ctbWidth, ctbWidth), outputPps.tileColumnWidthsInCtbs)
        assertEquals(listOf(ctbHeight), outputPps.tileRowHeightsInCtbs)
        assertEquals(ctbWidth * 2, outputSps.picWidthInCtbs)
        assertEquals(ctbHeight, outputSps.picHeightInCtbs)
        assertEquals(2, outputPps.numTileColumns)
        assertEquals(1, outputPps.numTileRows)
        assertTrue(outputPps.tilesEnabled)
        assertFalse(outputPps.entropyCodingSyncEnabled)
    }

    @Test
    fun roundTripsRealKvazaarTwoByOneOutputHeadersForIndependentFfmpegDecode() {
        val directory = fixtureDirectory()
        assumeTrue("Synthetic HEVC tile fixture directory is not configured", directory != null)
        val sourceFile = File(directory!!, "reassembled.hevc")
        assumeTrue("Kvazaar reassembled tile fixture is not present", sourceFile.isFile)
        val source = readParameterSets(sourceFile)
        val sourceVps = HevcParameterSets.parseVps(source.first)
        val sourceSps = HevcParameterSets.parseSps(source.second)
        val sourcePps = HevcParameterSets.parsePps(source.third, sourceSps)
        assertTrue("Kvazaar fixture must exercise a source tile grid", sourcePps.tilesEnabled)

        val output = HevcOutputParameterSetWriter.generate(
            source.first, source.second, source.third,
            HevcOutputParameterSetWriter.Request(
                vpsId = sourceVps.vpsId,
                spsId = sourceSps.spsId,
                ppsId = sourcePps.ppsId,
                codedWidth = sourceSps.width,
                codedHeight = sourceSps.height,
                tileColumnWidthsInCtbs = requireNotNull(sourcePps.tileColumnWidthsInCtbs),
                tileRowHeightsInCtbs = requireNotNull(sourcePps.tileRowHeightsInCtbs),
                loopFilterAcrossTilesEnabled = requireNotNull(sourcePps.loopFilterAcrossTiles),
                frameRateNumerator = 25
            )
        )

        assertEquals(sourceSps.width, output.codedWidth)
        assertEquals(sourceSps.height, output.codedHeight)
        assertEquals(sourcePps.tileColumnWidthsInCtbs, output.tileColumnWidthsInCtbs)
        assertEquals(sourcePps.tileRowHeightsInCtbs, output.tileRowHeightsInCtbs)

        val outputDirectory = System.getenv("APEXVIEW_HEVC_OUTPUT_PARAMETER_DIR")
        assumeTrue("Output parameter-set artifact directory is not configured", !outputDirectory.isNullOrBlank())
        val targetDirectory = File(outputDirectory!!)
        assertTrue(targetDirectory.mkdirs() || targetDirectory.isDirectory)
        File(targetDirectory, "generated_parameter_sets.hevc").outputStream().use { stream ->
            listOf(output.vps, output.sps, output.pps).forEach { nal ->
                stream.write(byteArrayOf(0, 0, 0, 1))
                stream.write(nal)
            }
        }
        File(targetDirectory, "generated_parameter_sets.json").writeText(
            """{
              "result": "PARAMETER_SET_ROUND_TRIP_PASS",
              "source": "Kvazaar synthetic 2x1 tile fixture",
              "width": ${output.codedWidth},
              "height": ${output.codedHeight},
              "tile_columns_in_ctbs": ${output.tileColumnWidthsInCtbs},
              "tile_rows_in_ctbs": ${output.tileRowHeightsInCtbs},
              "level_idc": ${output.levelIdc}
            }
            """.trimIndent() + "\n"
        )
    }

    @Test
    fun rejectsInvalidOutputTileCoverageAndUnsupportedRate() {
        val directory = fixtureDirectory()
        assumeTrue("Synthetic HEVC source fixture directory is not configured", directory != null)
        val source = readParameterSets(File(directory!!, "feed_a.hevc"))
        val sps = HevcParameterSets.parseSps(source.second)
        val pps = HevcParameterSets.parsePps(source.third, sps)

        fun request(columns: List<Int>, fpsNumerator: Int = 50, width: Int = sps.width * 2) =
            HevcOutputParameterSetWriter.Request(
                vpsId = 0, spsId = 0, ppsId = 0,
                codedWidth = width, codedHeight = sps.height,
                tileColumnWidthsInCtbs = columns,
                tileRowHeightsInCtbs = listOf(sps.picHeightInCtbs),
                loopFilterAcrossTilesEnabled = false,
                frameRateNumerator = fpsNumerator
            )

        try {
            HevcOutputParameterSetWriter.generate(
                source.first, source.second, source.third,
                request(listOf(sps.picWidthInCtbs, sps.picWidthInCtbs - 1))
            )
            throw AssertionError("Mismatched output tile coverage must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("do not cover"))
        }

        try {
            HevcOutputParameterSetWriter.generate(
                source.first, source.second, source.third,
                request(listOf(sps.picWidthInCtbs, sps.picWidthInCtbs), fpsNumerator = 100_000)
            )
            throw AssertionError("Unachievable output sample rate must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("level 6.2"))
        }
    }

    private fun fixtureDirectory(): File? {
        val path = System.getenv("APEXVIEW_HEVC_FIXTURE_DIR")
        return if (path.isNullOrBlank()) null else File(path)
    }

    private fun readParameterSets(file: File): Triple<ByteArray, ByteArray, ByteArray> {
        assumeTrue("Synthetic HEVC fixture is not present: ${file.name}", file.isFile)
        val nals = HevcBitstream.splitAnnexB(file.readBytes())
        return Triple(
            nals.first { it.type == 32 }.bytes,
            nals.first { it.type == 33 }.bytes,
            nals.first { it.type == 34 }.bytes
        )
    }
}
