package app.apexview.tiled

/**
 * Reads the picture-identity and reference-picture portion of HEVC slice_segment_header().
 *
 * The parser stops after temporal-MVP and SAO flags. It does not parse weighted prediction,
 * deblocking, entry-point offsets, extensions or CABAC data, and it never rewrites slice headers.
 * Unsupported predicted short-term RPS and non-zero layer IDs are rejected.
 */
internal object HevcSliceHeaderParser {
    data class ReferencePicture(
        val deltaPoc: Int?,
        val pocLsb: Int?,
        val usedByCurrent: Boolean,
        val longTerm: Boolean,
        val deltaPocMsbCycle: Long? = null
    )

    data class SliceHeader(
        val nalUnitType: Int,
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
        val bitsConsumed: Int
    )

    fun parse(
        nal: ByteArray,
        sps: HevcParameterSets.SpsMetadata,
        pps: HevcParameterSets.PpsMetadata
    ): SliceHeader {
        val header = HevcBitstream.parseNalHeader(nal)
        require(header.type in 0..31) { "Expected a HEVC VCL NAL unit, got type ${header.type}" }
        require(header.layerId == 0) { "Non-zero HEVC layer IDs are not supported" }
        require(nal.size > 2) { "Truncated HEVC slice NAL" }
        require(pps.spsId == sps.spsId) { "PPS references a different SPS" }

        val bits = HevcBitReader(HevcBitstream.ebspToRbsp(nal.copyOfRange(2, nal.size)))
        val initialBits = bits.bitsRemaining
        val firstSlice = bits.readBit() != 0
        val isIrap = header.type in 16..23
        val noOutput = if (isIrap) bits.readBit() != 0 else null
        val ppsId = readUe(bits, 63, "slice_pic_parameter_set_id")
        require(ppsId == pps.ppsId) { "Slice references PPS $ppsId, expected ${pps.ppsId}" }

        var dependent = false
        var address = 0
        if (!firstSlice) {
            if (pps.dependentSliceSegmentsEnabled) dependent = bits.readBit() != 0
            val pictureCtbs = sps.picWidthInCtbs * sps.picHeightInCtbs
            val addressBits = ceilLog2(pictureCtbs)
            address = if (addressBits == 0) 0 else bits.readBits(addressBits)
            require(address < pictureCtbs) { "Slice segment address exceeds picture CTB count" }
        }

        if (dependent) {
            return SliceHeader(
                header.type, firstSlice, noOutput, ppsId, true, address,
                null, null, null, null, null, null, emptyList(), null, null, null,
                initialBits - bits.bitsRemaining
            )
        }

        repeat(pps.extraSliceHeaderBits) { bits.readBit() }
        val sliceType = readUe(bits, 2, "slice_type")
        val outputFlag = if (pps.outputFlagPresent) bits.readBit() != 0 else null
        val colourPlane = if (sps.separateColourPlane) bits.readBits(2) else null

        val isIdr = header.type == 19 || header.type == 20
        var pocLsb: Int? = null
        var fromSps: Boolean? = null
        var shortTermIndex: Int? = null
        val references = mutableListOf<ReferencePicture>()

        // IDR pictures infer POC LSB zero and carry no RPS syntax. The temporal-MVP and
        // SAO flags below still belong to the independent slice header and must be consumed.
        if (!isIdr) {
            pocLsb = bits.readBits(sps.log2MaxPicOrderCntLsb)
            // Present for every non-IDR slice, including when the SPS has zero RPS entries.
            fromSps = bits.readBit() != 0
            val shortTermSet = if (fromSps) {
                require(sps.shortTermRefPicSets.isNotEmpty()) {
                    "Slice selects an SPS short-term RPS but the SPS contains none"
                }
                shortTermIndex = if (sps.shortTermRefPicSets.size > 1) {
                    bits.readBits(ceilLog2(sps.shortTermRefPicSets.size))
                } else 0
                require(shortTermIndex in sps.shortTermRefPicSets.indices) {
                    "SPS short-term RPS index is out of range"
                }
                sps.shortTermRefPicSets[shortTermIndex!!]
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
                    references += ReferencePicture(
                        null, ref.pocLsb, ref.usedByCurrent, true, deltaCycle
                    )
                }
            }
        }

        val temporalMvp = if (sps.temporalMvpEnabled) bits.readBit() != 0 else false
        val saoLuma: Boolean?
        val saoChroma: Boolean?
        if (sps.sampleAdaptiveOffsetEnabled) {
            saoLuma = bits.readBit() != 0
            saoChroma = if (sps.chromaFormatIdc != 0 && !sps.separateColourPlane) {
                bits.readBit() != 0
            } else false
        } else {
            saoLuma = false
            saoChroma = false
        }

        return SliceHeader(
            header.type, firstSlice, noOutput, ppsId, false, address, sliceType,
            outputFlag, colourPlane, pocLsb, fromSps, shortTermIndex, references,
            temporalMvp, saoLuma, saoChroma, initialBits - bits.bitsRemaining
        )
    }

    private fun readUe(bits: HevcBitReader, max: Int, field: String): Int {
        val value = bits.readUnsignedExpGolomb()
        require(value <= max.toLong()) { "$field exceeds supported range" }
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

    fun reset() {
        previousTid0PocMsb = 0L
        previousTid0PocLsb = 0L
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
        val maxPocLsb = 1L shl log2MaxPocLsb
        require(pocLsb.toLong() in 0 until maxPocLsb) { "POC LSB is outside SPS range" }

        if (nalUnitType == 19 || nalUnitType == 20) {
            reset()
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
        val poc = pocMsb + current

        // prevTid0Pic excludes all RADL/RASL pictures plus every SLNR picture.
        // RADL_R/RASL_R are reference pictures, but are still excluded from the POC anchor.
        val excludedFromTid0Anchor = nalUnitType in setOf(0, 2, 4, 6, 7, 8, 9, 10, 12, 14)
        if (temporalIdPlus1 == 1 && !excludedFromTid0Anchor) {
            previousTid0PocMsb = pocMsb
            previousTid0PocLsb = current
        }
        return poc
    }
}
