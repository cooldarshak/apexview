package app.apexview.tiled

import java.io.ByteArrayOutputStream

/**
 * Native Kotlin port of the per-cell HEVC stitching strategy used by F1 TV Tizen.
 *
 * Reference: https://github.com/nicbra/f1tv-tizen/blob/master/app/service/stitch-hevc.js
 * The source project is MIT licensed. Its copyright and licence notice are retained in
 * LICENSES/F1-TV-Tizen-MIT.txt. This implementation uses the repository's bounded parsers
 * and rewriter; CABAC data is never decoded or regenerated.
 *
 * The source feed keeps its own VPS/SPS/PPS and tile geometry. The output gets a newly generated
 * parameter-set triplet and a new tile per output cell. A source cell is accepted only when one
 * independent source slice exactly covers its selected source tile.
 */
internal object HevcTizenGridComposer {
    data class CellSelection(
        val feedIndex: Int,
        val sourceTileIndex: Int,
        val outputColumn: Int,
        val outputRow: Int
    )

    data class SliceEvidence(
        val feedIndex: Int,
        val sourceTileIndex: Int,
        val sourceCtbAddress: Int,
        val destinationCtbAddress: Int,
        val sourceHeaderBytes: Int,
        val outputHeaderBytes: Int,
        val copiedTailBytes: Int,
        val loopFilterAcrossSlices: Boolean?
    )

    data class ComposedPicture(
        val frameIndex: Int,
        val fullPoc: Long,
        val sampleData: ByteArray,
        val vclNals: List<ByteArray>,
        val evidence: List<SliceEvidence>
    )

    data class Result(
        val outputParameters: HevcOutputParameterSetWriter.Output,
        val codedWidth: Int,
        val codedHeight: Int,
        val displayWidth: Int,
        val displayHeight: Int,
        val tileColumnWidthsInCtbs: List<Int>,
        val tileRowHeightsInCtbs: List<Int>,
        val pictures: List<ComposedPicture>,
        val annexB: ByteArray,
        val outputLoopFilterAcrossTilesEnabled: Boolean
    )

    private data class ParsedFeed(
        val vpsNal: ByteArray,
        val spsNal: ByteArray,
        val ppsNal: ByteArray,
        val vps: HevcParameterSets.VpsMetadata,
        val sps: HevcParameterSets.SpsMetadata,
        val pps: HevcParameterSets.PpsMetadata,
        val pictures: List<List<ByteArray>>,
        val pictureIdentities: List<HevcPictureIdentityCompatibility.PictureIdentity>
    )

    private data class SourceTile(
        val index: Int,
        val column: Int,
        val row: Int,
        val leftCtb: Int,
        val topCtb: Int,
        val widthInCtbs: Int,
        val heightInCtbs: Int,
        val startAddress: Int
    )

    private data class LocatedSlice(
        val nal: ByteArray,
        val header: HevcSliceHeaderParser.SliceHeader,
        val tileIndex: Int,
        val expectedStartAddress: Int
    )

    fun composeAnnexBStreams(
        feedStreams: List<ByteArray>,
        grid: HevcOutputGridDimensions,
        cells: List<CellSelection>,
        frameRateNumerator: Int = 50,
        frameRateDenominator: Int = 1,
        outputNalLengthSize: Int = 4
    ): Result {
        require(feedStreams.size >= 2) { "At least two independent source feeds are required" }
        require(outputNalLengthSize in 1..4) { "Output NAL length size must be between 1 and 4" }
        val feeds = feedStreams.mapIndexed { index, bytes ->
            parseFeed(index, bytes)
        }
        val first = feeds.first()
        require(feeds.all { it.pictures.size == first.pictures.size }) {
            "Source feeds contain different coded-picture counts"
        }
        require(first.pictures.isNotEmpty()) { "Source feeds contain no coded pictures" }

        val referenceIdentities = first.pictureIdentities
        feeds.drop(1).forEachIndexed { index, feed ->
            val comparison = HevcPictureIdentityCompatibility.compare(
                referenceIdentities, feed.pictureIdentities
            )
            require(comparison.compatible) {
                "Source feed ${index + 1} picture identity is not aligned: ${comparison.reasons.joinToString()}"
            }
        }

        val placements = cells.map { selection ->
            require(selection.feedIndex in feeds.indices) {
                "Output cell references missing feed ${selection.feedIndex}"
            }
            val feed = feeds[selection.feedIndex]
            val tile = sourceTile(feed.sps, feed.pps, selection.sourceTileIndex)
            val sourcePicture = HevcSourcePicture(
                feed = HevcSourceFeedIdentity("feed-${selection.feedIndex}"),
                geometry = HevcSourcePictureGeometry.from(feed.sps),
                parameterSets = HevcSourceParameterSets(feed.vps, feed.sps, feed.pps)
            )
            HevcOutputCellPlacement(
                sourceCell = HevcSourceCellSelection(
                    picture = sourcePicture,
                    leftCtb = tile.leftCtb,
                    topCtb = tile.topCtb,
                    widthInCtbs = tile.widthInCtbs,
                    heightInCtbs = tile.heightInCtbs
                ),
                column = selection.outputColumn,
                row = selection.outputRow
            )
        }
        val gridResult = HevcOutputGridValidator.validate(grid, placements)
        require(gridResult.compatible && gridResult.geometry != null) {
            "Output cell layout is not compatible: ${gridResult.reasons.joinToString()}"
        }
        val geometry = requireNotNull(gridResult.geometry)
        val orderedSelections = cells.sortedWith(
            compareBy<CellSelection> { it.outputRow }.thenBy { it.outputColumn }
        )
        require(orderedSelections.size == grid.columns * grid.rows) {
            "Output grid does not contain exactly one source selection per cell"
        }

        val selectedByFrame = feeds.first().pictures.indices.map { frameIndex ->
            orderedSelections.map { selection ->
                val feed = feeds[selection.feedIndex]
                val tiles = locatePictureTiles(feed, feed.pictures[frameIndex])
                val selected = tiles.singleOrNull { it.tileIndex == selection.sourceTileIndex }
                    ?: throw IllegalArgumentException(
                        "Frame $frameIndex feed ${selection.feedIndex} must have exactly one independent slice for tile ${selection.sourceTileIndex}"
                    )
                val signature = feed.pictureIdentities[frameIndex]
                val cellIdentity = HevcPictureIdentityCompatibility.identify(
                    selected.header, signature.pictureOrderCount
                )
                val baseline = HevcPictureIdentityCompatibility.compare(
                    listOf(signature), listOf(cellIdentity)
                )
                require(baseline.compatible) {
                    "Frame $frameIndex feed ${selection.feedIndex} tile ${selection.sourceTileIndex} does not describe the feed's picture identity: ${baseline.reasons.joinToString()}"
                }
                selected
            }
        }

        // The output PPS flag is global. Enable filtering across output tile seams only
        // when every seam corresponds to adjacent source tiles from the same source feed.
        // Independent full-frame feeds are never filtered into one another at their boundary.
        val allOutputTileSeamsAreSourceTileSeams = orderedSelections.indices.all { outputIndex ->
            hasOwnLeftAndTopEdges(orderedSelections, outputIndex, feeds)
        }
        val canGateLoopFilter = allOutputTileSeamsAreSourceTileSeams &&
            selectedByFrame.flatten().withIndex().all { (flatIndex, selected) ->
                val selection = orderedSelections[flatIndex % orderedSelections.size]
                val feed = feeds[selection.feedIndex]
                feed.pps.tilesEnabled &&
                    feed.pps.loopFilterAcrossTiles == true &&
                    feed.pps.loopFilterAcrossSlicesEnabled &&
                    selected.header.loopFilterAcrossSlicesFieldBit != null
            }
        val templateFeed = feeds[orderedSelections.first().feedIndex]
        val generated = HevcOutputParameterSetWriter.generate(
            sourceVpsNal = templateFeed.vpsNal,
            sourceSpsNal = templateFeed.spsNal,
            sourcePpsNal = templateFeed.ppsNal,
            request = HevcOutputParameterSetWriter.Request(
                vpsId = 0,
                spsId = 0,
                ppsId = 0,
                codedWidth = geometry.codedWidth,
                codedHeight = geometry.codedHeight,
                tileColumnWidthsInCtbs = geometry.tileColumnWidthsInCtbs,
                tileRowHeightsInCtbs = geometry.tileRowHeightsInCtbs,
                loopFilterAcrossTilesEnabled = canGateLoopFilter,
                frameRateNumerator = frameRateNumerator,
                frameRateDenominator = frameRateDenominator
            )
        )
        val outputSps = HevcParameterSets.parseSps(generated.sps)
        val outputPps = HevcParameterSets.parsePps(generated.pps, outputSps)

        val outputPictures = selectedByFrame.mapIndexed { frameIndex, selectedSlices ->
            val vclOutput = ArrayList<ByteArray>(orderedSelections.size)
            val evidence = ArrayList<SliceEvidence>(orderedSelections.size)
            selectedSlices.forEachIndexed { outputIndex, located ->
                val selection = orderedSelections[outputIndex]
                val feed = feeds[selection.feedIndex]
                val outputAddress = destinationAddress(
                    geometry, selection.outputColumn, selection.outputRow
                )
                val ownEdges = hasOwnLeftAndTopEdges(orderedSelections, outputIndex, feeds)
                val filterOverride = if (canGateLoopFilter) {
                    located.header.loopFilterAcrossSlices == true && ownEdges
                } else null
                val rewritten = HevcSliceHeaderRewriter.rewrite(
                    nal = located.nal,
                    sourceSps = feed.sps,
                    sourcePps = feed.pps,
                    outputSps = outputSps,
                    outputPps = outputPps,
                    destinationAddress = outputAddress,
                    clearNalBytes = located.nal.size,
                    loopFilterAcrossSlicesOverride = filterOverride
                )
                val verifiedHeader = HevcSliceHeaderParser.parse(
                    rewritten.nal, outputSps, outputPps
                )
                require(verifiedHeader.sliceSegmentAddress == outputAddress) {
                    "Output slice address failed round-trip verification"
                }
                require(verifiedHeader.firstSliceSegmentInPicture == (outputAddress == 0)) {
                    "Output first-slice flag does not match its output CTB address"
                }
                vclOutput += rewritten.nal
                evidence += SliceEvidence(
                    feedIndex = selection.feedIndex,
                    sourceTileIndex = selection.sourceTileIndex,
                    sourceCtbAddress = located.header.sliceSegmentAddress,
                    destinationCtbAddress = rewritten.destinationAddress,
                    sourceHeaderBytes = rewritten.sourceHeaderBytes,
                    outputHeaderBytes = rewritten.outputHeaderBytes,
                    copiedTailBytes = rewritten.copiedTailBytes,
                    loopFilterAcrossSlices = verifiedHeader.loopFilterAcrossSlices
                )
            }
            require(vclOutput.isNotEmpty()) { "Composed output picture contains no VCL NAL units" }
            ComposedPicture(
                frameIndex = frameIndex,
                fullPoc = feeds.first().pictureIdentities[frameIndex].pictureOrderCount,
                sampleData = lengthPrefix(vclOutput, outputNalLengthSize),
                vclNals = vclOutput,
                evidence = evidence
            )
        }

        val outputStream = ByteArrayOutputStream()
        listOf(generated.vps, generated.sps, generated.pps).forEach {
            writeAnnexB(outputStream, it)
        }
        outputPictures.forEach { picture ->
            picture.vclNals.forEach { writeAnnexB(outputStream, it) }
        }

        return Result(
            outputParameters = generated,
            codedWidth = outputSps.width,
            codedHeight = outputSps.height,
            displayWidth = outputSps.displayWidth,
            displayHeight = outputSps.displayHeight,
            tileColumnWidthsInCtbs = generated.tileColumnWidthsInCtbs,
            tileRowHeightsInCtbs = generated.tileRowHeightsInCtbs,
            pictures = outputPictures,
            annexB = outputStream.toByteArray(),
            outputLoopFilterAcrossTilesEnabled = outputPps.loopFilterAcrossTiles == true
        )
    }

    private fun parseFeed(index: Int, annexB: ByteArray): ParsedFeed {
        val nals = HevcBitstream.splitAnnexB(annexB)
        require(nals.all { it.layerId == 0 }) {
            "Source feed $index contains a non-zero HEVC layer ID"
        }
        val vpsUnits = nals.filter { it.type == 32 }
        val spsUnits = nals.filter { it.type == 33 }
        val ppsUnits = nals.filter { it.type == 34 }
        require(vpsUnits.isNotEmpty() && spsUnits.isNotEmpty() && ppsUnits.isNotEmpty()) {
            "Source feed $index must contain VPS, SPS and PPS NAL units"
        }
        val vps = HevcParameterSets.parseVps(vpsUnits.first().bytes)
        val sps = HevcParameterSets.parseSps(spsUnits.first().bytes)
        val pps = HevcParameterSets.parsePps(ppsUnits.first().bytes, sps)
        require(vpsUnits.all { HevcParameterSets.parseVps(it.bytes) == vps }) {
            "Source feed $index changes its VPS during the supplied stream"
        }
        require(spsUnits.all { HevcParameterSets.parseSps(it.bytes) == sps }) {
            "Source feed $index changes its SPS during the supplied stream"
        }
        require(ppsUnits.all { HevcParameterSets.parsePps(it.bytes, sps) == pps }) {
            "Source feed $index changes its PPS during the supplied stream"
        }
        require(sps.vpsId == vps.vpsId && pps.spsId == sps.spsId) {
            "Source feed $index has inconsistent VPS/SPS/PPS references"
        }
        require(vps.maxLayers == 1 && !vps.extensionPresent && !sps.spsExtensionPresent &&
            !pps.extensionPresent) {
            "Source feed $index uses unsupported parameter-set extensions"
        }

        val signatures = HevcStreamAlignmentAnalyzer.analyze(annexB)
        val pictureNals = mutableListOf<MutableList<ByteArray>>()
        var current: MutableList<ByteArray>? = null
        nals.filter { it.type in 0..31 }.forEach { nal ->
            val header = HevcSliceHeaderParser.parse(nal.bytes, sps, pps)
            if (header.firstSliceSegmentInPicture) {
                current?.let { pictureNals += it }
                current = mutableListOf()
            }
            val picture = current
                ?: throw IllegalArgumentException("Source feed $index has a continuation slice before its first slice")
            picture += nal.bytes
        }
        current?.let { pictureNals += it }
        require(pictureNals.size == signatures.size && signatures.isNotEmpty()) {
            "Source feed $index picture grouping disagrees with the POC analyzer"
        }
        val identities = pictureNals.mapIndexed { frameIndex, picture ->
            val firstHeader = HevcSliceHeaderParser.parse(picture.first(), sps, pps)
            require(!firstHeader.dependentSliceSegment) {
                "Source feed $index frame $frameIndex begins with a dependent slice"
            }
            HevcPictureIdentityCompatibility.identify(
                firstHeader, signatures[frameIndex].poc
            )
        }
        return ParsedFeed(
            vpsUnits.first().bytes, spsUnits.first().bytes, ppsUnits.first().bytes,
            vps, sps, pps, pictureNals, identities
        )
    }

    private fun sourceTile(
        sps: HevcParameterSets.SpsMetadata,
        pps: HevcParameterSets.PpsMetadata,
        tileIndex: Int
    ): SourceTile {
        val columns = requireNotNull(pps.tileColumnWidthsInCtbs) {
            "Source PPS tile-column geometry is unavailable"
        }
        val rows = requireNotNull(pps.tileRowHeightsInCtbs) {
            "Source PPS tile-row geometry is unavailable"
        }
        require(columns.size == pps.numTileColumns && rows.size == pps.numTileRows) {
            "Source PPS tile dimensions do not match its tile counts"
        }
        require(tileIndex in 0 until columns.size * rows.size) {
            "Selected source tile index $tileIndex is outside the source PPS grid"
        }
        val column = tileIndex % columns.size
        val row = tileIndex / columns.size
        val leftCtb = columns.take(column).sum()
        val topCtb = rows.take(row).sum()
        val startAddress = Math.addExact(
            Math.multiplyExact(topCtb, sps.picWidthInCtbs),
            leftCtb
        )
        return SourceTile(
            index = tileIndex,
            column = column,
            row = row,
            leftCtb = leftCtb,
            topCtb = topCtb,
            widthInCtbs = columns[column],
            heightInCtbs = rows[row],
            startAddress = startAddress
        )
    }

    private fun locatePictureTiles(
        feed: ParsedFeed,
        picture: List<ByteArray>
    ): List<LocatedSlice> {
        val allTiles = mutableListOf<LocatedSlice>()
        val seen = mutableSetOf<Int>()
        picture.forEach { nal ->
            val header = HevcSliceHeaderParser.parse(nal, feed.sps, feed.pps)
            require(!header.dependentSliceSegment) {
                "Dependent source slice segments are not supported"
            }
            require(header.entryPointOffsets.isEmpty()) {
                "A source slice spans multiple tiles or carries unsupported entry-point offsets"
            }
            val tile = tileAtAddress(feed.sps, feed.pps, header.sliceSegmentAddress)
            require(tile.startAddress == header.sliceSegmentAddress) {
                "Source slice does not begin at a complete tile boundary"
            }
            require(header.firstSliceSegmentInPicture == (header.sliceSegmentAddress == 0)) {
                "Source first-slice flag does not match its CTB address"
            }
            require(seen.add(tile.index)) {
                "Source picture contains multiple independent slices in tile ${tile.index}"
            }
            allTiles += LocatedSlice(nal, header, tile.index, tile.startAddress)
        }
        return allTiles
    }

    private fun tileAtAddress(
        sps: HevcParameterSets.SpsMetadata,
        pps: HevcParameterSets.PpsMetadata,
        address: Int
    ): SourceTile {
        val columns = requireNotNull(pps.tileColumnWidthsInCtbs)
        val rows = requireNotNull(pps.tileRowHeightsInCtbs)
        val pictureCtbs = Math.multiplyExact(sps.picWidthInCtbs, sps.picHeightInCtbs)
        require(address in 0 until pictureCtbs) { "Source slice address exceeds source picture" }
        val rasterRow = address / sps.picWidthInCtbs
        val rasterColumn = address % sps.picWidthInCtbs
        var left = 0
        val column = columns.indexOfFirst { width ->
            val inColumn = rasterColumn >= left && rasterColumn < left + width
            if (!inColumn) left += width
            inColumn
        }
        var top = 0
        val row = rows.indexOfFirst { height ->
            val inRow = rasterRow >= top && rasterRow < top + height
            if (!inRow) top += height
            inRow
        }
        require(column >= 0 && row >= 0) { "Source CTB address is outside the PPS tile partition" }
        val tileIndex = row * columns.size + column
        return sourceTile(sps, pps, tileIndex)
    }

    private fun destinationAddress(
        geometry: HevcOutputPictureGeometry,
        column: Int,
        row: Int
    ): Int {
        require(column in 0 until geometry.grid.columns && row in 0 until geometry.grid.rows)
        val left = geometry.tileColumnWidthsInCtbs.take(column).sum()
        val top = geometry.tileRowHeightsInCtbs.take(row).sum()
        return Math.addExact(
            Math.multiplyExact(top, geometry.pictureWidthInCtbs),
            left
        )
    }

    private fun hasOwnLeftAndTopEdges(
        selections: List<CellSelection>,
        outputIndex: Int,
        feeds: List<ParsedFeed>
    ): Boolean {
        val current = selections[outputIndex]
        val currentFeed = feeds[current.feedIndex]
        val currentTile = sourceTile(
            currentFeed.sps, currentFeed.pps, current.sourceTileIndex
        )
        val leftOwn = current.outputColumn == 0 || (
            currentTile.column > 0 &&
                selections.any { candidate ->
                    candidate.outputColumn == current.outputColumn - 1 &&
                        candidate.outputRow == current.outputRow &&
                        candidate.feedIndex == current.feedIndex &&
                        candidate.sourceTileIndex == current.sourceTileIndex - 1
                }
            )
        val topOwn = current.outputRow == 0 || (
            currentTile.row > 0 &&
                selections.any { candidate ->
                    candidate.outputColumn == current.outputColumn &&
                        candidate.outputRow == current.outputRow - 1 &&
                        candidate.feedIndex == current.feedIndex &&
                        candidate.sourceTileIndex == current.sourceTileIndex -
                            currentFeed.pps.numTileColumns
                }
            )
        return leftOwn && topOwn
    }

    private fun lengthPrefix(nals: List<ByteArray>, lengthSize: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val maxLength = if (lengthSize == 4) 0xffff_ffffL else (1L shl (lengthSize * 8)) - 1L
        nals.forEach { nal ->
            require(nal.isNotEmpty() && nal.size.toLong() <= maxLength) {
                "NAL size exceeds the configured CMAF length-prefix field"
            }
            for (shift in (lengthSize - 1) downTo 0) {
                output.write((nal.size ushr (shift * 8)) and 0xff)
            }
            output.write(nal)
        }
        return output.toByteArray()
    }

    private fun writeAnnexB(output: ByteArrayOutputStream, nal: ByteArray) {
        output.write(byteArrayOf(0, 0, 0, 1))
        output.write(nal)
    }
}
