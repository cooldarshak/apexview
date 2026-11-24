package app.apexview.tiled

import java.io.ByteArrayOutputStream

/**
 * Reassembles aligned synthetic HEVC tile streams without rewriting slice headers or CABAC data.
 *
 * This POC accepts one independent slice per tile and only 2x1 or 2x2 uniform full-picture
 * grids. Annex-B carries no timestamps: callers must establish sample/time alignment separately.
 * This is a bitstream proof, not the production F1 CMAF compositor.
 */
internal object HevcTileStreamAssembler {
    data class AssemblyResult(
        val annexB: ByteArray,
        val pictureCount: Int,
        val tileSlicesPerPicture: Int,
        val sliceSegmentAddresses: List<Pair<Int, Int>>,
        val tileSliceSegmentAddresses: List<List<Int>> = emptyList()
    )

    fun assembleTwoTileStreams(leftAnnexB: ByteArray, rightAnnexB: ByteArray): AssemblyResult =
        assembleTileStreams(listOf(leftAnnexB, rightAnnexB), columns = 2, rows = 1)

    fun assembleFourTileStreams(tileStreams: List<ByteArray>): AssemblyResult =
        assembleTileStreams(tileStreams, columns = 2, rows = 2)

    private fun assembleTileStreams(
        tileStreams: List<ByteArray>,
        columns: Int,
        rows: Int
    ): AssemblyResult {
        require(columns == 2 && rows in 1..2) { "Only synthetic 2x1 and 2x2 grids are supported" }
        require(tileStreams.size == columns * rows) {
            "Expected ${columns * rows} tile streams, received ${tileStreams.size}"
        }
        val feeds = tileStreams.mapIndexed { index, bytes -> parseFeed("tile-$index", bytes) }
        val firstFeed = feeds.first()

        feeds.drop(1).forEachIndexed { index, feed ->
            require(firstFeed.parameterSets.map { it.bytes.toList() } ==
                feed.parameterSets.map { it.bytes.toList() }) {
                "Tile feeds do not carry byte-identical VPS/SPS/PPS"
            }
            require(firstFeed.sps == feed.sps && firstFeed.pps == feed.pps) {
                "Tile feeds resolve to different parsed SPS/PPS metadata"
            }
            require(firstFeed.vcl.size == feed.vcl.size) {
                "Tile feed ${index + 1} has a different picture count"
            }
        }
        require(firstFeed.vcl.isNotEmpty()) { "Tile feeds contain no pictures" }

        val sps = firstFeed.sps
        val pps = firstFeed.pps
        require(sps.width > 0 && sps.height > 0) { "Invalid SPS picture dimensions" }
        require(sps.spsExtensionPresent == false) {
            "SPS extensions are outside the supported synthetic profile"
        }
        require(pps.tilesEnabled && pps.numTileColumns == columns && pps.numTileRows == rows) {
            "Expected a ${columns}x${rows} HEVC tile grid"
        }
        require(pps.uniformTileSpacing == true) { "Only uniform HEVC tile spacing is supported" }
        require(!pps.entropyCodingSyncEnabled) { "Wavefront entropy coding is not supported" }
        require(!pps.weightedPredictionEnabled && !pps.weightedBipredictionEnabled) {
            "Weighted prediction is not supported"
        }
        require(pps.deblockingFilterOverrideEnabled != true) {
            "Per-slice deblocking overrides are not supported by this synthetic assembler"
        }
        require(!pps.scalingListDataPresent && !pps.listsModificationPresent &&
            !pps.sliceSegmentHeaderExtensionPresent && !pps.extensionPresent) {
            "PPS contains unsupported extension or slice-header tools"
        }
        val columnWidths = requireNotNull(pps.tileColumnWidthsInCtbs) {
            "PPS tile column geometry is missing"
        }
        val rowHeights = requireNotNull(pps.tileRowHeightsInCtbs) {
            "PPS tile row geometry is missing"
        }
        require(columnWidths.size == columns && rowHeights.size == rows) {
            "Parsed tile dimensions do not match the requested grid"
        }
        require(columnWidths.distinct().size == 1 && rowHeights.distinct().size == 1 &&
            columnWidths.sum() == sps.picWidthInCtbs &&
            rowHeights.sum() == sps.picHeightInCtbs) {
            "Only uniform, full-picture CTB tile layouts are supported"
        }

        val columnOffsets = columnWidths.scan(0) { total, width -> total + width }.dropLast(1)
        val rowOffsets = rowHeights.scan(0) { total, height -> total + height }.dropLast(1)
        val expectedAddresses = buildList {
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    add(rowOffsets[row] * sps.picWidthInCtbs + columnOffsets[column])
                }
            }
        }

        val output = ByteArrayOutputStream()
        firstFeed.parameterSets.forEach { appendNal(output, it.bytes) }
        val pairedAddresses = ArrayList<Pair<Int, Int>>(if (rows == 1) firstFeed.vcl.size else 0)
        val allAddresses = ArrayList<List<Int>>(firstFeed.vcl.size)

        for (pictureIndex in firstFeed.vcl.indices) {
            val nals = feeds.map { it.vcl[pictureIndex] }
            val headers = nals.map { HevcSliceHeaderParser.parse(it.bytes, sps, pps) }
            val referenceHeader = headers.first()
            nals.drop(1).forEachIndexed { tileIndex, nal ->
                require(nal.type == nals.first().type &&
                    nal.layerId == nals.first().layerId &&
                    nal.temporalIdPlus1 == nals.first().temporalIdPlus1) {
                    "NAL header mismatch at picture $pictureIndex tile ${tileIndex + 1}"
                }
            }
            require(referenceHeader.firstSliceSegmentInPicture &&
                headers.drop(1).none { it.firstSliceSegmentInPicture }) {
                "Picture $pictureIndex has invalid first/continuation tile slice flags"
            }
            headers.forEachIndexed { tileIndex, header ->
                require(!header.dependentSliceSegment) {
                    "Dependent tile slice segments are not supported"
                }
                require(header.sliceSegmentAddress == expectedAddresses[tileIndex]) {
                    "Unexpected tile slice address at picture $pictureIndex tile $tileIndex: " +
                        "${header.sliceSegmentAddress}, expected ${expectedAddresses[tileIndex]}"
                }
                require(header.entryPointOffsets.isEmpty()) {
                    "Entry-point offsets inside a tile slice are not supported"
                }
                require(samePictureSyntax(referenceHeader, header)) {
                    "Picture identity or reference syntax mismatch at picture $pictureIndex tile $tileIndex"
                }
            }
            nals.forEach { appendNal(output, it.bytes) }
            val pictureAddresses = headers.map { it.sliceSegmentAddress }
            allAddresses += pictureAddresses
            if (rows == 1) pairedAddresses += pictureAddresses[0] to pictureAddresses[1]
        }

        return AssemblyResult(
            annexB = output.toByteArray(),
            pictureCount = firstFeed.vcl.size,
            tileSlicesPerPicture = columns * rows,
            sliceSegmentAddresses = pairedAddresses,
            tileSliceSegmentAddresses = allAddresses
        )
    }

    private fun samePictureSyntax(a: HevcSliceHeaderParser.SliceHeader, b: HevcSliceHeaderParser.SliceHeader): Boolean =
        a.ppsId == b.ppsId &&
            a.noOutputOfPriorPictures == b.noOutputOfPriorPictures &&
            a.sliceType == b.sliceType &&
            a.picOutputFlag == b.picOutputFlag &&
            a.colourPlaneId == b.colourPlaneId &&
            a.picOrderCntLsb == b.picOrderCntLsb &&
            a.shortTermRpsFromSps == b.shortTermRpsFromSps &&
            a.shortTermRpsIndex == b.shortTermRpsIndex &&
            a.references == b.references &&
            a.temporalMvpEnabled == b.temporalMvpEnabled &&
            a.sampleAdaptiveOffsetLuma == b.sampleAdaptiveOffsetLuma &&
            a.sampleAdaptiveOffsetChroma == b.sampleAdaptiveOffsetChroma

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
