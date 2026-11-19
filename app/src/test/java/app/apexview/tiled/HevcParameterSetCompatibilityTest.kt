package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcParameterSetCompatibilityTest {
    @Test fun acceptsIndependentNonTiledSourcePicturesAsCellsInANewOutputGrid() {
        val first = fixture(tilesEnabled = false)
        val second = fixture(tilesEnabled = false).withDifferentParameterSetIds()

        val parameterResult = compare(first, second)
        assertTrue(parameterResult.reasons.toString(), parameterResult.compatible)

        val cellA = fullCell(first, "channel-1004")
        val cellB = fullCell(second, "channel-1009")
        val sourceResult = HevcSourceCellCompatibility.compare(cellA, cellB)
        assertTrue(sourceResult.reasons.toString(), sourceResult.compatible)

        val output = HevcOutputGridValidator.validate(
            HevcOutputGridDimensions(columns = 2, rows = 1),
            listOf(
                HevcOutputCellPlacement(cellA, column = 0, row = 0),
                HevcOutputCellPlacement(cellB, column = 1, row = 0)
            )
        )
        assertTrue(output.reasons.toString(), output.compatible)
        assertNotNull(output.geometry)
        assertEquals(2560, output.geometry!!.codedWidth)
        assertEquals(720, output.geometry.codedHeight)
        assertEquals(40, output.geometry.pictureWidthInCtbs)
        assertEquals(12, output.geometry.pictureHeightInCtbs)
        assertEquals(listOf(20, 20), output.geometry.tileColumnWidthsInCtbs)
        assertEquals(listOf(12), output.geometry.tileRowHeightsInCtbs)
    }

    @Test fun allowsDifferentValidSourcePpsTileLayoutsWithoutConfusingThemWithOutputGrid() {
        val fullPicture = fixture(tilesEnabled = false)
        val sourceWithTwoTiles = fixture(tilesEnabled = true)
        val result = compare(fullPicture, sourceWithTwoTiles)
        assertTrue(result.reasons.toString(), result.compatible)

        val tileCell = HevcSourceCellSelection(
            picture = picture(sourceWithTwoTiles, "channel-tiled"),
            leftCtb = 0,
            topCtb = 0,
            widthInCtbs = 10,
            heightInCtbs = 12
        )
        assertTrue(
            HevcSourceCellCompatibility.validateSelection(tileCell).toString(),
            HevcSourceCellCompatibility.validateSelection(tileCell).isEmpty()
        )
    }

    @Test fun rejectsSourceCellsWithDifferentGeometryWhenTheyCannotMapToTheSameOutputCell() {
        val first = fixture(tilesEnabled = false)
        val secondBase = fixture(tilesEnabled = false)
        val secondSps = secondBase.sps.copy(
            width = 960,
            height = 540,
            displayWidth = 960,
            displayHeight = 540,
            picWidthInCtbs = 15,
            picHeightInCtbs = 9
        )
        val secondPps = secondBase.pps.copy(
            tileColumnWidthsInCtbs = listOf(15),
            tileRowHeightsInCtbs = listOf(9)
        )
        val second = secondBase.copy(sps = secondSps, pps = secondPps)

        // Different source picture sizes are allowed at the parameter-set layer. The selected
        // cells must still map to equal coded dimensions before an output grid can be formed.
        assertTrue(compare(first, second).reasons.toString(), compare(first, second).compatible)
        val output = HevcOutputGridValidator.validate(
            HevcOutputGridDimensions(2, 1),
            listOf(
                HevcOutputCellPlacement(fullCell(first, "feed-a"), 0, 0),
                HevcOutputCellPlacement(fullCell(second, "feed-b"), 1, 0)
            )
        )
        assertFalse(output.compatible)
        assertTrue(output.reasons.any { it.contains("output row") || it.contains("source cell") })
    }

    @Test fun mixed1080AndThree540SourceTileGridsFitOneHevcAtlasWhenAligned() {
        val base = fixture(tilesEnabled = false)
        val common = base.sps.copy(ctbSize = 32, log2CtbSize = 5)
        val large = HevcMixedArchiveAtlasPlan.Source(base.vps,
            common.copy(width = 1920, height = 1088, displayWidth = 1920, displayHeight = 1080,
                picWidthInCtbs = 60, picHeightInCtbs = 34, conformanceWindowBottom = 8),
            base.pps.copy(tilesEnabled = true, uniformTileSpacing = false,
                numTileColumns = 2, numTileRows = 2,
                explicitColumnWidthsMinus1 = listOf(29), explicitRowHeightsMinus1 = listOf(16),
                tileColumnWidthsInCtbs = listOf(30, 30), tileRowHeightsInCtbs = listOf(17, 17),
                loopFilterAcrossTiles = true))
        val small = HevcMixedArchiveAtlasPlan.Source(base.vps,
            common.copy(width = 960, height = 544, displayWidth = 960, displayHeight = 540,
                picWidthInCtbs = 30, picHeightInCtbs = 17, conformanceWindowBottom = 4),
            base.pps.copy(tileColumnWidthsInCtbs = listOf(30), tileRowHeightsInCtbs = listOf(17)))
        val plan = HevcMixedArchiveAtlasPlan.choose(listOf(large, small, small, small))
        assertNotNull("Four independently selected feeds must fit a bounded one-decoder picture", plan)
        assertEquals(1920, plan!!.codedWidth)
        assertEquals(2176, plan.codedHeight)
        assertEquals(1920, plan.sourceRects[0].width)
        assertEquals(1080, plan.sourceRects[0].height)
        assertEquals(3, plan.sourceRects.drop(1).size)
        assertEquals(8, plan.cells.size)
    }

    @Test fun rejectsDifferentCtbSizeBitDepthChromaAndProfileConstraints() {
        val f = fixture(tilesEnabled = false)

        val differentCtb = f.sps.copy(ctbSize = 32, log2CtbSize = 5)
        val ctbResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, f.pps, f.vps, differentCtb, f.pps
        )
        assertFalse(ctbResult.compatible)
        assertTrue(ctbResult.reasons.any { it.contains("CTB sizes differ") })

        val bitDepth = f.sps.copy(bitDepthLuma = f.sps.bitDepthLuma + 2)
        val bitDepthResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, f.pps, f.vps, bitDepth, f.pps
        )
        assertFalse(bitDepthResult.compatible)
        assertTrue(bitDepthResult.reasons.any { it.contains("bit-depth") })

        val chroma = f.sps.copy(chromaFormatIdc = 2)
        val chromaResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, f.pps, f.vps, chroma, f.pps
        )
        assertFalse(chromaResult.compatible)
        assertTrue(chromaResult.reasons.any { it.contains("chroma-format") })

        val profile = f.sps.copy(
            profileTierLevel = f.sps.profileTierLevel.copy(profileIdc = 2)
        )
        val profileResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, f.pps, f.vps, profile, f.pps
        )
        assertFalse(profileResult.compatible)
        assertTrue(profileResult.reasons.any { it.contains("profile or tier") })
    }

    @Test fun acceptsDifferentLevelIdsAndIndependentVuiTimingButRejectsDifferentReferenceStructures() {
        val f = fixture(tilesEnabled = false)
        val otherLevel = f.sps.copy(
            profileTierLevel = f.sps.profileTierLevel.copy(levelIdc = 123)
        )
        val levelResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, f.pps, f.vps, otherLevel, f.pps
        )
        assertTrue(levelResult.reasons.toString(), levelResult.compatible)

        val differentPocWidth = f.sps.copy(log2MaxPicOrderCntLsb = f.sps.log2MaxPicOrderCntLsb + 1)
        val pocResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, f.pps, f.vps, differentPocWidth, f.pps
        )
        assertFalse(pocResult.compatible)
        assertTrue(pocResult.reasons.any { it.contains("POC widths") })

        val differentReferences = f.sps.copy(
            shortTermRefPicSets = listOf(
                HevcParameterSets.ShortTermRefPicSet(
                    negative = listOf(HevcParameterSets.ReferenceDelta(-1, true)),
                    positive = emptyList()
                )
            )
        )
        val referenceResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, f.pps, f.vps, differentReferences, f.pps
        )
        assertFalse(referenceResult.compatible)
        assertTrue(referenceResult.reasons.any { it.contains("reference-picture structures") })

        val vui = HevcParameterSets.VuiMetadata(
            aspectRatioIdc = null,
            sarWidth = null,
            sarHeight = null,
            videoFormat = 5,
            videoFullRange = true,
            colourPrimaries = 1,
            transferCharacteristics = 2,
            matrixCoefficients = 3,
            timingInfoPresent = true,
            numUnitsInTick = 1,
            timeScale = 50,
            hrdParametersPresent = false,
            bitstreamRestrictionPresent = false
        )
        val timed = f.sps.copy(vuiParametersPresent = true, vuiMetadata = vui)
        val differentTiming = timed.copy(vuiMetadata = vui.copy(timeScale = 25))
        val timingResult = HevcParameterSetCompatibility.compare(
            f.vps, timed, f.pps, f.vps, differentTiming, f.pps
        )
        // VUI timing belongs to the source track. Different complete timing metadata is
        // allowed at the coded-cell parameter-set layer; WP4 validates sample timestamps.
        assertTrue(timingResult.reasons.toString(), timingResult.compatible)

        // Do not silently accept a VUI that the bounded parser could not fully understand.
        val incompleteVui = timed.copy(vuiMetadata = null)
        val incompleteVuiResult = HevcParameterSetCompatibility.compare(
            f.vps, incompleteVui, f.pps, f.vps, timed, f.pps
        )
        assertFalse(incompleteVuiResult.compatible)
        assertTrue(incompleteVuiResult.reasons.any {
            it.contains("VUI metadata is present but could not be parsed completely")
        })
    }

    @Test fun acceptsDifferentSourceCropsButRejectsInvalidTileGeometryAndUnsupportedSliceSyntax() {
        val f = fixture(tilesEnabled = false)

        val cropped = f.sps.copy(
            conformanceWindowLeft = 1,
            displayWidth = f.sps.width - 2
        )
        val cropResult = HevcParameterSetCompatibility.compare(
            f.vps, cropped, f.pps, f.vps, f.sps, f.pps
        )
        assertTrue(cropResult.reasons.toString(), cropResult.compatible)

        val malformedTiles = f.pps.copy(
            tilesEnabled = true,
            uniformTileSpacing = false,
            numTileColumns = 2,
            numTileRows = 1,
            explicitColumnWidthsMinus1 = listOf(4),
            explicitRowHeightsMinus1 = emptyList(),
            tileColumnWidthsInCtbs = listOf(5, 5),
            tileRowHeightsInCtbs = listOf(12),
            loopFilterAcrossTiles = true
        )
        val tileResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, malformedTiles, f.vps, f.sps, malformedTiles
        )
        assertFalse(tileResult.compatible)
        assertTrue(tileResult.reasons.any { it.contains("does not cover its complete source picture") })

        val wavefront = f.pps.copy(entropyCodingSyncEnabled = true)
        val wavefrontResult = HevcParameterSetCompatibility.compare(
            f.vps, f.sps, wavefront, f.vps, f.sps, wavefront
        )
        assertFalse(wavefrontResult.compatible)
        assertTrue(wavefrontResult.reasons.any { it.contains("Wavefront") })

        val deblockingSource = fixture(tilesEnabled = false, deblockingOverride = true)
        val equivalentOverride = deblockingSource.withDifferentParameterSetIds()
        val deblockingResult = compare(deblockingSource, equivalentOverride)
        assertTrue(deblockingResult.reasons.toString(), deblockingResult.compatible)

        val mismatchedOverride = compare(deblockingSource, fixture(tilesEnabled = false))
        assertFalse(mismatchedOverride.compatible)
        assertTrue(mismatchedOverride.reasons.any {
            it.contains("PPS slice/coding syntax differs")
        })
    }

    @Test fun rejectsInvalidOutputPlacementsAndDimensionOverflow() {
        val f = fixture(tilesEnabled = false)
        val a = fullCell(f, "feed-a")
        val b = fullCell(f, "feed-b")

        val duplicate = HevcOutputGridValidator.validate(
            HevcOutputGridDimensions(2, 1),
            listOf(
                HevcOutputCellPlacement(a, 0, 0),
                HevcOutputCellPlacement(b, 0, 0)
            )
        )
        assertFalse(duplicate.compatible)
        assertTrue(duplicate.reasons.any { it.contains("duplicate") || it.contains("unfilled") })

        val tooWideSps = f.sps.copy(
            width = 32768,
            displayWidth = 32768,
            picWidthInCtbs = 512
        )
        val tooWidePps = f.pps.copy(
            tileColumnWidthsInCtbs = listOf(512)
        )
        val tooWide = f.copy(sps = tooWideSps, pps = tooWidePps)
        val overflow = HevcOutputGridValidator.validate(
            HevcOutputGridDimensions(2, 1),
            listOf(
                HevcOutputCellPlacement(fullCell(tooWide, "wide-a"), 0, 0),
                HevcOutputCellPlacement(fullCell(tooWide, "wide-b"), 1, 0)
            )
        )
        assertFalse(overflow.compatible)
        assertTrue(overflow.reasons.any { it.contains("exceed supported HEVC bounds") })
    }

    @Test fun rejectsMissingParameterSetsAndAcceptsDifferentSourceParameterSetIds() {
        val f = fixture(tilesEnabled = false)
        val missing = HevcParameterSetCompatibility.compare(
            null, f.sps, f.pps, f.vps, f.sps, f.pps
        )
        assertFalse(missing.compatible)
        assertTrue(missing.reasons.any { it.contains("VPS is missing") })

        val reidentified = f.withDifferentParameterSetIds()
        val result = compare(f, reidentified)
        assertTrue(result.reasons.toString(), result.compatible)
    }

    private data class Fixture(
        val vps: HevcParameterSets.VpsMetadata,
        val sps: HevcParameterSets.SpsMetadata,
        val pps: HevcParameterSets.PpsMetadata
    ) {
        fun withDifferentParameterSetIds() = copy(
            vps = vps.copy(vpsId = (vps.vpsId + 1) % 16),
            sps = sps.copy(vpsId = (sps.vpsId + 1) % 16, spsId = (sps.spsId + 1) % 16),
            pps = pps.copy(ppsId = (pps.ppsId + 1) % 64, spsId = (pps.spsId + 1) % 16)
        )
    }

    private fun compare(first: Fixture, second: Fixture) =
        HevcParameterSetCompatibility.compare(
            first.vps, first.sps, first.pps, second.vps, second.sps, second.pps
        )

    private fun picture(f: Fixture, id: String) = HevcSourcePicture(
        feed = HevcSourceFeedIdentity(id),
        geometry = HevcSourcePictureGeometry.from(f.sps),
        parameterSets = HevcSourceParameterSets(f.vps, f.sps, f.pps)
    )

    private fun fullCell(f: Fixture, id: String): HevcSourceCellSelection {
        val sps = f.sps
        return HevcSourceCellSelection(
            picture = picture(f, id),
            leftCtb = 0,
            topCtb = 0,
            widthInCtbs = sps.picWidthInCtbs,
            heightInCtbs = sps.picHeightInCtbs
        )
    }

    private fun fixture(tilesEnabled: Boolean, deblockingOverride: Boolean = false): Fixture {
        val vps = HevcParameterSets.parseVps(nal(32, BitWriter().apply {
            write(1, 4); write(1, 1); write(1, 1); write(0, 6)
            write(0, 3); write(1, 1); write(0xffff, 16)
            write(0, 8 + 32 + 32 + 16 + 8) // profile-tier-level
            write(1, 1) // ordering info present
            writeUe(4); writeUe(2); writeUe(0)
            write(0, 6); writeUe(0) // layer sets
            write(0, 1) // no timing info
            write(0, 1) // no VPS extension
        }.finish()))
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits(tilesEnabled, deblockingOverride)), sps)
        return Fixture(vps, sps, pps)
    }

    private fun spsBits(): ByteArray = BitWriter().apply {
        write(1, 4); write(0, 3); write(1, 1)
        write(0, 8 + 32 + 4 + 44 + 8)
        writeUe(0); writeUe(1) // SPS id, 4:2:0
        writeUe(1280); writeUe(720); write(0, 1) // dimensions, no crop
        writeUe(0); writeUe(0); writeUe(4) // bit depth, POC width
        write(1, 1); writeUe(4); writeUe(2); writeUe(0) // DPB ordering
        writeUe(0); writeUe(3) // 64x64 CTB
        writeUe(0); writeUe(0); writeUe(0); writeUe(0) // transform block limits
        write(0, 1) // scaling list disabled
        write(0, 1) // AMP disabled
        write(0, 1) // SAO disabled
        write(0, 1) // PCM disabled
        writeUe(0) // no short-term RPS
        write(0, 1) // no long-term RPS
        write(0, 1) // temporal MVP disabled
        write(1, 1) // strong intra smoothing
        write(0, 1) // no VUI
        write(0, 1) // no SPS extension
    }.finish()

    private fun ppsBits(tilesEnabled: Boolean, deblockingOverride: Boolean): ByteArray = BitWriter().apply {
        writeUe(0); writeUe(0) // PPS and SPS ids
        write(0, 1); write(0, 1); write(0, 3) // dependent slices, output flag, extra bits
        write(0, 1); write(0, 1) // sign hiding and CABAC init
        writeUe(0); writeUe(0); writeSe(0) // default refs and init QP
        write(0, 1); write(0, 1); write(0, 1) // constrained intra, transform skip, CU QP delta
        writeSe(0); writeSe(0); write(0, 1) // chroma QP offsets, slice offsets flag
        write(0, 1); write(0, 1); write(0, 1) // weighted pred/bipred, transquant bypass
        write(if (tilesEnabled) 1 else 0, 1)
        write(0, 1) // entropy coding sync disabled
        if (tilesEnabled) {
            writeUe(1); writeUe(0) // two columns, one row
            write(1, 1); write(1, 1) // uniform tile spacing, loop filter across tiles
        }
        write(1, 1) // loop filter across slices
        write(if (deblockingOverride) 1 else 0, 1) // deblocking_filter_control_present_flag
        if (deblockingOverride) {
            write(1, 1) // pps_deblocking_filter_override_enabled_flag
            write(0, 1) // pps_deblocking_filter_disabled_flag
            writeSe(0); writeSe(0) // default beta/tc offsets
        }
        write(0, 1) // no PPS scaling-list data
        write(0, 1) // no reference-list modification
        writeUe(0) // parallel merge level
        write(0, 1) // no slice header extension
        write(0, 1) // no PPS extension
    }.finish()

    private fun nal(type: Int, rbsp: ByteArray): ByteArray =
        byteArrayOf((type shl 1).toByte(), 0x01) + rbsp

    private class BitWriter {
        private val bits = mutableListOf<Int>()
        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += (value ushr i) and 1
        }
        fun writeUe(value: Int) {
            val code = value.toLong() + 1L
            val width = 64 - java.lang.Long.numberOfLeadingZeros(code)
            repeat(width - 1) { bits += 0 }
            write(code.toInt(), width)
        }
        fun writeSe(value: Int) = writeUe(if (value <= 0) -2 * value else 2 * value - 1)
        fun finish(): ByteArray {
            bits += 1
            while (bits.size % 8 != 0) bits += 0
            return ByteArray(bits.size / 8) { index ->
                var value = 0
                for (bit in 0 until 8) value = (value shl 1) or bits[index * 8 + bit]
                value.toByte()
            }
        }
    }
}
