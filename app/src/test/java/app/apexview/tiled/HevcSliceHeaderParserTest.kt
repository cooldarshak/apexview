package app.apexview.tiled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcSliceHeaderParserTest {
    @Test
    fun rejectsReservedVclNalTypesAndTemporalLayersOutsideSpsLimit() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits()), sps)
        val reservedType = nal(31, pSliceBits())
        try {
            HevcSliceHeaderParser.parse(reservedType, sps, pps)
            throw AssertionError("Reserved VCL NAL type must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Reserved or unsupported"))
        }

        val excessiveTemporalLayer = nal(1, pSliceBits(), temporalIdPlus1 = 2)
        try {
            HevcSliceHeaderParser.parse(excessiveTemporalLayer, sps, pps)
            throw AssertionError("Temporal layer above SPS limit must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("temporal_id_plus1"))
        }
    }

    @Test
    fun parsesSlicePocAndUsedShortTermReferenceDeltas() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits()), sps)
        val slice = HevcSliceHeaderParser.parse(nal(1, pSliceBits()), sps, pps)

        assertTrue(slice.firstSliceSegmentInPicture)
        assertEquals(1, slice.sliceType)
        assertEquals(42, slice.picOrderCntLsb)
        assertFalse(slice.shortTermRpsFromSps!!)
        assertEquals(listOf(-1, -4), slice.references.map { it.deltaPoc })
        assertEquals(listOf(true, false), slice.references.map { it.usedByCurrent })
        assertTrue(slice.temporalMvpEnabled!!)
        assertTrue(slice.sampleAdaptiveOffsetLuma!!)
        assertTrue(slice.sampleAdaptiveOffsetChroma!!)
        assertTrue(slice.bitsConsumed > 0)
    }

    @Test
    fun resolvesShortTermReferenceSetSelectedFromSps() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits(includeSpsRps = true)))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits()), sps)
        val bits = BitWriter().apply {
            write(1, 1) // first slice
            writeUe(0) // PPS id
            write(0, 2) // reserved header bits
            writeUe(1) // P slice
            write(43, 8)
            write(1, 1) // use SPS short-term RPS; only one, so no index bits
            write(1, 1) // temporal MVP
            write(1, 1); write(0, 1) // SAO luma/chroma
            write(0, 1) // num_ref_idx_active_override_flag
            writeUe(0) // five_minus_max_num_merge_cand
            writeSe(0) // slice_qp_delta
            write(1, 1) // slice_loop_filter_across_slices_enabled_flag
            writeUe(0) // num_entry_point_offsets
        }.finish()
        val slice = HevcSliceHeaderParser.parse(nal(1, bits), sps, pps)

        assertTrue(slice.shortTermRpsFromSps!!)
        assertEquals(0, slice.shortTermRpsIndex)
        assertEquals(listOf(-1, 1), slice.references.map { it.deltaPoc })
        assertEquals(listOf(true, true), slice.references.map { it.usedByCurrent })
    }

    @Test
    fun parsesIdrAndInfersPocZeroWithoutReferenceSets() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits()), sps)
        val bits = BitWriter().apply {
            write(1, 1) // first slice
            write(1, 1) // IRAP no_output_of_prior_pics_flag
            writeUe(0) // PPS id
            write(0, 2) // reserved header bits
            writeUe(2) // I slice
            write(1, 1); write(1, 1) // SAO luma/chroma are present for IDR
            writeSe(0) // slice_qp_delta
            write(1, 1) // slice_loop_filter_across_slices_enabled_flag
            writeUe(0) // num_entry_point_offsets
        }.finish()
        val slice = HevcSliceHeaderParser.parse(nal(20, bits), sps, pps)

        assertTrue(slice.noOutputOfPriorPictures!!)
        assertNull(slice.picOrderCntLsb) // inferred as zero, not signalled
        assertNull(slice.shortTermRpsFromSps)
        assertTrue(slice.references.isEmpty())
        assertFalse(slice.temporalMvpEnabled!!)
        assertTrue(slice.sampleAdaptiveOffsetLuma!!)
        assertTrue(slice.sampleAdaptiveOffsetChroma!!)
    }

    @Test
    fun parsesDependentSliceAddressWithoutInventingPictureIdentity() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits()), sps)
        val bits = BitWriter().apply {
            write(0, 1) // not first slice in picture
            writeUe(0) // PPS id
            write(1, 1) // dependent slice segment
            write(5, 9) // address in the 510-CTB picture
        }.finish()
        val slice = HevcSliceHeaderParser.parse(nal(1, bits), sps, pps)

        assertTrue(slice.dependentSliceSegment)
        assertEquals(5, slice.sliceSegmentAddress)
        assertNull(slice.picOrderCntLsb)
        assertNull(slice.sliceType)
    }

    @Test
    fun resolvesInterPredictedInlineRpsInsideSliceHeader() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits(includeSpsRps = true)))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits()), sps)
        val bits = BitWriter().apply {
            write(1, 1); writeUe(0); write(0, 2); writeUe(1)
            write(44, 8)
            write(0, 1) // inline RPS, not selected from SPS
            write(1, 1) // inter-RPS prediction
            writeUe(0) // delta_idx_minus1: use the sole SPS RPS
            write(0, 1); writeUe(0) // positive deltaRps = +1
            write(0, 1); write(1, 1) // shifted -1 becomes zero; retain but do not use
            write(1, 1) // shifted +1 -> +2; used, use_delta inferred
            write(1, 1) // deltaRps +1; used, use_delta inferred
            write(1, 1) // temporal MVP
            write(1, 1); write(1, 1) // SAO luma/chroma
            write(0, 1); writeUe(0); writeSe(0); write(1, 1); writeUe(0)
        }.finish()
        val slice = HevcSliceHeaderParser.parse(nal(1, bits), sps, pps)
        assertFalse(slice.shortTermRpsFromSps!!)
        assertEquals(listOf(1, 2), slice.references.map { it.deltaPoc })
        assertEquals(listOf(true, true), slice.references.map { it.usedByCurrent })
    }

    @Test
    fun parsesSliceLevelDeblockingOverrideAndBothFilterOffsets() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits(deblockingOverride = true)), sps)
        assertTrue(pps.deblockingFilterControlPresent)
        assertTrue(pps.deblockingFilterOverrideEnabled!!)

        val bits = BitWriter().apply {
            write(1, 1); writeUe(0); write(0, 2); writeUe(1)
            write(42, 8)
            write(0, 1); writeUe(2); writeUe(0)
            writeUe(0); write(1, 1); writeUe(2); write(0, 1)
            write(1, 1) // temporal MVP
            write(1, 1); write(1, 1) // SAO luma/chroma
            write(0, 1) // active reference override false
            writeUe(0) // five_minus_max_num_merge_cand
            writeSe(0) // slice_qp_delta
            write(1, 1) // slice_deblocking_filter_override_flag
            write(0, 1) // slice_deblocking_filter_disabled_flag
            writeSe(2) // slice_beta_offset_div2
            writeSe(-1) // slice_tc_offset_div2
            write(1, 1) // slice_loop_filter_across_slices_enabled_flag
            writeUe(0) // num_entry_point_offsets
        }.finish()
        val slice = HevcSliceHeaderParser.parse(nal(1, bits), sps, pps)

        assertTrue(slice.deblockingFilterOverride!!)
        assertFalse(slice.deblockingFilterDisabled!!)
        assertEquals(2, slice.betaOffsetDiv2)
        assertEquals(-1, slice.tcOffsetDiv2)
        assertTrue(slice.loopFilterAcrossSlices!!)
    }

    @Test
    fun parsesWeightedPredictionTableAndActiveReferenceCounts() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(nal(34, ppsBits(weightedPred = true)), sps)
        val slice = HevcSliceHeaderParser.parse(nal(1, weightedPSliceBits()), sps, pps)

        assertEquals(1, slice.numPicTotalCurrent)
        assertEquals(1, slice.numRefIdxL0Active)
        assertNull(slice.numRefIdxL1Active)
        val table = slice.predictionWeightTable!!
        assertEquals(1, table.lumaLog2WeightDenom)
        assertEquals(1, table.chromaLog2WeightDenom)
        assertEquals(HevcSliceHeaderParser.PredictionWeightComponent(2, -1), table.list0.single().luma)
        assertEquals(
            listOf(HevcSliceHeaderParser.PredictionWeightComponent(1, -2), HevcSliceHeaderParser.PredictionWeightComponent(-1, 3)),
            table.list0.single().chroma
        )
        assertTrue(table.list1.isEmpty())
        assertEquals(0, slice.sliceQpDelta)
    }

    @Test
    fun parsesReferenceListModificationAndCollocatedReferenceIndex() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(
            nal(34, ppsBits(defaultL0Minus1 = 1, listsModification = true)), sps
        )
        val slice = HevcSliceHeaderParser.parse(nal(1, twoReferencePSliceBits()), sps, pps)

        assertEquals(2, slice.numPicTotalCurrent)
        assertEquals(2, slice.numRefIdxL0Active)
        assertEquals(listOf(1, 0), slice.list0Modification)
        assertTrue(slice.list1Modification.isEmpty())
        assertEquals(true, slice.collocatedFromL0)
        assertEquals(1, slice.collocatedReferenceIndex)
    }

    @Test
    fun parsesBsliceL1PredictionListMvdFlagAndCollocatedL1Selection() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(
            nal(34, ppsBits(
                weightedBipred = true,
                defaultL0Minus1 = 1,
                defaultL1Minus1 = 1,
                listsModification = true
            )), sps
        )
        val slice = HevcSliceHeaderParser.parse(nal(1, weightedBSliceBits()), sps, pps)

        assertEquals(0, slice.sliceType)
        assertEquals(2, slice.numPicTotalCurrent)
        assertEquals(2, slice.numRefIdxL0Active)
        assertEquals(2, slice.numRefIdxL1Active)
        assertEquals(listOf(1, 0), slice.list0Modification)
        assertEquals(listOf(0, 1), slice.list1Modification)
        assertTrue(slice.mvdL1Zero!!)
        assertFalse(slice.collocatedFromL0!!)
        assertEquals(1, slice.collocatedReferenceIndex)

        val table = slice.predictionWeightTable!!
        assertEquals(2, table.list0.size)
        assertEquals(2, table.list1.size)
        assertNull(table.list0[0].luma)
        assertEquals(
            HevcSliceHeaderParser.PredictionWeightComponent(1, 2),
            table.list1[0].luma
        )
        assertEquals(
            listOf(
                HevcSliceHeaderParser.PredictionWeightComponent(0, 0),
                HevcSliceHeaderParser.PredictionWeightComponent(0, 0)
            ),
            table.list1[0].chroma
        )
    }

    @Test
    fun parsesPerSliceDeblockingOverridesEntryPointsAndHeaderExtensionLength() {
        val sps = HevcParameterSets.parseSps(nal(33, spsBits()))
        val pps = HevcParameterSets.parsePps(
            nal(34, ppsBits(deblockingOverride = true, sliceHeaderExtension = true)), sps
        )
        val slice = HevcSliceHeaderParser.parse(nal(1, extendedPSliceBits()), sps, pps)

        assertTrue(slice.deblockingFilterOverride!!)
        assertFalse(slice.deblockingFilterDisabled!!)
        assertEquals(2, slice.betaOffsetDiv2)
        assertEquals(-1, slice.tcOffsetDiv2)
        assertTrue(slice.loopFilterAcrossSlices!!)
        assertEquals(listOf(2L, 4L), slice.entryPointOffsets)
        assertEquals(2, slice.sliceHeaderExtensionLength)
        assertTrue(slice.bitsConsumed > 0)
    }

    @Test
    fun derivesPocAcrossLsbWrap() {
        val tracker = HevcPocTracker()
        assertEquals(0L, tracker.derive(20, 1, 0, 8))
        for (poc in 1..255) {
            assertEquals(poc.toLong(), tracker.derive(1, 1, poc, 8))
        }
        assertEquals(256L, tracker.derive(1, 1, 0, 8))
        assertEquals(257L, tracker.derive(1, 1, 1, 8))
    }

    @Test
    fun resetsPocMsbForBlaInsteadOfWrappingAgainstZero() {
        val tracker = HevcPocTracker()
        assertEquals(10L, tracker.derive(1, 1, 10, 8))
        assertEquals(240L, tracker.derive(16, 1, 240, 8))
        assertEquals(241L, tracker.derive(1, 1, 241, 8))
    }

    @Test
    fun excludesAllSlnrRadlAndRaslNalTypesFromPrevTid0Anchor() {
        val excludedTypes = listOf(0, 2, 4, 6, 7, 8, 9, 10, 12, 14)
        excludedTypes.forEach { excludedType ->
            val tracker = HevcPocTracker()
            assertEquals(8L, tracker.derive(1, 1, 8, 4))
            tracker.derive(excludedType, 1, 1, 4)
            assertEquals(
                "NAL type $excludedType must not replace prevTid0Pic",
                16L,
                tracker.derive(1, 1, 0, 4)
            )
        }
    }

    @Test
    fun reservedReferenceVclTypesCanUpdatePrevTid0Anchor() {
        listOf(11, 13, 15).forEach { referenceType ->
            val tracker = HevcPocTracker()
            assertEquals(8L, tracker.derive(1, 1, 8, 4))
            tracker.derive(referenceType, 1, 1, 4)
            assertEquals(
                "Reference NAL type $referenceType should become prevTid0Pic",
                0L,
                tracker.derive(1, 1, 0, 4)
            )
        }
    }

    @Test
    fun nonZeroTemporalLayerDoesNotReplacePrevTid0Anchor() {
        val tracker = HevcPocTracker()
        assertEquals(8L, tracker.derive(1, 1, 8, 4))
        tracker.derive(1, 2, 1, 4)
        assertEquals(16L, tracker.derive(1, 1, 0, 4))
    }

    @Test
    fun appliesCraNoRaslOutputFlagOnlyWhenSequenceContextRequiresIt() {
        val atRandomAccessPoint = HevcPocTracker()
        assertEquals(240L, atRandomAccessPoint.derive(21, 1, 240, 8, noRaslOutputFlag = true))

        val inMiddleOfSequence = HevcPocTracker()
        assertEquals(10L, inMiddleOfSequence.derive(1, 1, 10, 8))
        assertEquals(-16L, inMiddleOfSequence.derive(21, 1, 240, 8, noRaslOutputFlag = false))
    }

    @Test
    fun doesNotUseTrailNonReferencePicturesAsPocAnchors() {
        val tracker = HevcPocTracker()
        assertEquals(100L, tracker.derive(1, 1, 100, 8))
        assertEquals(-6L, tracker.derive(0, 1, 250, 8)) // wraps against the prior reference anchor
        assertEquals(101L, tracker.derive(1, 1, 101, 8))
    }

    private fun spsBits(includeSpsRps: Boolean = false): ByteArray = BitWriter().apply {
        write(1, 4); write(0, 3); write(1, 1)
        write(0, 8 + 32 + 4 + 44 + 8)
        writeUe(0); writeUe(1)
        writeUe(1920); writeUe(1080); write(0, 1)
        writeUe(0); writeUe(0); writeUe(4)
        write(1, 1); writeUe(4); writeUe(2); writeUe(0)
        writeUe(0); writeUe(3)
        writeUe(0); writeUe(0); writeUe(0); writeUe(0)
        write(0, 1) // scaling lists disabled
        write(0, 1) // AMP disabled
        write(1, 1) // SAO enabled
        write(0, 1) // PCM disabled
        writeUe(if (includeSpsRps) 1 else 0)
        if (includeSpsRps) {
            writeUe(1); writeUe(1) // one negative and one positive reference
            writeUe(0); write(1, 1) // delta -1, used
            writeUe(0); write(1, 1) // delta +1, used
        }
        write(0, 1) // long-term refs absent
        write(1, 1) // temporal MVP enabled
        write(1, 1) // strong smoothing
        write(0, 1) // no VUI
        write(0, 1) // no SPS extension
    }.finish()

    private fun ppsBits(
        weightedPred: Boolean = false,
        weightedBipred: Boolean = false,
        defaultL0Minus1: Int = 0,
        defaultL1Minus1: Int = 0,
        listsModification: Boolean = false,
        deblockingOverride: Boolean = false,
        sliceHeaderExtension: Boolean = false
    ): ByteArray = BitWriter().apply {
        writeUe(0); writeUe(0)
        write(1, 1); write(0, 1); write(2, 3)
        write(0, 1); write(0, 1)
        writeUe(defaultL0Minus1); writeUe(defaultL1Minus1)
        writeSe(0)
        write(0, 1); write(0, 1); write(0, 1)
        writeSe(0); writeSe(0)
        write(0, 1) // slice chroma QP offsets absent
        write(if (weightedPred) 1 else 0, 1)
        write(if (weightedBipred) 1 else 0, 1)
        write(0, 1)
        write(1, 1); write(0, 1)
        writeUe(1); writeUe(1); write(1, 1)
        write(1, 1) // loop filter across tiles
        write(1, 1) // loop filter across slices
        write(if (deblockingOverride) 1 else 0, 1)
        if (deblockingOverride) {
            write(1, 1) // deblocking_filter_override_enabled_flag
            write(0, 1) // pps_deblocking_filter_disabled_flag
            writeSe(0); writeSe(0)
        }
        write(0, 1) // no PPS scaling-list data
        write(if (listsModification) 1 else 0, 1)
        writeUe(0) // parallel merge level
        write(if (sliceHeaderExtension) 1 else 0, 1)
        write(0, 1) // no PPS extension
    }.finish()

    private fun pSliceBits(): ByteArray = BitWriter().apply {
        write(1, 1); writeUe(0); write(0, 2); writeUe(1)
        write(42, 8)
        write(0, 1) // short_term_ref_pic_set_sps_flag, present even with zero SPS sets
        writeUe(2); writeUe(0)
        writeUe(0); write(1, 1)
        writeUe(2); write(0, 1)
        write(1, 1) // temporal MVP enabled
        write(1, 1); write(1, 1) // SAO luma/chroma
        write(0, 1) // num_ref_idx_active_override_flag
        writeUe(0) // five_minus_max_num_merge_cand
        writeSe(0) // slice_qp_delta
        write(1, 1) // slice_loop_filter_across_slices_enabled_flag
        writeUe(0) // num_entry_point_offsets
    }.finish()

    private fun weightedPSliceBits(): ByteArray = BitWriter().apply {
        write(1, 1); writeUe(0); write(0, 2); writeUe(1)
        write(42, 8)
        write(0, 1); writeUe(2); writeUe(0)
        writeUe(0); write(1, 1); writeUe(2); write(0, 1)
        write(1, 1) // temporal MVP
        write(1, 1); write(1, 1) // SAO
        write(0, 1) // active reference override false
        writeUe(1) // luma_log2_weight_denom
        writeSe(0) // delta_chroma_log2_weight_denom
        write(1, 1) // luma_weight_l0_flag
        write(1, 1) // chroma_weight_l0_flag
        writeSe(2); writeSe(-1) // luma weight/offset
        writeSe(1); writeSe(-2) // Cb weight/offset
        writeSe(-1); writeSe(3) // Cr weight/offset
        writeUe(0) // five_minus_max_num_merge_cand
        writeSe(0) // slice_qp_delta
        write(1, 1) // loop filter across slices
        writeUe(0) // no tile entry points
    }.finish()

    private fun twoReferencePSliceBits(): ByteArray = BitWriter().apply {
        write(1, 1); writeUe(0); write(0, 2); writeUe(1)
        write(42, 8)
        write(0, 1); writeUe(2); writeUe(0)
        writeUe(0); write(1, 1); writeUe(1); write(1, 1)
        write(1, 1) // temporal MVP
        write(1, 1); write(1, 1) // SAO
        write(0, 1) // active reference override false (PPS default is two)
        write(1, 1) // ref_pic_list_modification_flag_l0
        write(1, 1); write(0, 1) // list_entry_l0[0..1]
        writeUe(1) // collocated_ref_idx
        writeUe(0) // five_minus_max_num_merge_cand
        writeSe(0) // slice_qp_delta
        write(1, 1) // loop filter across slices
        writeUe(0) // no tile entry points
    }.finish()

    private fun weightedBSliceBits(): ByteArray = BitWriter().apply {
        write(1, 1); writeUe(0); write(0, 2); writeUe(0) // first B slice, PPS, extra bits, B slice type
        write(42, 8)
        write(0, 1) // inline short-term RPS
        writeUe(1); writeUe(1) // one negative and one positive reference
        writeUe(0); write(1, 1) // -1, used by current
        writeUe(0); write(1, 1) // +1, used by current
        write(1, 1) // temporal MVP
        write(1, 1); write(1, 1) // SAO luma/chroma
        write(1, 1) // active reference override
        writeUe(1); writeUe(1) // two L0 and two L1 entries
        write(1, 1) // ref_pic_list_modification_flag_l0
        write(1, 1); write(0, 1) // L0 entries [1, 0]
        write(1, 1) // ref_pic_list_modification_flag_l1
        write(0, 1); write(1, 1) // L1 entries [0, 1]
        write(1, 1) // mvd_l1_zero_flag
        write(0, 1) // collocated_from_l0_flag = false, select L1
        writeUe(1) // collocated_ref_idx
        writeUe(0) // luma_log2_weight_denom
        writeSe(0) // delta_chroma_log2_weight_denom
        write(0, 1); write(0, 1) // L0 luma flags
        write(0, 1); write(0, 1) // L0 chroma flags
        write(1, 1); write(0, 1) // L1 luma flags
        write(1, 1); write(0, 1) // L1 chroma flags
        writeSe(1); writeSe(2) // L1 ref0 luma delta weight/offset
        writeSe(0); writeSe(0); writeSe(0); writeSe(0) // L1 ref0 Cb/Cr components
        writeUe(0) // five_minus_max_num_merge_cand
        writeSe(0) // slice_qp_delta
        write(1, 1) // loop filter across slices
        writeUe(0) // no tile entry points
    }.finish()

    private fun extendedPSliceBits(): ByteArray = BitWriter().apply {
        write(1, 1); writeUe(0); write(0, 2); writeUe(1)
        write(42, 8)
        write(0, 1); writeUe(2); writeUe(0)
        writeUe(0); write(1, 1); writeUe(2); write(0, 1)
        write(1, 1) // temporal MVP
        write(1, 1); write(1, 1) // SAO
        write(0, 1) // active reference override false
        writeUe(0) // five_minus_max_num_merge_cand
        writeSe(0) // slice_qp_delta
        write(1, 1) // slice_deblocking_filter_override_flag
        write(0, 1) // slice_deblocking_filter_disabled_flag
        writeSe(2); writeSe(-1) // beta and tc offsets
        write(1, 1) // loop filter across slices
        writeUe(2) // two tile entry points
        writeUe(2) // offset_len_minus1 = 2
        write(1, 3); write(3, 3) // offset_minus1 values => offsets 2 and 4
        writeUe(2) // extension length
        write(0xab, 8); write(0xcd, 8)
    }.finish()

    private fun nal(type: Int, rbsp: ByteArray, temporalIdPlus1: Int = 1): ByteArray =
        byteArrayOf((type shl 1).toByte(), temporalIdPlus1.toByte()) + rbsp

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
