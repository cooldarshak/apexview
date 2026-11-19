package app.apexview.tiled

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import app.apexview.croppedHevcCodedSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class HevcParameterSetParserTest {
    @Test fun parsesVpsOrderingLayerSetsTimingAndHrdParameters() {
        val bits = BitWriter().apply {
            write(5, 4) // VPS id
            write(1, 1); write(1, 1) // base-layer internal and available
            write(0, 6) // one layer
            write(1, 3); write(1, 1) // two sub-layers, temporal nesting
            write(0xffff, 16)
            write(0, 8); write(0, 32); write(0, 32); write(0, 16); write(0, 8) // general PTL
            write(0, 1); write(0, 1) // sub-layer profile/level absent
            write(0, 14) // reserved_zero_2bits for remaining sub-layers
            write(1, 1) // ordering info present for all sub-layers
            repeat(2) {
                writeUe(4); writeUe(2); writeUe(0)
            }
            write(0, 6) // max layer ID
            writeUe(0) // one layer set (base layer set only)
            write(1, 1) // timing info present
            write(1, 32); write(25, 32)
            write(1, 1); writeUe(0) // POC proportional, one tick per POC
            writeUe(1) // one HRD parameter set
            writeUe(0) // layer_set_idx = 0
            // First hrd_parameters has common_info_present_flag inferred true.
            write(0, 1); write(0, 1) // no NAL or VCL HRD
            repeat(2) { // each sub-layer: fixed rate, duration, and CPB count
                write(1, 1); writeUe(0); writeUe(0)
            }
            write(0, 1) // no VPS extension
        }
        val vps = HevcParameterSets.parseVps(nal(32, bits.finish()))
        assertEquals(5, vps.vpsId)
        assertTrue(vps.baseLayerInternal)
        assertTrue(vps.baseLayerAvailable)
        assertEquals(1, vps.maxLayers)
        assertEquals(2, vps.maxSubLayers)
        assertTrue(vps.temporalIdNesting)
        assertTrue(vps.subLayerOrderingInfoPresent)
        assertEquals(listOf(5, 5), vps.maxDecPicBuffering)
        assertEquals(listOf(2, 2), vps.maxNumReorderPics)
        assertEquals(0, vps.maxLayerId)
        assertEquals(1, vps.numLayerSets)
        assertTrue(vps.timingInfoPresent)
        assertEquals(1L, vps.numUnitsInTick)
        assertEquals(25L, vps.timeScale)
        assertEquals(true, vps.pocProportionalToTiming)
        assertEquals(0, vps.numTicksPocDiffOneMinus1)
        assertEquals(1, vps.hrdParameters.size)
        assertFalse(vps.hrdParameters.single().commonInfo.nalHrdPresent)
        assertFalse(vps.extensionPresent)
    }

    @Test fun extractsSpsDimensionsPocAndCtbGeometry() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        assertEquals(1, sps.vpsId)
        assertEquals(0, sps.spsId)
        assertEquals(1, sps.maxSubLayers)
        assertEquals(1, sps.chromaFormatIdc)
        assertEquals(1920, sps.width)
        assertEquals(1080, sps.height)
        assertEquals(8, sps.log2MaxPicOrderCntLsb)
        assertEquals(64, sps.ctbSize)
        assertEquals(30, sps.picWidthInCtbs)
        assertEquals(17, sps.picHeightInCtbs)
        assertEquals(8, sps.bitDepthLuma)
        assertEquals(8, sps.bitDepthChroma)
        assertEquals(1920, sps.displayWidth)
        assertEquals(1080, sps.displayHeight)
        assertEquals(0, sps.profileTierLevel.profileIdc)
        assertEquals(0, sps.profileTierLevel.levelIdc)
        assertEquals(listOf(5), sps.maxDecPicBuffering)
        assertEquals(listOf(2), sps.maxNumReorderPics)
        assertFalse(sps.vuiParametersPresent)
        assertEquals(null, sps.vuiMetadata)
        assertEquals(false, sps.spsExtensionPresent)
    }

    @Test fun parsesVuiAspectColourAndTimingMetadataBeforeSpsExtensionFlag() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits(withVui = true)))
        val vui = sps.vuiMetadata!!
        assertTrue(sps.vuiParametersPresent)
        assertEquals(5, vui.videoFormat)
        assertEquals(true, vui.videoFullRange)
        assertEquals(1, vui.colourPrimaries)
        assertEquals(2, vui.transferCharacteristics)
        assertEquals(3, vui.matrixCoefficients)
        assertTrue(vui.timingInfoPresent)
        assertEquals(1L, vui.numUnitsInTick)
        assertEquals(25L, vui.timeScale)
        assertFalse(vui.hrdParametersPresent)
        assertFalse(vui.bitstreamRestrictionPresent)
        assertFalse(sps.spsExtensionPresent)
    }

    @Test fun appliesChromaAwareConformanceWindowAndCapturesBitDepth() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits(
            cropLeft = 1, cropRight = 2, cropTop = 1, cropBottom = 2,
            bitDepthLumaMinus8 = 2, bitDepthChromaMinus8 = 1
        )))
        assertEquals(1914, sps.displayWidth)
        assertEquals(1074, sps.displayHeight)
        assertEquals(10, sps.bitDepthLuma)
        assertEquals(9, sps.bitDepthChroma)
        assertEquals(1, sps.conformanceWindowLeft)
        assertEquals(2, sps.conformanceWindowRight)
    }

    @Test fun usesTheFullDecoderSizeForA720pCrop() {
        val csd = byteArrayOf(0, 0, 0, 1) + nal(33, spsBits(cropRight = 320, cropBottom = 184, codedHeight = 1088))
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(1280).setHeight(720)
            .setInitializationData(listOf(csd)).build()
        assertEquals(1920 to 1088, croppedHevcCodedSize(format))
        assertEquals(null, croppedHevcCodedSize(format.buildUpon().setWidth(1920).setHeight(1080).build()))
    }

    @Test fun extractsUniformPpsTileGridAndCalculatesCtbPartitions() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits(uniform = true)), sps)
        assertEquals(0, pps.ppsId)
        assertEquals(0, pps.spsId)
        assertTrue(pps.dependentSliceSegmentsEnabled)
        assertTrue(pps.tilesEnabled)
        assertTrue(pps.uniformTileSpacing == true)
        assertEquals(2, pps.numTileColumns)
        assertEquals(2, pps.numTileRows)
        assertEquals(listOf(15, 15), pps.tileColumnWidthsInCtbs)
        assertEquals(listOf(8, 9), pps.tileRowHeightsInCtbs)
        assertFalse(pps.weightedPredictionEnabled)
        assertFalse(pps.weightedBipredictionEnabled)
        assertTrue(pps.loopFilterAcrossSlicesEnabled)
        assertTrue(pps.deblockingFilterControlPresent)
        assertFalse(pps.deblockingFilterOverrideEnabled!!)
        assertFalse(pps.deblockingFilterDisabled!!)
        assertEquals(1, pps.betaOffsetDiv2)
        assertEquals(-1, pps.tcOffsetDiv2)
        assertTrue(pps.listsModificationPresent)
        assertEquals(2, pps.log2ParallelMergeLevelMinus2)
        assertTrue(pps.sliceSegmentHeaderExtensionPresent)
        assertFalse(pps.extensionPresent)
    }

    @Test fun parsesPpsWithoutTilesAndStillConsumesFollowingSyntax() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits(uniform = true, tiles = false)), sps)
        assertFalse(pps.tilesEnabled)
        assertEquals(1, pps.numTileColumns)
        assertEquals(1, pps.numTileRows)
        assertEquals(listOf(30), pps.tileColumnWidthsInCtbs)
        assertEquals(listOf(17), pps.tileRowHeightsInCtbs)
        assertTrue(pps.loopFilterAcrossSlicesEnabled)
        assertEquals(2, pps.log2ParallelMergeLevelMinus2)
    }

    @Test fun extractsExplicitPpsTileWidthsAndHeights() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits(uniform = false)), sps)
        assertFalse(pps.uniformTileSpacing == true)
        assertEquals(listOf(15, 15), pps.tileColumnWidthsInCtbs)
        assertEquals(listOf(8, 9), pps.tileRowHeightsInCtbs)
    }

    @Test fun derivesPredictedSpsShortTermRpsFromEarlierSet() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBitsWithPredictedRps()))
        val predicted = sps.shortTermRefPicSets[1]

        assertTrue(predicted.negative.isEmpty())
        assertEquals(listOf(1, 3), predicted.positive.map { it.deltaPoc })
        assertEquals(listOf(true, false), predicted.positive.map { it.usedByCurrent })
    }

    @Test fun derivesInlinePredictedRpsUsingDeltaIndexMinusOne() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBitsWithPredictedRps()))
        val bits = BitWriter().apply {
            write(1, 1) // inline inter_ref_pic_set_prediction_flag
            writeUe(1) // delta_idx_minus1: reference SPS set 0, not set 1
            write(0, 1) // delta_rps_sign: +1
            writeUe(0) // abs_delta_rps_minus1: 1
            write(0, 1); write(1, 1) // shifted -1 -> 0; keep in RPS but not used
            write(0, 1); write(1, 1) // shifted +2 -> +3; keep in RPS but not used
            write(1, 1) // +1 delta itself; used (use_delta inferred)
        }
        val parsed = HevcParameterSets.parseShortTermRefPicSet(
            HevcBitReader(bits.finish()),
            index = sps.shortTermRefPicSets.size,
            totalSets = sps.shortTermRefPicSets.size,
            previous = sps.shortTermRefPicSets,
        )

        assertTrue(parsed.negative.isEmpty())
        assertEquals(listOf(1, 3), parsed.positive.map { it.deltaPoc })
        assertEquals(listOf(true, false), parsed.positive.map { it.usedByCurrent })
    }

    @Test fun acceptsPpsInitialQpAllowedByHigherBitDepthSps() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits(bitDepthLumaMinus8 = 2, bitDepthChromaMinus8 = 2)))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits(uniform = true, initQpMinus26 = -30)), sps)
        assertEquals(10, sps.bitDepthLuma)
        assertEquals(-30, pps.initQpMinus26)
    }

    @Test fun rejectsPpsInitialQpOutsideReferencedSpsBitDepthRange() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        assertThrows(IllegalArgumentException::class.java) {
            HevcParameterSets.parsePps(nal(34, ppsBits(uniform = true, initQpMinus26 = -27)), sps)
        }
    }

    @Test fun rejectsPpsThatReferencesDifferentSps() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val wrongPps = HevcParameterSets.parsePps(nal(34, ppsBits(uniform = true, spsId = 1)))
        assertThrows(IllegalArgumentException::class.java) {
            HevcParameterSets.parsePps(nal(34, ppsBits(uniform = true, spsId = 1)), sps)
        }
        assertEquals(1, wrongPps.spsId)
    }

    @Test fun rejectsWrongNalTypeAndTruncatedVps() {
        assertThrows(IllegalArgumentException::class.java) {
            HevcParameterSets.parseSps(nal(34, spsBits()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HevcParameterSets.parseVps(byteArrayOf(0x40, 0x01))
        }
    }

    private fun spsBits(
        cropLeft: Int = 0,
        cropRight: Int = 0,
        cropTop: Int = 0,
        cropBottom: Int = 0,
        bitDepthLumaMinus8: Int = 0,
        bitDepthChromaMinus8: Int = 0,
        withVui: Boolean = false,
        codedHeight: Int = 1080,
    ): ByteArray = BitWriter().apply {
        write(1, 4) // sps_video_parameter_set_id
        write(0, 3) // sps_max_sub_layers_minus1
        write(1, 1) // temporal nesting
        write(0, 8 + 32 + 4 + 44 + 8) // general profile-tier-level
        writeUe(0) // sps id
        writeUe(1) // 4:2:0 chroma
        writeUe(1920)
        writeUe(codedHeight)
        val hasCrop = cropLeft + cropRight + cropTop + cropBottom > 0
        write(if (hasCrop) 1 else 0, 1)
        if (hasCrop) {
            writeUe(cropLeft); writeUe(cropRight); writeUe(cropTop); writeUe(cropBottom)
        }
        writeUe(bitDepthLumaMinus8); writeUe(bitDepthChromaMinus8) // bit depth
        writeUe(4) // log2_max_pic_order_cnt_lsb_minus4 = 4
        write(1, 1) // sub-layer ordering info for all layers
        writeUe(4); writeUe(2); writeUe(0)
        writeUe(0) // log2_min_luma_coding_block_size_minus3
        writeUe(3) // log2_diff_max_min_luma_coding_block_size -> 64x64 CTB
        writeUe(0); writeUe(0) // transform block size limits
        writeUe(0); writeUe(0) // transform hierarchy depths
        write(0, 1) // scaling_list_enabled_flag
        write(0, 1) // amp_enabled_flag
        write(1, 1) // sample_adaptive_offset_enabled_flag
        write(0, 1) // pcm_enabled_flag
        writeUe(0) // no SPS short-term reference sets
        write(0, 1) // long_term_ref_pics_present_flag
        write(1, 1) // sps_temporal_mvp_enabled_flag
        write(1, 1) // strong_intra_smoothing_enabled_flag
        write(if (withVui) 1 else 0, 1) // vui_parameters_present_flag
        if (withVui) {
            write(0, 1) // aspect_ratio_info_present_flag
            write(0, 1) // overscan_info_present_flag
            write(1, 1) // video_signal_type_present_flag
            write(5, 3) // video_format
            write(1, 1) // video_full_range_flag
            write(1, 1) // colour_description_present_flag
            write(1, 8); write(2, 8); write(3, 8) // colour primaries/transfer/matrix
            write(0, 1) // chroma_loc_info_present_flag
            write(0, 1); write(0, 1); write(0, 1) // neutral chroma, field sequence, frame field
            write(0, 1) // default display window absent
            write(1, 1) // vui_timing_info_present_flag
            write(1, 32); write(25, 32) // num_units_in_tick, time_scale
            write(0, 1) // poc_proportional_to_timing_flag
            write(0, 1) // vui_hrd_parameters_present_flag
            write(0, 1) // bitstream_restriction_flag
        }
        write(0, 1) // sps_extension_present_flag
    }.finish()

    private fun spsBitsWithPredictedRps(): ByteArray = BitWriter().apply {
        write(1, 4); write(0, 3); write(1, 1)
        write(0, 8 + 32 + 4 + 44 + 8)
        writeUe(0); writeUe(1)
        writeUe(1920); writeUe(1080); write(0, 1)
        writeUe(0); writeUe(0); writeUe(4)
        write(1, 1); writeUe(4); writeUe(2); writeUe(0)
        writeUe(0); writeUe(3)
        writeUe(0); writeUe(0); writeUe(0); writeUe(0)
        write(0, 1); write(0, 1); write(1, 1); write(0, 1)
        writeUe(2) // two short-term reference sets
        // Set 0: explicit RPS with -1 and +2.
        writeUe(1); writeUe(1)
        writeUe(0); write(1, 1)
        writeUe(1); write(1, 1)
        // Set 1: predicted from set 0 with deltaRps = +1.
        write(1, 1) // inter_ref_pic_set_prediction_flag
        write(0, 1); writeUe(0) // positive deltaRps of 1
        write(0, 1); write(1, 1) // source -1 shifts to 0; use_delta true, not used
        write(0, 1); write(1, 1) // source +2 shifts to +3; use_delta true, not used
        write(1, 1) // deltaRps +1 is used; use_delta inferred
        write(0, 1) // no long-term references
        write(1, 1); write(1, 1) // temporal MVP and strong smoothing
        write(0, 1) // no VUI
        write(0, 1) // no SPS extension
    }.finish()

    private fun ppsBits(
        uniform: Boolean,
        spsId: Int = 0,
        tiles: Boolean = true,
        initQpMinus26: Int = 0
    ): ByteArray = BitWriter().apply {
        writeUe(0) // PPS id
        writeUe(spsId)
        write(1, 1) // dependent slice segments
        write(0, 1) // output flag absent
        write(2, 3) // extra slice-header bits
        write(0, 1); write(0, 1) // sign hiding, CABAC init
        writeUe(0); writeUe(0)
        writeSe(initQpMinus26) // init QP
        write(0, 1); write(0, 1); write(0, 1) // constrained intra, transform skip, CU QP delta
        writeSe(0); writeSe(0) // chroma QP offsets
        write(0, 1) // slice chroma QP offsets
        write(0, 1); write(0, 1); write(0, 1) // weighted pred/bipred/transquant
        write(if (tiles) 1 else 0, 1) // tiles enabled
        write(0, 1) // entropy coding sync disabled
        if (tiles) {
            writeUe(1); writeUe(1) // 2 columns, 2 rows
            write(if (uniform) 1 else 0, 1)
            if (!uniform) {
                writeUe(14) // first tile column is 15 CTBs
                writeUe(7)  // first tile row is 8 CTBs
            }
            write(1, 1) // loop filter across tiles
        }
        write(1, 1) // loop filter across slices
        write(1, 1) // deblocking filter control present
        write(0, 1) // deblocking override disabled
        write(0, 1) // deblocking filter enabled
        writeSe(1); writeSe(-1) // beta/tc offsets
        write(0, 1) // no PPS scaling list data
        write(1, 1) // lists modification present
        writeUe(2) // log2 parallel merge level minus 2
        write(1, 1) // slice segment header extension present
        write(0, 1) // no PPS extension
    }.finish()

    private fun nal(type: Int, rbsp: ByteArray): ByteArray =
        byteArrayOf((type shl 1).toByte(), 0x01) + rbspToEbsp(rbsp)

    /** Encodes RBSP as EBSP for tests; VPS/SPS syntax often contains 00 00 00/01/02/03. */
    private fun rbspToEbsp(rbsp: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var zeroCount = 0
        rbsp.forEach { byte ->
            val value = byte.toInt() and 0xff
            if (zeroCount >= 2 && value <= 3) {
                out.write(3)
                zeroCount = 0
            }
            out.write(value)
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitWriter {
        private val bits = mutableListOf<Int>()
        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += (value ushr i) and 1
        }
        fun writeUe(value: Int) {
            require(value >= 0)
            val code = value.toLong() + 1L
            val width = 64 - java.lang.Long.numberOfLeadingZeros(code)
            repeat(width - 1) { bits += 0 }
            write(code.toInt(), width)
        }
        fun writeSe(value: Int) = writeUe(if (value <= 0) -2 * value else 2 * value - 1)
        fun finish(): ByteArray {
            bits += 1 // rbsp_stop_one_bit
            while (bits.size % 8 != 0) bits += 0
            return ByteArray(bits.size / 8) { index ->
                var value = 0
                for (bit in 0 until 8) value = (value shl 1) or bits[index * 8 + bit]
                value.toByte()
            }
        }
    }
}
