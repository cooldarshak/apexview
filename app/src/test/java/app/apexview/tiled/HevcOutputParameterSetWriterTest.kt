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
    fun outputLevelAccountsForTileAndSliceLimitsInAdditionToPixelRate() {
        // The picture itself is small enough for a low level, but a 6-column tile grid
        // requires at least level 5.0 (level 4.1 permits only five tile columns).
        assertEquals(
            150,
            HevcOutputParameterSetWriter.requiredLevel(
                pictureSamples = 100_000,
                fpsNumerator = 25,
                fpsDenominator = 1,
                tileColumns = 6,
                tileRows = 2,
                sliceSegments = 12,
            )
        )
        // Eleven columns exceed level 5.2's ten-column cap, so level 6.0 is required.
        assertEquals(
            180,
            HevcOutputParameterSetWriter.requiredLevel(
                pictureSamples = 100_000,
                fpsNumerator = 25,
                fpsDenominator = 1,
                tileColumns = 11,
                tileRows = 2,
                sliceSegments = 22,
            )
        )
    }

    @Test
    fun outputLevelRejectsTileGridOrSliceCountAboveLevel62() {
        listOf(
            Triple(21, 1, 21),
            Triple(1, 23, 23),
            Triple(20, 20, 601),
        ).forEach { (columns, rows, slices) ->
            try {
                HevcOutputParameterSetWriter.requiredLevel(
                    pictureSamples = 100_000,
                    fpsNumerator = 25,
                    fpsDenominator = 1,
                    tileColumns = columns,
                    tileRows = rows,
                    sliceSegments = slices,
                )
                throw AssertionError("Unrepresentable HEVC grid must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("level 6.2 limits"))
            }
        }
    }

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
    fun mixedQualityAtlasDoesNotInheritSourceCropAndClipLast540pFeed() {
        val directory = fixtureDirectory()
        assumeTrue("Synthetic HEVC source fixture directory is not configured", directory != null)
        val source = readParameterSets(File(directory!!, "feed_a.hevc"))
        val sps = HevcParameterSets.parseSps(source.second)
        val pps = HevcParameterSets.parsePps(source.third, sps)
        // The S22 Ultra failure was a 1920x1632 coded atlas inherited an 8-pixel
        // bottom crop, losing part of the last 540p source. The fixture may use
        // another CTB size, so derive the coded atlas bounds from its actual SPS.
        assertTrue("Fixture must have a cropped bottom CTB row", sps.displayHeight < sps.height)
        val codedWidth = sps.picWidthInCtbs * sps.ctbSize * 2
        val codedHeight = sps.picHeightInCtbs * sps.ctbSize * 3
        val output = HevcOutputParameterSetWriter.generate(
            source.first, source.second, source.third,
            HevcOutputParameterSetWriter.Request(
                vpsId = HevcParameterSets.parseVps(source.first).vpsId,
                spsId = sps.spsId,
                ppsId = pps.ppsId,
                codedWidth = codedWidth,
                codedHeight = codedHeight,
                tileColumnWidthsInCtbs = List(2) { sps.picWidthInCtbs },
                tileRowHeightsInCtbs = List(3) { sps.picHeightInCtbs },
                loopFilterAcrossTilesEnabled = false,
                frameRateNumerator = 50,
                uncroppedOutputPicture = true
            )
        )
        val rewritten = HevcParameterSets.parseSps(output.sps)
        assertEquals(codedWidth, rewritten.width)
        assertEquals(codedHeight, rewritten.height)
        assertEquals(codedWidth, rewritten.displayWidth)
        assertEquals(codedHeight, rewritten.displayHeight)
        assertEquals(0, rewritten.conformanceWindowBottom)
        assertTrue(2 * sps.picHeightInCtbs * sps.ctbSize + sps.displayHeight <= rewritten.displayHeight)
    }

    @Test
    fun roundTripsRealKvazaarTwoByOneOutputHeadersForIndependentFfmpegDecode() {
        val directory = tileFixtureDirectory()
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

    private fun tileFixtureDirectory(): File? {
        val path = System.getenv("APEXVIEW_HEVC_TILE_FIXTURE_DIR")
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
