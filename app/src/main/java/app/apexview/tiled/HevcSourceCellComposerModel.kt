package app.apexview.tiled

/**
 * Immutable description of a source picture and where a rectangular CTB-aligned cell from it
 * will be placed in a newly generated output picture. Source PPS tile declarations remain source
 * metadata; they are never used as the output-grid declaration.
 */
internal data class HevcSourceFeedIdentity(val value: String)

internal data class HevcSourcePictureGeometry(
    val codedWidth: Int,
    val codedHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val ctbSize: Int,
    val pictureWidthInCtbs: Int,
    val pictureHeightInCtbs: Int,
    val conformanceWindowLeft: Int,
    val conformanceWindowRight: Int,
    val conformanceWindowTop: Int,
    val conformanceWindowBottom: Int
) {
    companion object {
        fun from(sps: HevcParameterSets.SpsMetadata) = HevcSourcePictureGeometry(
            codedWidth = sps.width,
            codedHeight = sps.height,
            displayWidth = sps.displayWidth,
            displayHeight = sps.displayHeight,
            ctbSize = sps.ctbSize,
            pictureWidthInCtbs = sps.picWidthInCtbs,
            pictureHeightInCtbs = sps.picHeightInCtbs,
            conformanceWindowLeft = sps.conformanceWindowLeft,
            conformanceWindowRight = sps.conformanceWindowRight,
            conformanceWindowTop = sps.conformanceWindowTop,
            conformanceWindowBottom = sps.conformanceWindowBottom
        )
    }
}

internal data class HevcSourceParameterSets(
    val vps: HevcParameterSets.VpsMetadata,
    val sps: HevcParameterSets.SpsMetadata,
    val pps: HevcParameterSets.PpsMetadata
)

internal data class HevcSourcePicture(
    val feed: HevcSourceFeedIdentity,
    val geometry: HevcSourcePictureGeometry,
    val parameterSets: HevcSourceParameterSets
)

internal data class HevcSourceCellSelection(
    val picture: HevcSourcePicture,
    val leftCtb: Int,
    val topCtb: Int,
    val widthInCtbs: Int,
    val heightInCtbs: Int
)

internal data class HevcOutputGridDimensions(val columns: Int, val rows: Int)

internal data class HevcOutputCellPlacement(
    val sourceCell: HevcSourceCellSelection,
    val column: Int,
    val row: Int
)

internal data class HevcOutputPictureGeometry(
    val grid: HevcOutputGridDimensions,
    val codedWidth: Int,
    val codedHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val ctbSize: Int,
    val pictureWidthInCtbs: Int,
    val pictureHeightInCtbs: Int,
    val tileColumnWidthsInCtbs: List<Int>,
    val tileRowHeightsInCtbs: List<Int>
)

/** Compatibility of two selected source picture regions, not compatibility of output PPS tiles. */
internal object HevcSourceCellCompatibility {
    data class Result(val compatible: Boolean, val reasons: List<String>)

    private data class CellMetrics(
        val pixelWidth: Int,
        val pixelHeight: Int,
        val widthInCtbs: Int,
        val heightInCtbs: Int,
        val ctbSize: Int
    )

    fun compare(
        first: HevcSourceCellSelection,
        second: HevcSourceCellSelection
    ): Result {
        val reasons = mutableListOf<String>()
        reasons += validateSelection(first, "First")
        reasons += validateSelection(second, "Second")

        val firstSets = first.picture.parameterSets
        val secondSets = second.picture.parameterSets
        reasons += HevcParameterSetCompatibility.compare(
            firstSets.vps, firstSets.sps, firstSets.pps,
            secondSets.vps, secondSets.sps, secondSets.pps
        ).reasons

        val firstMetrics = metrics(first)
        val secondMetrics = metrics(second)
        if (firstMetrics != null && secondMetrics != null) {
            if (firstMetrics.ctbSize != secondMetrics.ctbSize) {
                reasons += "Source cells use different CTB sizes"
            }
            if (firstMetrics.widthInCtbs != secondMetrics.widthInCtbs ||
                firstMetrics.heightInCtbs != secondMetrics.heightInCtbs) {
                reasons += "Source cells have different CTB dimensions"
            }
            if (firstMetrics.pixelWidth != secondMetrics.pixelWidth ||
                firstMetrics.pixelHeight != secondMetrics.pixelHeight) {
                reasons += "Source cells have different coded pixel dimensions"
            }
        }

        return Result(reasons.isEmpty(), reasons.distinct())
    }

    internal fun validateSelection(
        selection: HevcSourceCellSelection,
        label: String = "Source"
    ): List<String> {
        val reasons = mutableListOf<String>()
        val picture = selection.picture
        val geometry = picture.geometry
        val sets = picture.parameterSets
        val sps = sets.sps
        val pps = sets.pps

        if (picture.feed.value.isBlank()) reasons += "$label feed identity is empty"
        if (geometry != HevcSourcePictureGeometry.from(sps)) {
            reasons += "$label picture geometry does not match its parsed SPS"
        }
        if (geometry.codedWidth <= 0 || geometry.codedHeight <= 0 ||
            geometry.ctbSize <= 0 || geometry.pictureWidthInCtbs <= 0 ||
            geometry.pictureHeightInCtbs <= 0) {
            reasons += "$label picture geometry is invalid"
            return reasons.distinct()
        }

        val expectedWidthInCtbs =
            (geometry.codedWidth.toLong() + geometry.ctbSize - 1L) / geometry.ctbSize
        val expectedHeightInCtbs =
            (geometry.codedHeight.toLong() + geometry.ctbSize - 1L) / geometry.ctbSize
        if (expectedWidthInCtbs != geometry.pictureWidthInCtbs.toLong() ||
            expectedHeightInCtbs != geometry.pictureHeightInCtbs.toLong()) {
            reasons += "$label CTB dimensions do not cover the coded picture"
        }

        if (geometry.displayWidth <= 0 || geometry.displayHeight <= 0 ||
            geometry.displayWidth > geometry.codedWidth ||
            geometry.displayHeight > geometry.codedHeight) {
            reasons += "$label SPS display dimensions are invalid for source-cell mapping"
        }

        val right = selection.leftCtb.toLong() + selection.widthInCtbs.toLong()
        val bottom = selection.topCtb.toLong() + selection.heightInCtbs.toLong()
        if (selection.leftCtb < 0 || selection.topCtb < 0 ||
            selection.widthInCtbs <= 0 || selection.heightInCtbs <= 0 ||
            right > geometry.pictureWidthInCtbs.toLong() ||
            bottom > geometry.pictureHeightInCtbs.toLong()) {
            reasons += "$label source-cell CTB rectangle is outside the source picture"
            return reasons.distinct()
        }

        reasons += validateTileLayout(sps, pps, label)
        val columnWidths = pps.tileColumnWidthsInCtbs
        val rowHeights = pps.tileRowHeightsInCtbs
        if (columnWidths != null && rowHeights != null &&
            columnWidths.isNotEmpty() && rowHeights.isNotEmpty()) {
            val xBoundaries = boundaries(columnWidths)
            val yBoundaries = boundaries(rowHeights)
            if (selection.leftCtb !in xBoundaries || right.toInt() !in xBoundaries ||
                selection.topCtb !in yBoundaries || bottom.toInt() !in yBoundaries) {
                reasons += "$label source-cell rectangle does not align to complete source tile boundaries"
            }
        }

        return reasons.distinct()
    }

    /**
     * Validate the source PPS against its own SPS. A non-tiled PPS is represented as one full
     * picture cell. Tiled sources may use any valid parsed tile partition; the selected rectangle
     * must start and end on boundaries in that source partition.
     */
    internal fun validateTileLayout(
        sps: HevcParameterSets.SpsMetadata,
        pps: HevcParameterSets.PpsMetadata,
        label: String = "Source"
    ): List<String> {
        val reasons = mutableListOf<String>()
        if (sps.picWidthInCtbs <= 0 || sps.picHeightInCtbs <= 0) {
            reasons += "$label SPS has invalid CTB picture dimensions"
            return reasons
        }
        val columns = pps.tileColumnWidthsInCtbs
        val rows = pps.tileRowHeightsInCtbs
        if (columns == null || rows == null) {
            reasons += "$label PPS tile geometry is unavailable"
            return reasons
        }
        if (!pps.tilesEnabled) {
            if (pps.numTileColumns != 1 || pps.numTileRows != 1 ||
                columns != listOf(sps.picWidthInCtbs) ||
                rows != listOf(sps.picHeightInCtbs)) {
                reasons += "$label non-tiled PPS does not describe one complete source picture"
            }
            return reasons
        }

        if (pps.numTileColumns <= 0 || pps.numTileRows <= 0 ||
            columns.size != pps.numTileColumns || rows.size != pps.numTileRows) {
            reasons += "$label PPS tile counts do not match the parsed tile geometry"
            return reasons
        }
        if (columns.any { it <= 0 } || rows.any { it <= 0 }) {
            reasons += "$label PPS contains a non-positive tile dimension"
        }
        if (columns.sumOf { it.toLong() } != sps.picWidthInCtbs.toLong() ||
            rows.sumOf { it.toLong() } != sps.picHeightInCtbs.toLong()) {
            reasons += "$label PPS tile geometry does not cover its complete source picture"
        }
        if (pps.uniformTileSpacing == null) {
            reasons += "$label tiled PPS has no parsed tile-spacing mode"
        } else if (pps.uniformTileSpacing && (
                pps.explicitColumnWidthsMinus1.isNotEmpty() ||
                    pps.explicitRowHeightsMinus1.isNotEmpty()
                )) {
            reasons += "$label uniform tile spacing unexpectedly contains explicit dimensions"
        } else if (!pps.uniformTileSpacing && (
                pps.explicitColumnWidthsMinus1.size != pps.numTileColumns - 1 ||
                    pps.explicitRowHeightsMinus1.size != pps.numTileRows - 1
                )) {
            reasons += "$label explicit tile dimensions are incomplete"
        }
        if (pps.loopFilterAcrossTiles == null) {
            reasons += "$label tiled PPS loop-filter flag is unavailable"
        }
        return reasons.distinct()
    }

    private fun boundaries(lengths: List<Int>): Set<Int> {
        val result = linkedSetOf(0)
        var total = 0L
        lengths.forEach {
            total += it.toLong()
            if (total <= Int.MAX_VALUE) result += total.toInt()
        }
        return result
    }

    private fun metrics(selection: HevcSourceCellSelection): CellMetrics? {
        val g = selection.picture.geometry
        if (g.ctbSize <= 0 || selection.leftCtb < 0 || selection.topCtb < 0 ||
            selection.widthInCtbs <= 0 || selection.heightInCtbs <= 0) return null
        val left = selection.leftCtb.toLong() * g.ctbSize
        val top = selection.topCtb.toLong() * g.ctbSize
        val right = (selection.leftCtb.toLong() + selection.widthInCtbs) * g.ctbSize
        val bottom = (selection.topCtb.toLong() + selection.heightInCtbs) * g.ctbSize
        if (right <= left || bottom <= top) return null
        val pixelWidth = (minOf(g.codedWidth.toLong(), right) - left).coerceAtLeast(0L)
        val pixelHeight = (minOf(g.codedHeight.toLong(), bottom) - top).coerceAtLeast(0L)
        if (pixelWidth > Int.MAX_VALUE || pixelHeight > Int.MAX_VALUE) return null
        return CellMetrics(
            pixelWidth.toInt(), pixelHeight.toInt(),
            selection.widthInCtbs, selection.heightInCtbs, g.ctbSize
        )
    }

    internal fun cellMetricsForGrid(selection: HevcSourceCellSelection): List<Int>? {
        val value = metrics(selection) ?: return null
        if (value.pixelWidth <= 0 || value.pixelHeight <= 0) return null
        return listOf(value.pixelWidth, value.pixelHeight, value.widthInCtbs,
            value.heightInCtbs, value.ctbSize)
    }
}

/** Builds the generated output picture geometry independently of every source PPS tile grid. */
internal object HevcOutputGridValidator {
    data class Result(
        val compatible: Boolean,
        val reasons: List<String>,
        val geometry: HevcOutputPictureGeometry?
    )

    fun validate(
        grid: HevcOutputGridDimensions,
        placements: List<HevcOutputCellPlacement>
    ): Result {
        val reasons = mutableListOf<String>()
        if (grid.columns <= 0 || grid.rows <= 0) {
            return Result(false, listOf("Output grid dimensions must be positive"), null)
        }
        val requiredCells = grid.columns.toLong() * grid.rows.toLong()
        if (requiredCells > 256L) {
            return Result(false, listOf("Output grid exceeds the supported 256-cell model bound"), null)
        }
        if (placements.size.toLong() != requiredCells) {
            reasons += "Output grid requires exactly $requiredCells source-cell placements"
        }

        val byPosition = mutableMapOf<Pair<Int, Int>, HevcOutputCellPlacement>()
        placements.forEach { placement ->
            if (placement.column !in 0 until grid.columns ||
                placement.row !in 0 until grid.rows) {
                reasons += "Output cell placement is outside the declared grid"
            } else if (byPosition.put(placement.column to placement.row, placement) != null) {
                reasons += "Output grid contains duplicate cell placements"
            }
        }
        if (byPosition.size.toLong() != requiredCells) {
            reasons += "Output grid contains one or more unfilled cell positions"
        }

        placements.forEachIndexed { index, placement ->
            reasons += HevcSourceCellCompatibility.validateSelection(
                placement.sourceCell, "Output cell $index"
            )
        }
        val ordered = byPosition.values.toList()
        for (firstIndex in ordered.indices) {
            for (secondIndex in firstIndex + 1 until ordered.size) {
                reasons += HevcSourceCellCompatibility.compare(
                    ordered[firstIndex].sourceCell, ordered[secondIndex].sourceCell
                ).reasons
            }
        }
        if (reasons.isNotEmpty()) return Result(false, reasons.distinct(), null)

        val metrics = Array(grid.rows) { row ->
            Array(grid.columns) { column ->
                HevcSourceCellCompatibility.cellMetricsForGrid(
                    byPosition.getValue(column to row).sourceCell
                )!!
            }
        }
        val columnWidthsPixels = (0 until grid.columns).map { column ->
            metrics[0][column][0]
        }
        val columnWidthsCtbs = (0 until grid.columns).map { column ->
            metrics[0][column][2]
        }
        val rowHeightsPixels = (0 until grid.rows).map { row ->
            metrics[row][0][1]
        }
        val rowHeightsCtbs = (0 until grid.rows).map { row ->
            metrics[row][0][3]
        }
        val ctbSize = metrics[0][0][4]

        for (row in 0 until grid.rows) {
            for (column in 0 until grid.columns) {
                val cell = metrics[row][column]
                if (cell[0] != columnWidthsPixels[column] ||
                    cell[2] != columnWidthsCtbs[column]) {
                    reasons += "Cells in output column $column do not have matching width geometry"
                }
                if (cell[1] != rowHeightsPixels[row] ||
                    cell[3] != rowHeightsCtbs[row]) {
                    reasons += "Cells in output row $row do not have matching height geometry"
                }
                if (cell[4] != ctbSize) reasons += "Output cells do not share one CTB size"
                if (column < grid.columns - 1 &&
                    cell[0] != cell[2] * ctbSize) {
                    reasons += "A partial CTB-width source cell cannot be placed before the final output column"
                }
                if (row < grid.rows - 1 &&
                    cell[1] != cell[3] * ctbSize) {
                    reasons += "A partial CTB-height source cell cannot be placed before the final output row"
                }
            }
        }
        if (reasons.isNotEmpty()) return Result(false, reasons.distinct(), null)

        val widthInCtbsLong = columnWidthsCtbs.sumOf { it.toLong() }
        val heightInCtbsLong = rowHeightsCtbs.sumOf { it.toLong() }
        val codedWidthLong =
            (widthInCtbsLong - columnWidthsCtbs.last()) * ctbSize +
                columnWidthsPixels.last()
        val codedHeightLong =
            (heightInCtbsLong - rowHeightsCtbs.last()) * ctbSize +
                rowHeightsPixels.last()
        if (widthInCtbsLong <= 0 || heightInCtbsLong <= 0 ||
            widthInCtbsLong > Int.MAX_VALUE || heightInCtbsLong > Int.MAX_VALUE ||
            codedWidthLong <= 0 || codedHeightLong <= 0 ||
            codedWidthLong > 32768L || codedHeightLong > 32768L) {
            reasons += "Generated output picture dimensions exceed supported HEVC bounds"
            return Result(false, reasons.distinct(), null)
        }

        val geometry = HevcOutputPictureGeometry(
            grid = grid,
            codedWidth = codedWidthLong.toInt(),
            codedHeight = codedHeightLong.toInt(),
            displayWidth = codedWidthLong.toInt(),
            displayHeight = codedHeightLong.toInt(),
            ctbSize = ctbSize,
            pictureWidthInCtbs = widthInCtbsLong.toInt(),
            pictureHeightInCtbs = heightInCtbsLong.toInt(),
            tileColumnWidthsInCtbs = columnWidthsCtbs,
            tileRowHeightsInCtbs = rowHeightsCtbs
        )
        return Result(true, emptyList(), geometry)
    }
}
