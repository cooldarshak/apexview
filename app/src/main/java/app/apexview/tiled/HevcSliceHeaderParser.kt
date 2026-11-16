package app.apexview.tiled

/**
 * Bounded HEVC slice_segment_header() parser for clear NAL units.
 *
 * It parses picture identity, reference-list syntax, weighted-prediction tables, deblocking
 * overrides, tile/WPP entry-point offsets, optional header-extension lengths, and byte alignment.
 * It does not parse CABAC slice data or rewrite a slice header. Unsupported parameter-set
 * extensions and unsupported merge profiles remain fail-closed in the compatibility gate.
 */
internal object HevcSliceHeaderParser {
    data class ReferencePicture(
        val deltaPoc: Int?,
        val pocLsb: Int?,
        val usedByCurrent: Boolean,
        val longTerm: Boolean,
        val deltaPocMsbCycle: Long? = null
    )

    data class PredictionWeightComponent(val deltaWeight: Int, val offset: Int)

    data class ReferencePredictionWeight(
        val luma: PredictionWeightComponent?,
        val chroma: List<PredictionWeightComponent>?
    )

    data class PredictionWeightTable(
        val lumaLog2WeightDenom: Int,
        val chromaLog2WeightDenom: Int?,
        val list0: List<ReferencePredictionWeight>,
        val list1: List<ReferencePredictionWeight>
    )

    data class SliceHeader(
        val nalUnitType: Int,
        val temporalIdPlus1: Int,
        val firstSliceSegmentInPicture: Boolean,
        val noOutputOfPriorPictures: Boolean?,
        val ppsId: Int,
        val dependentSliceSegment: Boolean,
        val sliceSegmentAddress: Int,
        val sliceType: Int?,
        val picOutputFlag: Boolean?,
        val colourPlaneId: Int?,
        val picOrderCntLsb: Int?,
        val shortTermRpsFromSps: Boolean?,
        val shortTermRpsIndex: Int?,
        val references: List<ReferencePicture>,
        val temporalMvpEnabled: Boolean?,
        val sampleAdaptiveOffsetLuma: Boolean?,
        val sampleAdaptiveOffsetChroma: Boolean?,
        val bitsConsumed: Int,
        val numPicTotalCurrent: Int = 0,
        val numRefIdxL0Active: Int? = null,
        val numRefIdxL1Active: Int? = null,
        val list0Modification: List<Int> = emptyList(),
        val list1Modification: List<Int> = emptyList(),
        val mvdL1Zero: Boolean? = null,
        val cabacInitEnabled: Boolean? = null,
        val collocatedFromL0: Boolean? = null,
        val collocatedReferenceIndex: Int? = null,
        val predictionWeightTable: PredictionWeightTable? = null,
        val fiveMinusMaxNumMergeCand: Int? = null,
        val sliceQpDelta: Int? = null,
        val sliceChromaQpOffsets: Pair<Int, Int>? = null,
        val deblockingFilterOverride: Boolean? = null,
        val deblockingFilterDisabled: Boolean? = null,
        val betaOffsetDiv2: Int? = null,
        val tcOffsetDiv2: Int? = null,
        val loopFilterAcrossSlices: Boolean? = null,
        val loopFilterAcrossSlicesFieldBit: Int? = null,
        val entryPointOffsets: List<Long> = emptyList(),
        val sliceHeaderExtensionLength: Int = 0,
        val sourceAddressEndBit: Int,
        val entryPointFieldStartBit: Int,
        val entryPointFieldEndBit: Int,
        val alignmentStartBit: Int
    )

    fun parse(
        nal: ByteArray,
        sps: HevcParameterSets.SpsMetadata,
        pps: HevcParameterSets.PpsMetadata,
        clearNalBytes: Int = nal.size
    ): SliceHeader {
        val header = HevcBitstream.parseNalHeader(nal)
        require(header.type in 0..21) {
            "Reserved or unsupported HEVC VCL NAL type ${header.type}"
        }
        require(header.layerId == 0) { "Non-zero HEVC layer IDs are not supported" }
        require(header.temporalIdPlus1 <= sps.maxSubLayers) {
            "HEVC temporal_id_plus1 exceeds the SPS sub-layer limit"
        }
        require(nal.size > 2) { "Truncated HEVC slice NAL" }
        require(clearNalBytes in 3..nal.size) { "Clear HEVC slice prefix is outside the NAL unit" }
        require(pps.spsId == sps.spsId) { "PPS references a different SPS" }
        require(!sps.spsExtensionPresent) {
            "SPS extensions are not parsed by the bounded slice-header parser"
        }
        require(!pps.extensionPresent) {
            "PPS extensions are not parsed by the bounded slice-header parser"
        }
        val qpBdOffsetY = 6 * (sps.bitDepthLuma - 8)
        require(pps.initQpMinus26 in (-26 - qpBdOffsetY)..25) {
            "PPS init_qp_minus26 exceeds the range for the SPS luma bit depth"
        }

        val bits = HevcBitReader(HevcBitstream.ebspToRbsp(nal.copyOfRange(2, clearNalBytes)))
        val initialBits = bits.bitsRemaining
        fun consumed() = initialBits - bits.bitsRemaining

        val firstSlice = bits.readBit() != 0
        val isIrap = header.type in 16..21
        val noOutput = if (isIrap) bits.readBit() != 0 else null
        val ppsId = readUe(bits, 63, "slice_pic_parameter_set_id")
        require(ppsId == pps.ppsId) { "Slice references PPS $ppsId, expected ${pps.ppsId}" }

        var dependent = false
        var address = 0
        if (!firstSlice) {
            if (pps.dependentSliceSegmentsEnabled) dependent = bits.readBit() != 0
            val pictureCtbs = Math.multiplyExact(sps.picWidthInCtbs, sps.picHeightInCtbs)
            val addressBits = ceilLog2(pictureCtbs)
            address = if (addressBits == 0) 0 else bits.readBits(addressBits)
            require(address < pictureCtbs) { "Slice segment address exceeds picture CTB count" }
        }
        val sourceAddressEndBit = bits.positionBits

        var sliceType: Int? = null
        var outputFlag: Boolean? = null
        var colourPlane: Int? = null
        var pocLsb: Int? = null
        var fromSps: Boolean? = null
        var shortTermIndex: Int? = null
        val references = mutableListOf<ReferencePicture>()
        var temporalMvp: Boolean? = null
        var saoLuma: Boolean? = null
        var saoChroma: Boolean? = null
        var numPicTotalCurrent = 0
        var numL0: Int? = null
        var numL1: Int? = null
        var list0Modification = emptyList<Int>()
        var list1Modification = emptyList<Int>()
        var mvdL1Zero: Boolean? = null
        var cabacInit: Boolean? = null
        var collocatedFromL0: Boolean? = null
        var collocatedIndex: Int? = null
        var weightTable: PredictionWeightTable? = null
        var fiveMinusMaxMerge: Int? = null
        var sliceQpDelta: Int? = null
        var chromaQpOffsets: Pair<Int, Int>? = null
        var deblockingOverride: Boolean? = null
        var deblockingDisabled: Boolean? = null
        var betaOffset: Int? = null
        var tcOffset: Int? = null
        var loopFilterAcrossSlices: Boolean? = null
        var loopFilterAcrossSlicesFieldBit: Int? = null
        var entryPointOffsets = emptyList<Long>()
        var entryPointFieldStartBit = bits.positionBits
        var entryPointFieldEndBit = bits.positionBits

        if (!dependent) {
            repeat(pps.extraSliceHeaderBits) { bits.readBit() }
            sliceType = readUe(bits, 2, "slice_type")
            outputFlag = if (pps.outputFlagPresent) bits.readBit() != 0 else null
            colourPlane = if (sps.separateColourPlane) bits.readBits(2) else null

            val isIdr = header.type == 19 || header.type == 20
            if (!isIdr) {
                pocLsb = bits.readBits(sps.log2MaxPicOrderCntLsb)
                fromSps = bits.readBit() != 0
                val shortTermSet = if (fromSps) {
                    require(sps.shortTermRefPicSets.isNotEmpty()) {
                        "Slice selects an SPS short-term RPS but the SPS contains none"
                    }
                    val selectedIndex = if (sps.shortTermRefPicSets.size > 1) {
                        bits.readBits(ceilLog2(sps.shortTermRefPicSets.size))
                    } else 0
                    require(selectedIndex in sps.shortTermRefPicSets.indices) {
                        "SPS short-term RPS index is out of range"
                    }
                    shortTermIndex = selectedIndex
                    sps.shortTermRefPicSets[selectedIndex]
                } else {
                    HevcParameterSets.parseShortTermRefPicSet(
                        bits, sps.shortTermRefPicSets.size, sps.shortTermRefPicSets.size,
                        sps.shortTermRefPicSets
                    )
                }

                references += shortTermSet.all.map {
                    ReferencePicture(it.deltaPoc, null, it.usedByCurrent, false)
                }

                if (sps.longTermRefPicsPresent) {
                    val numLongTermSps = if (sps.longTermRefPics.isNotEmpty()) {
                        readUe(bits, sps.longTermRefPics.size, "num_long_term_sps")
                    } else 0
                    val numLongTermPictures = readUe(bits, 32, "num_long_term_pics")
                    require(numLongTermSps + numLongTermPictures <= 32) {
                        "Slice long-term RPS exceeds supported reference count"
                    }
                    repeat(numLongTermSps + numLongTermPictures) { index ->
                        val ref = if (index < numLongTermSps) {
                            val spsIndex = if (sps.longTermRefPics.size > 1) {
                                bits.readBits(ceilLog2(sps.longTermRefPics.size))
                            } else 0
                            require(spsIndex in sps.longTermRefPics.indices) {
                                "Long-term SPS reference index is out of range"
                            }
                            sps.longTermRefPics[spsIndex]
                        } else {
                            HevcParameterSets.LongTermReference(
                                bits.readBits(sps.log2MaxPicOrderCntLsb),
                                bits.readBit() != 0
                            )
                        }
                        val deltaPresent = bits.readBit() != 0
                        val deltaCycle = if (deltaPresent) bits.readUnsignedExpGolomb() else null
                        references += ReferencePicture(null, ref.pocLsb, ref.usedByCurrent, true, deltaCycle)
                    }
                }
            }

            numPicTotalCurrent = references.count { it.usedByCurrent }
            temporalMvp = if (sps.temporalMvpEnabled && !isIdr) bits.readBit() != 0 else false
            if (sps.sampleAdaptiveOffsetEnabled) {
                saoLuma = bits.readBit() != 0
                saoChroma = if (sps.chromaFormatIdc != 0 && !sps.separateColourPlane) {
                    bits.readBit() != 0
                } else false
            } else {
                saoLuma = false
                saoChroma = false
            }

            if (sliceType != 2) {
                val activeOverride = bits.readBit() != 0
                numL0 = if (activeOverride) {
                    readUe(bits, 14, "num_ref_idx_l0_active_minus1") + 1
                } else pps.numRefIdxL0DefaultActiveMinus1 + 1
                numL1 = if (sliceType == 0) {
                    if (activeOverride) readUe(bits, 14, "num_ref_idx_l1_active_minus1") + 1
                    else pps.numRefIdxL1DefaultActiveMinus1 + 1
                } else null

                if (pps.listsModificationPresent && numPicTotalCurrent > 1) {
                    val width = ceilLog2(numPicTotalCurrent)
                    val modifyL0 = bits.readBit() != 0
                    if (modifyL0) {
                        list0Modification = List(numL0) {
                            bits.readBits(width).also {
                                require(it < numPicTotalCurrent) { "list_entry_l0 exceeds NumPicTotalCurr" }
                            }
                        }
                    }
                    if (sliceType == 0) {
                        val modifyL1 = bits.readBit() != 0
                        if (modifyL1) {
                            list1Modification = List(requireNotNull(numL1)) {
                                bits.readBits(width).also {
                                    require(it < numPicTotalCurrent) { "list_entry_l1 exceeds NumPicTotalCurr" }
                                }
                            }
                        }
                    }
                }

                mvdL1Zero = if (sliceType == 0) bits.readBit() != 0 else null
                cabacInit = if (pps.cabacInitPresent) bits.readBit() != 0 else false
                if (temporalMvp == true) {
                    collocatedFromL0 = if (sliceType == 0) bits.readBit() != 0 else true
                    val collocatedCount = if (collocatedFromL0 == true) numL0 else numL1
                    if (collocatedCount != null && collocatedCount > 1) {
                        collocatedIndex = readUe(bits, collocatedCount - 1, "collocated_ref_idx")
                    }
                }

                if ((pps.weightedPredictionEnabled && sliceType == 1) ||
                    (pps.weightedBipredictionEnabled && sliceType == 0)) {
                    weightTable = parsePredictionWeightTable(
                        bits, requireNotNull(numL0), numL1 ?: 0,
                        chromaEnabled = sps.chromaFormatIdc != 0 && !sps.separateColourPlane,
                        bipred = sliceType == 0
                    )
                }
                fiveMinusMaxMerge = readUe(bits, 4, "five_minus_max_num_merge_cand")
            }

            val qpBdOffsetY = 6 * (sps.bitDepthLuma - 8)
            val initialSliceQp = 26 + pps.initQpMinus26
            sliceQpDelta = readSe(
                bits,
                -qpBdOffsetY - initialSliceQp,
                51 - initialSliceQp,
                "slice_qp_delta"
            )
            if (pps.sliceChromaQpOffsetsPresent) {
                val cb = readSe(bits, -12, 12, "slice_cb_qp_offset")
                val cr = readSe(bits, -12, 12, "slice_cr_qp_offset")
                chromaQpOffsets = cb to cr
            }

            deblockingDisabled = pps.deblockingFilterDisabled ?: false
            betaOffset = pps.betaOffsetDiv2
            tcOffset = pps.tcOffsetDiv2
            if (pps.deblockingFilterOverrideEnabled == true) {
                deblockingOverride = bits.readBit() != 0
                if (deblockingOverride == true) {
                    deblockingDisabled = bits.readBit() != 0
                    if (deblockingDisabled == false) {
                        betaOffset = readSe(bits, -6, 6, "slice_beta_offset_div2")
                        tcOffset = readSe(bits, -6, 6, "slice_tc_offset_div2")
                    } else {
                        betaOffset = null
                        tcOffset = null
                    }
                }
            }

            if (pps.loopFilterAcrossSlicesEnabled &&
                (saoLuma == true || saoChroma == true || deblockingDisabled == false)) {
                loopFilterAcrossSlicesFieldBit = bits.positionBits
                loopFilterAcrossSlices = bits.readBit() != 0
            }

            entryPointFieldStartBit = bits.positionBits
            if (pps.tilesEnabled || pps.entropyCodingSyncEnabled) {
                val count = readUe(bits, 65_535, "num_entry_point_offsets")
                if (count > 0) {
                    val offsetLengthMinus1 = readUe(bits, 31, "offset_len_minus1")
                    val offsetBits = offsetLengthMinus1 + 1
                    entryPointOffsets = List(count) {
                        (bits.readBits(offsetBits).toLong() and 0xffff_ffffL) + 1L
                    }
                }
            }
            entryPointFieldEndBit = bits.positionBits
        }

        val extensionLength = if (pps.sliceSegmentHeaderExtensionPresent) {
            val length = readUe(bits, 1024, "slice_segment_header_extension_length")
            require(length.toLong() * 8L <= bits.bitsRemaining.toLong()) {
                "Slice-header extension exceeds the remaining bitstream"
            }
            repeat(length) { bits.readBits(8) }
            length
        } else 0
        val alignmentStartBit = bits.positionBits
        readByteAlignment(bits, ::consumed)

        return SliceHeader(
            nalUnitType = header.type,
            temporalIdPlus1 = header.temporalIdPlus1,
            firstSliceSegmentInPicture = firstSlice,
            noOutputOfPriorPictures = noOutput,
            ppsId = ppsId,
            dependentSliceSegment = dependent,
            sliceSegmentAddress = address,
            sliceType = sliceType,
            picOutputFlag = outputFlag,
            colourPlaneId = colourPlane,
            picOrderCntLsb = pocLsb,
            shortTermRpsFromSps = fromSps,
            shortTermRpsIndex = shortTermIndex,
            references = references,
            temporalMvpEnabled = temporalMvp,
            sampleAdaptiveOffsetLuma = saoLuma,
            sampleAdaptiveOffsetChroma = saoChroma,
            bitsConsumed = consumed(),
            numPicTotalCurrent = numPicTotalCurrent,
            numRefIdxL0Active = numL0,
            numRefIdxL1Active = numL1,
            list0Modification = list0Modification,
            list1Modification = list1Modification,
            mvdL1Zero = mvdL1Zero,
            cabacInitEnabled = cabacInit,
            collocatedFromL0 = collocatedFromL0,
            collocatedReferenceIndex = collocatedIndex,
            predictionWeightTable = weightTable,
            fiveMinusMaxNumMergeCand = fiveMinusMaxMerge,
            sliceQpDelta = sliceQpDelta,
            sliceChromaQpOffsets = chromaQpOffsets,
            deblockingFilterOverride = deblockingOverride,
            deblockingFilterDisabled = deblockingDisabled,
            betaOffsetDiv2 = betaOffset,
            tcOffsetDiv2 = tcOffset,
            loopFilterAcrossSlices = loopFilterAcrossSlices,
            loopFilterAcrossSlicesFieldBit = loopFilterAcrossSlicesFieldBit,
            entryPointOffsets = entryPointOffsets,
            sliceHeaderExtensionLength = extensionLength,
            sourceAddressEndBit = sourceAddressEndBit,
            entryPointFieldStartBit = entryPointFieldStartBit,
            entryPointFieldEndBit = entryPointFieldEndBit,
            alignmentStartBit = alignmentStartBit
        )
    }

    private fun parsePredictionWeightTable(
        bits: HevcBitReader,
        numL0: Int,
        numL1: Int,
        chromaEnabled: Boolean,
        bipred: Boolean
    ): PredictionWeightTable {
        val lumaDenom = readUe(bits, 7, "luma_log2_weight_denom")
        val chromaDenom = if (chromaEnabled) {
            val value = readSe(bits, -7, 7, "delta_chroma_log2_weight_denom")
            (lumaDenom + value).also {
                require(it in 0..7) { "chroma_log2_weight_denom is outside HEVC range" }
            }
        } else null

        fun readList(active: Int): List<ReferencePredictionWeight> {
            val lumaFlags = List(active) { bits.readBit() != 0 }
            val chromaFlags = if (chromaEnabled) List(active) { bits.readBit() != 0 }
                else List(active) { false }
            return List(active) { index ->
                val luma = if (lumaFlags[index]) {
                    PredictionWeightComponent(
                        readSe(bits, -128, 127, "delta_luma_weight"),
                        readSe(bits, -128, 127, "luma_offset")
                    )
                } else null
                val chroma = if (chromaFlags[index]) {
                    List(2) {
                        PredictionWeightComponent(
                            readSe(bits, -128, 127, "delta_chroma_weight"),
                            readSe(bits, -512, 511, "delta_chroma_offset")
                        )
                    }
                } else null
                ReferencePredictionWeight(luma, chroma)
            }
        }

        val list0 = readList(numL0)
        val list1 = if (bipred) readList(numL1) else emptyList()
        return PredictionWeightTable(lumaDenom, chromaDenom, list0, list1)
    }

    private fun readByteAlignment(bits: HevcBitReader, consumed: () -> Int) {
        require(bits.bitsRemaining > 0) { "Slice header is missing byte_alignment()" }
        require(bits.readBit() == 1) { "alignment_bit_equal_to_one is not set" }
        while (consumed() % 8 != 0) {
            require(bits.bitsRemaining > 0) { "Truncated byte_alignment()" }
            require(bits.readBit() == 0) { "alignment_bit_equal_to_zero is not zero" }
        }
    }

    private fun readUe(bits: HevcBitReader, max: Int, field: String): Int {
        val value = bits.readUnsignedExpGolomb()
        require(value <= max.toLong()) { "$field exceeds supported range" }
        return value.toInt()
    }

    private fun readSe(bits: HevcBitReader, min: Int, max: Int, field: String): Int {
        val value = bits.readSignedExpGolomb()
        require(value in min.toLong()..max.toLong()) { "$field exceeds supported range" }
        return value.toInt()
    }

    private fun ceilLog2(value: Int): Int {
        require(value > 0)
        return if (value <= 1) 0 else 32 - Integer.numberOfLeadingZeros(value - 1)
    }
}

/**
 * Computes PicOrderCntVal using the H.265 wrap and random-access rules.
 *
 * noRaslOutputFlag is derived from bitstream position by the analyzer: it is always true for
 * IDR/BLA, and for CRA only at the first picture in the bitstream or the first picture after EOS
 * (unless an external HandleCraAsBla policy is explicitly introduced).
 */
internal class HevcPocTracker {
    private var previousTid0PocMsb = 0L
    private var previousTid0PocLsb = 0L

    /** True after any valid temporal-id-zero reference picture has established prevTid0Pic. */
    var hasReferenceAnchor: Boolean = false
        private set

    /** True only after a temporal-id-zero IDR/BLA or CRA with NoRaslOutputFlag=1. */
    var hasIrapAnchor: Boolean = false
        private set

    fun reset() {
        previousTid0PocMsb = 0L
        previousTid0PocLsb = 0L
        hasReferenceAnchor = false
        hasIrapAnchor = false
    }

    /** Creates an isolated working copy so a failed composition cannot advance the live tracker. */
    fun fork(): HevcPocTracker = HevcPocTracker().also {
        it.previousTid0PocMsb = previousTid0PocMsb
        it.previousTid0PocLsb = previousTid0PocLsb
        it.hasReferenceAnchor = hasReferenceAnchor
        it.hasIrapAnchor = hasIrapAnchor
    }

    /** Commits a successfully validated working copy back into this feed's tracker. */
    fun commitFrom(snapshot: HevcPocTracker) {
        previousTid0PocMsb = snapshot.previousTid0PocMsb
        previousTid0PocLsb = snapshot.previousTid0PocLsb
        hasReferenceAnchor = snapshot.hasReferenceAnchor
        hasIrapAnchor = snapshot.hasIrapAnchor
    }

    fun derive(
        nalUnitType: Int,
        temporalIdPlus1: Int,
        pocLsb: Int,
        log2MaxPocLsb: Int,
        noRaslOutputFlag: Boolean = nalUnitType in 16..20
    ): Long {
        require(temporalIdPlus1 in 1..7) { "Invalid temporal_id_plus1" }
        require(log2MaxPocLsb in 4..16) { "Unsupported POC LSB width" }
        require(nalUnitType in 0..23) { "Unsupported HEVC NAL unit type for POC derivation" }
        val maxPocLsb = 1L shl log2MaxPocLsb
        require(pocLsb.toLong() in 0 until maxPocLsb) { "POC LSB is outside SPS range" }

        if (nalUnitType == 19 || nalUnitType == 20) {
            reset()
            if (temporalIdPlus1 == 1) {
                hasReferenceAnchor = true
                hasIrapAnchor = true
            }
            return 0L
        }

        val isIrap = nalUnitType in 16..23
        val resetPocMsb = isIrap && (nalUnitType in 16..18 || noRaslOutputFlag)
        val current = pocLsb.toLong()

        val pocMsb = if (resetPocMsb) {
            // For IRAP with NoRaslOutputFlag=1 the standard sets PicOrderCntMsb to zero.
            // Do not run the wrap comparison against an artificial zero anchor.
            0L
        } else {
            when {
                current < previousTid0PocLsb &&
                    previousTid0PocLsb - current >= maxPocLsb / 2L ->
                    previousTid0PocMsb + maxPocLsb
                current > previousTid0PocLsb &&
                    current - previousTid0PocLsb > maxPocLsb / 2L ->
                    previousTid0PocMsb - maxPocLsb
                else -> previousTid0PocMsb
            }
        }
        val poc = Math.addExact(pocMsb, current)

        // prevTid0Pic excludes all RADL/RASL pictures plus every SLNR picture.
        // RADL_R/RASL_R are reference pictures, but are still excluded from the POC anchor.
        val excludedFromTid0Anchor = nalUnitType in setOf(0, 2, 4, 6, 7, 8, 9, 10, 12, 14)
        if (temporalIdPlus1 == 1 && !excludedFromTid0Anchor) {
            previousTid0PocMsb = pocMsb
            previousTid0PocLsb = current
            hasReferenceAnchor = true
            if (resetPocMsb) hasIrapAnchor = true
        }
        return poc
    }
}
