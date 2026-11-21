package app.apexview.tiled

/** Picture-level identity fields that must match across independently encoded feeds. */
internal data class HevcPictureIdentity(
    val nalUnitType: Int,
    val ppsId: Int,
    val noOutputOfPriorPictures: Boolean?,
    val sliceType: Int,
    val picOutputFlag: Boolean?,
    val colourPlaneId: Int?,
    val picOrderCntLsb: Int?,
    val shortTermRpsFromSps: Boolean?,
    val shortTermRpsIndex: Int?,
    val references: List<HevcSliceHeaderParser.ReferencePicture>
)

/** One decoded picture's identity and reference requirements, without touching coded payloads. */
internal data class HevcFrameSignature(
    val decodeIndex: Int,
    val poc: Long,
    val pocLsb: Int,
    val sliceType: Int,
    val references: List<HevcSliceHeaderParser.ReferencePicture>,
    val sliceSegmentAddresses: List<Int>,
    val pictureIdentity: HevcPictureIdentity
)

internal data class HevcFrameMismatch(
    val decodeIndex: Int,
    val firstPoc: Long?,
    val secondPoc: Long?,
    val pocAligned: Boolean,
    val sliceTypeAligned: Boolean,
    val referencesAligned: Boolean,
    val segmentLayoutAligned: Boolean,
    val pictureIdentityAligned: Boolean
)

internal data class HevcFrameAlignmentReport(
    val firstFrameCount: Int,
    val secondFrameCount: Int,
    val comparedFrameCount: Int,
    val pocAlignedFrameCount: Int,
    val referenceAlignedFrameCount: Int,
    val pictureIdentityAlignedFrameCount: Int,
    val fullyAlignedFrameCount: Int,
    val mismatches: List<HevcFrameMismatch>
) {
    val hasSameFrameCount: Boolean get() = firstFrameCount == secondFrameCount
    val pocAligned: Boolean get() = hasSameFrameCount && pocAlignedFrameCount == comparedFrameCount
    val referencesAligned: Boolean get() = hasSameFrameCount && referenceAlignedFrameCount == comparedFrameCount
    val pictureIdentityAligned: Boolean get() =
        hasSameFrameCount && pictureIdentityAlignedFrameCount == comparedFrameCount
    val fullyAligned: Boolean get() =
        hasSameFrameCount && fullyAlignedFrameCount == comparedFrameCount
}

/**
 * Reads clear Annex-B HEVC test streams and builds frame signatures from their slice headers.
 * This is analysis only: the input streams remain separate, and no VCL payload is combined.
 */
internal object HevcStreamAlignmentAnalyzer {
    fun analyze(annexB: ByteArray): List<HevcFrameSignature> =
        analyzeNalUnits(HevcBitstream.splitAnnexB(annexB))

    /** Reuse an already validated split. Cloning VCL NALs a second time can cost tens of MB per segment. */
    internal fun analyzeNalUnits(
        nals: List<HevcBitstream.NalUnit>,
        prevalidatedSliceHeaders: Map<ByteArray, HevcSliceHeaderParser.SliceHeader> = emptyMap(),
    ): List<HevcFrameSignature> {
        val spsById = mutableMapOf<Int, HevcParameterSets.SpsMetadata>()
        val ppsById = mutableMapOf<Int, HevcParameterSets.PpsMetadata>()
        val pocTracker = HevcPocTracker()
        val frames = mutableListOf<FrameBuilder>()
        var sawCodedPicture = false
        var afterEndOfSequence = false

        for (nal in nals) {
            when (nal.type) {
                36 -> afterEndOfSequence = true // EOS_NUT
                33 -> {
                    val sps = HevcParameterSets.parseSps(nal.bytes)
                    spsById[sps.spsId] = sps
                }
                34 -> {
                    val pps = HevcParameterSets.parsePps(nal.bytes)
                    ppsById[pps.ppsId] = pps
                }
                in 0..31 -> {
                    // A compositor can provide the same already-validated header for the
                    // exact owned NAL object. Independent inspector callers keep the
                    // original PPS lookup and bounded parse below.
                    val prevalidated = prevalidatedSliceHeaders[nal.bytes]
                    val ppsId = prevalidated?.ppsId ?: readSlicePpsId(nal.bytes, nal.type)
                    val pps = ppsById[ppsId]
                        ?: error("Slice references unknown PPS $ppsId")
                    val sps = spsById[pps.spsId]
                        ?: error("PPS ${pps.ppsId} references unknown SPS ${pps.spsId}")
                    val slice = prevalidated ?: HevcSliceHeaderParser.parse(nal.bytes, sps, pps)
                    require(slice.ppsId == pps.ppsId && slice.nalUnitType == nal.type) {
                        "Cached slice header does not match its active parameter set or NAL"
                    }

                    if (slice.firstSliceSegmentInPicture) {
                        require(!slice.dependentSliceSegment) { "A first slice segment cannot be dependent" }
                        // IDR infers POC LSB zero; it is not present in the slice header.
                        val pocLsb = slice.picOrderCntLsb
                            ?: if (nal.type == 19 || nal.type == 20) 0
                            else error("Independent non-IDR slice has no POC LSB")
                        val isIrap = nal.type in 16..23
                        val noRaslOutputFlag = isIrap && when {
                            nal.type in 16..20 -> true // BLA and IDR are always reset points
                            else -> !sawCodedPicture || afterEndOfSequence
                        }
                        val poc = pocTracker.derive(
                            nal.type, nal.temporalIdPlus1, pocLsb, sps.log2MaxPicOrderCntLsb,
                            noRaslOutputFlag = noRaslOutputFlag
                        )
                        frames += FrameBuilder(
                            decodeIndex = frames.size,
                            poc = poc,
                            pocLsb = pocLsb,
                            sliceType = slice.sliceType ?: error("Independent slice has no slice_type"),
                            ppsId = slice.ppsId,
                            noOutputOfPriorPictures = slice.noOutputOfPriorPictures,
                            picOutputFlag = slice.picOutputFlag,
                            shortTermRpsFromSps = slice.shortTermRpsFromSps,
                            shortTermRpsIndex = slice.shortTermRpsIndex,
                            temporalMvpEnabled = slice.temporalMvpEnabled,
                            references = slice.references,
                            sliceSegmentAddresses = mutableListOf(slice.sliceSegmentAddress),
                            pictureIdentity = HevcPictureIdentity(
                                nalUnitType = slice.nalUnitType,
                                ppsId = slice.ppsId,
                                noOutputOfPriorPictures = slice.noOutputOfPriorPictures,
                                sliceType = requireNotNull(slice.sliceType),
                                picOutputFlag = slice.picOutputFlag,
                                colourPlaneId = slice.colourPlaneId,
                                picOrderCntLsb = slice.picOrderCntLsb,
                                shortTermRpsFromSps = slice.shortTermRpsFromSps,
                                shortTermRpsIndex = slice.shortTermRpsIndex,
                                references = slice.references
                            )
                        )
                        sawCodedPicture = true
                        afterEndOfSequence = false
                    } else {
                        val current = frames.lastOrNull()
                            ?: error("Slice segment appeared before the first slice of a picture")
                        require(slice.ppsId == current.ppsId) {
                            "PPS changed between slice segments in one picture"
                        }
                        require(slice.noOutputOfPriorPictures == current.noOutputOfPriorPictures) {
                            "no_output_of_prior_pics_flag changed between slice segments in one picture"
                        }
                        if (!slice.dependentSliceSegment) {
                            val segmentPocLsb = slice.picOrderCntLsb
                                ?: if (nal.type == 19 || nal.type == 20) 0 else null
                            require(segmentPocLsb == current.pocLsb) {
                                "POC LSB changed between independent slice segments in one picture"
                            }
                            require(slice.picOutputFlag == current.picOutputFlag) {
                                "pic_output_flag changed between slice segments in one picture"
                            }
                            require(slice.shortTermRpsFromSps == current.shortTermRpsFromSps &&
                                slice.shortTermRpsIndex == current.shortTermRpsIndex
                            ) { "Short-term RPS selection changed between slice segments in one picture" }
                            require(slice.references == current.references) {
                                "Reference picture set changed between slice segments in one picture"
                            }
                            require(slice.temporalMvpEnabled == current.temporalMvpEnabled) {
                                "slice_temporal_mvp_enabled_flag changed between slice segments in one picture"
                            }
                        }
                        require(slice.sliceSegmentAddress !in current.sliceSegmentAddresses) {
                            "Duplicate slice_segment_address in one picture"
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
        var identityMatches = 0
        var fullMatches = 0
        val mismatches = mutableListOf<HevcFrameMismatch>()
        for (index in 0 until count) {
            val a = first[index]
            val b = second[index]
            val pocSame = a.poc == b.poc && a.pocLsb == b.pocLsb
            val typeSame = a.sliceType == b.sliceType
            val refsSame = a.references == b.references
            val segmentsSame = a.sliceSegmentAddresses == b.sliceSegmentAddresses
            val identitySame = a.pictureIdentity == b.pictureIdentity
            if (pocSame) pocMatches++
            if (refsSame) refMatches++
            if (identitySame) identityMatches++
            if (pocSame && typeSame && refsSame && segmentsSame && identitySame) fullMatches++
            if (!pocSame || !typeSame || !refsSame || !segmentsSame || !identitySame) {
                mismatches += HevcFrameMismatch(
                    index, a.poc, b.poc, pocSame, typeSame, refsSame, segmentsSame, identitySame
                )
            }
        }
        return HevcFrameAlignmentReport(
            first.size, second.size, count, pocMatches, refMatches, identityMatches,
            fullMatches, mismatches
        )
    }

    private fun readSlicePpsId(nal: ByteArray, nalType: Int): Int {
        val header = HevcBitstream.readNalHeader(nal)
        require(header.type == nalType)
        // Picture PPS id is in the first few bytes. Do not copy the encoded VCL payload.
        // The 128-byte inspection window may end exactly on 00 00 03 (an HEVC
        // emulation-prevention byte), while the following byte still exists in
        // this NAL. Include that following byte so the bounded EBSP validator
        // doesn't mistake a valid partial window for a truncated full NAL.
        var prefixBytes = minOf(nal.size - 2, 128)
        val end = 2 + prefixBytes
        if (end < nal.size && prefixBytes >= 3 &&
            nal[end - 3] == 0.toByte() && nal[end - 2] == 0.toByte() && nal[end - 1] == 3.toByte()) {
            prefixBytes++
        }
        val bits = HevcBitReader(HevcBitstream.ebspToRbsp(nal, 2, prefixBytes))
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
        val ppsId: Int,
        val noOutputOfPriorPictures: Boolean?,
        val picOutputFlag: Boolean?,
        val shortTermRpsFromSps: Boolean?,
        val shortTermRpsIndex: Int?,
        val temporalMvpEnabled: Boolean?,
        val references: List<HevcSliceHeaderParser.ReferencePicture>,
        val sliceSegmentAddresses: MutableList<Int>,
        val pictureIdentity: HevcPictureIdentity
    ) {
        fun toSignature() = HevcFrameSignature(
            decodeIndex, poc, pocLsb, sliceType, references, sliceSegmentAddresses.toList(), pictureIdentity
        )
    }
}
