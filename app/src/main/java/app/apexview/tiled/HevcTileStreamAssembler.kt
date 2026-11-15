package app.apexview.tiled

import java.io.ByteArrayOutputStream

/**
 * Reassembles the deliberately narrow synthetic 2x1 HEVC tile profile used by the POC.
 *
 * Input streams must already be sample/time aligned. Annex-B has no timestamps, so this API
 * must not align arbitrary live feeds. It validates both complementary slices for each picture
 * and emits their original NAL bytes in tile-scan order. No slice header or coded payload is
 * rewritten.
 *
 * The implementation fails closed on unsupported NALs, parameter-set differences, dependent
 * slices, unexpected tile geometry, missing pictures, and picture/reference mismatches.
 */
internal object HevcTileStreamAssembler {
    data class AssemblyResult(
        val annexB: ByteArray,
        val pictureCount: Int,
        val tileSlicesPerPicture: Int,
        val sliceSegmentAddresses: List<Pair<Int, Int>>
    )

    fun assembleTwoTileStreams(leftAnnexB: ByteArray, rightAnnexB: ByteArray): AssemblyResult {
        val left = parseFeed("left", leftAnnexB)
        val right = parseFeed("right", rightAnnexB)

        require(left.parameterSets.map { it.bytes.toList() } ==
            right.parameterSets.map { it.bytes.toList() }) {
            "Tile feeds do not carry byte-identical VPS/SPS/PPS"
        }
        require(left.sps == right.sps && left.pps == right.pps) {
            "Tile feeds resolve to different parsed SPS/PPS metadata"
        }
        require(left.vcl.size == right.vcl.size && left.vcl.isNotEmpty()) {
            "Tile feeds must contain the same non-zero number of picture slices"
        }

        val sps = left.sps
        val pps = left.pps
        require(sps.width > 0 && sps.height > 0) { "Invalid SPS picture dimensions" }
        require(sps.spsExtensionPresent == false) {
            "SPS extensions are outside the supported synthetic profile"
        }
        require(pps.tilesEnabled && pps.numTileColumns == 2 && pps.numTileRows == 1) {
            "Only a 2-column by 1-row HEVC tile grid is supported"
        }
        require(!pps.entropyCodingSyncEnabled) { "Wavefront entropy coding is not supported" }
        require(!pps.weightedPredictionEnabled && !pps.weightedBipredictionEnabled) {
            "Weighted prediction is not supported"
        }
        require(pps.deblockingFilterOverrideEnabled != true) {
            "Per-slice deblocking overrides are not supported"
        }
        require(!pps.scalingListDataPresent && !pps.listsModificationPresent &&
            !pps.sliceSegmentHeaderExtensionPresent && !pps.extensionPresent) {
            "PPS contains unsupported extension or slice-header tools"
        }
        val tileColumnWidths = requireNotNull(pps.tileColumnWidthsInCtbs) {
            "PPS tile column geometry is missing"
        }
        val tileRowHeights = requireNotNull(pps.tileRowHeightsInCtbs) {
            "PPS tile row geometry is missing"
        }
        require(tileColumnWidths.size == 2 && tileRowHeights.size == 1) {
            "Parsed tile dimensions do not match a 2x1 grid"
        }
        require(tileColumnWidths[0] == tileColumnWidths[1] &&
            tileColumnWidths.sum() == sps.picWidthInCtbs &&
            tileRowHeights.single() == sps.picHeightInCtbs) {
            "Only a uniform, full-picture 2x1 CTB layout is supported"
        }

        val expectedRightAddress = tileColumnWidths[0]
        val output = ByteArrayOutputStream()
        left.parameterSets.forEach { appendNal(output, it.bytes) }
        val addresses = ArrayList<Pair<Int, Int>>(left.vcl.size)

        for (index in left.vcl.indices) {
            val leftNal = left.vcl[index]
            val rightNal = right.vcl[index]
            require(leftNal.type == rightNal.type &&
                leftNal.layerId == rightNal.layerId &&
                leftNal.temporalIdPlus1 == rightNal.temporalIdPlus1) {
                "NAL header mismatch at picture $index"
            }

            val first = HevcSliceHeaderParser.parse(leftNal.bytes, sps, pps)
            val second = HevcSliceHeaderParser.parse(rightNal.bytes, sps, pps)
            require(first.firstSliceSegmentInPicture && !second.firstSliceSegmentInPicture) {
                "Picture $index does not contain the expected first/continuation tile slices"
            }
            require(!first.dependentSliceSegment && !second.dependentSliceSegment) {
                "Dependent tile slice segments are not supported"
            }
            require(first.sliceSegmentAddress == 0 &&
                second.sliceSegmentAddress == expectedRightAddress) {
                "Unexpected tile slice addresses at picture $index: " +
                    "${first.sliceSegmentAddress},${second.sliceSegmentAddress}"
            }
            require(first.ppsId == second.ppsId &&
                first.noOutputOfPriorPictures == second.noOutputOfPriorPictures &&
                first.sliceType == second.sliceType &&
                first.picOutputFlag == second.picOutputFlag &&
                first.colourPlaneId == second.colourPlaneId &&
                first.picOrderCntLsb == second.picOrderCntLsb &&
                first.shortTermRpsFromSps == second.shortTermRpsFromSps &&
                first.shortTermRpsIndex == second.shortTermRpsIndex &&
                first.references == second.references &&
                first.temporalMvpEnabled == second.temporalMvpEnabled &&
                first.sampleAdaptiveOffsetLuma == second.sampleAdaptiveOffsetLuma &&
                first.sampleAdaptiveOffsetChroma == second.sampleAdaptiveOffsetChroma) {
                "Picture identity or reference syntax mismatch at picture $index"
            }

            appendNal(output, leftNal.bytes)
            appendNal(output, rightNal.bytes)
            addresses += first.sliceSegmentAddress to second.sliceSegmentAddress
        }

        return AssemblyResult(
            annexB = output.toByteArray(),
            pictureCount = left.vcl.size,
            tileSlicesPerPicture = 2,
            sliceSegmentAddresses = addresses
        )
    }

    private data class ParsedFeed(
        val parameterSets: List<HevcBitstream.NalUnit>,
        val vcl: List<HevcBitstream.NalUnit>,
        val sps: HevcParameterSets.SpsMetadata,
        val pps: HevcParameterSets.PpsMetadata
    )

    private fun parseFeed(label: String, bytes: ByteArray): ParsedFeed {
        val nals = HevcBitstream.splitAnnexB(bytes)
        require(nals.all { it.layerId == 0 }) { "$label feed contains a non-zero HEVC layer ID" }
        val parameterSets = nals.filter { it.type in 32..34 }
        val vcl = nals.filter { it.type in 0..31 }
        val unsupported = nals.filter { it.type !in 0..31 && it.type !in 32..34 }
        require(unsupported.isEmpty()) {
            "$label feed contains unsupported non-VCL NAL types: ${unsupported.map { it.type }.distinct()}"
        }
        require(vcl.isNotEmpty()) { "$label feed contains no VCL slices" }
        require(parameterSets.count { it.type == 32 } == 1 &&
            parameterSets.count { it.type == 33 } == 1 &&
            parameterSets.count { it.type == 34 } == 1) {
            "$label feed must contain exactly one VPS, SPS and PPS"
        }
        val firstVclIndex = nals.indexOfFirst { it.type in 0..31 }
        require(nals.withIndex().filter { it.value.type in 32..34 }
            .all { it.index < firstVclIndex }) {
            "$label feed parameter sets must precede all coded pictures"
        }

        val vpsNal = parameterSets.single { it.type == 32 }
        val spsNal = parameterSets.single { it.type == 33 }
        val ppsNal = parameterSets.single { it.type == 34 }
        val vps = HevcParameterSets.parseVps(vpsNal.bytes)
        val sps = HevcParameterSets.parseSps(spsNal.bytes)
        require(sps.vpsId == vps.vpsId) {
            "$label SPS references VPS ${sps.vpsId}, but supplied VPS is ${vps.vpsId}"
        }
        require(sps.maxSubLayers <= vps.maxSubLayers) {
            "$label SPS sub-layer count exceeds its VPS limit"
        }
        val pps = HevcParameterSets.parsePps(ppsNal.bytes, sps)
        require(pps.spsId == sps.spsId) {
            "$label PPS references SPS ${pps.spsId}, but supplied SPS is ${sps.spsId}"
        }
        return ParsedFeed(parameterSets, vcl, sps, pps)
    }

    private fun appendNal(output: ByteArrayOutputStream, nal: ByteArray) {
        output.write(byteArrayOf(0, 0, 0, 1))
        output.write(nal)
    }
}
