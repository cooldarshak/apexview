package app.apexview.tiled

/** One decoded picture's identity and reference requirements, without touching coded payloads. */
internal data class HevcFrameSignature(
    val decodeIndex: Int,
    val poc: Long,
    val pocLsb: Int,
    val sliceType: Int,
    val references: List<HevcSliceHeaderParser.ReferencePicture>,
    val sliceSegmentAddresses: List<Int>
)

internal data class HevcFrameMismatch(
    val decodeIndex: Int,
    val firstPoc: Long?,
    val secondPoc: Long?,
    val pocAligned: Boolean,
    val sliceTypeAligned: Boolean,
    val referencesAligned: Boolean,
    val segmentLayoutAligned: Boolean
)

internal data class HevcFrameAlignmentReport(
    val firstFrameCount: Int,
    val secondFrameCount: Int,
    val comparedFrameCount: Int,
    val pocAlignedFrameCount: Int,
    val referenceAlignedFrameCount: Int,
    val fullyAlignedFrameCount: Int,
    val mismatches: List<HevcFrameMismatch>
) {
    val hasSameFrameCount: Boolean get() = firstFrameCount == secondFrameCount
    val pocAligned: Boolean get() = hasSameFrameCount && pocAlignedFrameCount == comparedFrameCount
    val referencesAligned: Boolean get() = hasSameFrameCount && referenceAlignedFrameCount == comparedFrameCount
    val fullyAligned: Boolean get() =
        hasSameFrameCount && fullyAlignedFrameCount == comparedFrameCount
}

/**
 * Reads clear Annex-B HEVC test streams and builds frame signatures from their slice headers.
 * This is analysis only: the input streams remain separate, and no VCL payload is combined.
 */
internal object HevcStreamAlignmentAnalyzer {
    fun analyze(annexB: ByteArray): List<HevcFrameSignature> {
        val spsById = mutableMapOf<Int, HevcParameterSets.SpsMetadata>()
        val ppsById = mutableMapOf<Int, HevcParameterSets.PpsMetadata>()
        val pocTracker = HevcPocTracker()
        val frames = mutableListOf<FrameBuilder>()
        val nals = HevcBitstream.splitAnnexB(annexB)

        for (nal in nals) {
            when (nal.type) {
                33 -> {
                    val sps = HevcParameterSets.parseSps(nal.bytes)
                    spsById[sps.spsId] = sps
                }
                34 -> {
                    val pps = HevcParameterSets.parsePps(nal.bytes)
                    ppsById[pps.ppsId] = pps
                }
                in 0..31 -> {
                    val ppsId = readSlicePpsId(nal.bytes, nal.type)
                    val pps = ppsById[ppsId]
                        ?: error("Slice references unknown PPS $ppsId")
                    val sps = spsById[pps.spsId]
                        ?: error("PPS ${pps.ppsId} references unknown SPS ${pps.spsId}")
                    val slice = HevcSliceHeaderParser.parse(nal.bytes, sps, pps)

                    if (slice.firstSliceSegmentInPicture) {
                        require(!slice.dependentSliceSegment) { "A first slice segment cannot be dependent" }
                        val pocLsb = slice.picOrderCntLsb
                            ?: error("Independent slice has no POC LSB")
                        val poc = pocTracker.derive(
                            nal.type, nal.temporalIdPlus1, pocLsb, sps.log2MaxPicOrderCntLsb
                        )
                        frames += FrameBuilder(
                            frames.size, poc, pocLsb, slice.sliceType
                                ?: error("Independent slice has no slice_type"),
                            slice.references, mutableListOf(slice.sliceSegmentAddress)
                        )
                    } else {
                        val current = frames.lastOrNull()
                            ?: error("Slice segment appeared before the first slice of a picture")
                        if (slice.picOrderCntLsb != null) {
                            require(slice.picOrderCntLsb == current.pocLsb) {
                                "POC LSB changed between slice segments in one picture"
                            }
                        }
                        current.sliceSegmentAddresses += slice.sliceSegmentAddress
                    }
                }
            }
        }
        return frames.map { it.toSignature() }
    }

    fun compare(
        first: List<HevcFrameSignature>,
        second: List<HevcFrameSignature>
    ): HevcFrameAlignmentReport {
        val count = minOf(first.size, second.size)
        var pocMatches = 0
        var refMatches = 0
        var fullMatches = 0
        val mismatches = mutableListOf<HevcFrameMismatch>()
        for (index in 0 until count) {
            val a = first[index]
            val b = second[index]
            val pocSame = a.poc == b.poc && a.pocLsb == b.pocLsb
            val typeSame = a.sliceType == b.sliceType
            val refsSame = a.references == b.references
            val segmentsSame = a.sliceSegmentAddresses == b.sliceSegmentAddresses
            if (pocSame) pocMatches++
            if (refsSame) refMatches++
            if (pocSame && typeSame && refsSame && segmentsSame) fullMatches++
            if (!pocSame || !typeSame || !refsSame || !segmentsSame) {
                mismatches += HevcFrameMismatch(
                    index, a.poc, b.poc, pocSame, typeSame, refsSame, segmentsSame
                )
            }
        }
        return HevcFrameAlignmentReport(
            first.size, second.size, count, pocMatches, refMatches, fullMatches, mismatches
        )
    }

    private fun readSlicePpsId(nal: ByteArray, nalType: Int): Int {
        val header = HevcBitstream.parseNalHeader(nal)
        require(header.type == nalType)
        val bits = HevcBitReader(HevcBitstream.ebspToRbsp(nal.copyOfRange(2, nal.size)))
        bits.readBit() // first_slice_segment_in_pic_flag
        if (nalType in 16..23) bits.readBit() // no_output_of_prior_pics_flag
        val ppsId = bits.readUnsignedExpGolomb()
        require(ppsId <= 63L) { "slice_pic_parameter_set_id exceeds supported range" }
        return ppsId.toInt()
    }

    private data class FrameBuilder(
        val decodeIndex: Int,
        val poc: Long,
        val pocLsb: Int,
        val sliceType: Int,
        val references: List<HevcSliceHeaderParser.ReferencePicture>,
        val sliceSegmentAddresses: MutableList<Int>
    ) {
        fun toSignature() = HevcFrameSignature(
            decodeIndex, poc, pocLsb, sliceType, references, sliceSegmentAddresses.toList()
        )
    }
}
