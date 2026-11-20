package app.apexview.tiled

/** A source picture occupies a contiguous block of *coded* HEVC tiles. Missing atlas
 * cells repeat an independent source tile; no decoded pixels or extra video player are used. */
internal object HevcMixedArchiveAtlasPlan {
    data class Source(
        val vps: HevcParameterSets.VpsMetadata,
        val sps: HevcParameterSets.SpsMetadata,
        val pps: HevcParameterSets.PpsMetadata,
    )

    data class SourceRect(val x: Int, val y: Int, val width: Int, val height: Int)
    data class Plan(
        val grid: HevcOutputGridDimensions,
        val cells: List<HevcTizenGridComposer.CellSelection>,
        val sourceRects: List<SourceRect>,
        val codedWidth: Int,
        val codedHeight: Int,
        val evaluatedShapes: Int = 0,
        val rejectedGridValidations: Int = 0,
        val searchBudgetLimited: Boolean = false,
    )

    private data class Tile(val feed: Int, val index: Int, val col: Int, val row: Int,
                            val ctbsWide: Int, val ctbsHigh: Int, val pixelsWide: Int, val pixelsHigh: Int,
                            val sourceCell: HevcSourceCellSelection)
    private data class FeedTiles(val cols: Int, val rows: Int, val tiles: List<Tile>)

    /** Returns null if the actual SPS/PPS partitions cannot form one conforming tiled picture.
     * Mixed quality is supported only when the bitstream geometry permits lossless stitching. */
    fun choose(sources: List<Source>, maximumWidth: Int = 4096,
               maximumHeight: Int = HevcArchiveAtlasPlan.MAX_CODED_HEIGHT): Plan? {
        if (sources.isEmpty() || sources.size > 6) return null
        val ctb = sources.first().sps.ctbSize
        if (ctb <= 0 || sources.any { it.sps.ctbSize != ctb }) return null
        val all = sources.mapIndexed { index, source ->
            val cols = source.pps.tileColumnWidthsInCtbs ?: return null
            val rows = source.pps.tileRowHeightsInCtbs ?: return null
            if (cols.isEmpty() || rows.isEmpty() || cols.any { it <= 0 } || rows.any { it <= 0 } ||
                cols.sum() != source.sps.picWidthInCtbs || rows.sum() != source.sps.picHeightInCtbs) return null
            val picture = HevcSourcePicture(HevcSourceFeedIdentity("source-$index"),
                HevcSourcePictureGeometry.from(source.sps), HevcSourceParameterSets(source.vps, source.sps, source.pps))
            FeedTiles(cols.size, rows.size, rows.flatMapIndexed { row, high ->
                cols.mapIndexed { col, wide ->
                    val x = cols.take(col).sum() * ctb
                    val y = rows.take(row).sum() * ctb
                    val selection = HevcSourceCellSelection(picture, x / ctb, y / ctb, wide, high)
                    Tile(index, row * cols.size + col, col, row, wide, high,
                        minOf(wide * ctb, source.sps.width - x),
                        minOf(high * ctb, source.sps.height - y), selection)
                }
            })
        }
        if (all.sumOf { it.tiles.size } > 24) return null
        val availableTiles = all.flatMap { it.tiles }
        // Every tile must be independently decodable and share the coding tools of the
        // output PPS. Source dimensions and source tile counts may differ.
        val anchor = all.first().tiles.first().sourceCell
        if (availableTiles.any {
            !HevcSourceCellCompatibility.compareCodingParameters(anchor, it.sourceCell).compatible
        }) return null
        val minimumCells = all.sumOf { it.tiles.size }
        val minimumColumns = all.maxOf { it.cols }
        val minimumRows = all.maxOf { it.rows }
        // Bounds for the remaining unplaced feeds are used at every recursive
        // node. Precompute them rather than allocating all.drop(index) lists.
        val remainingColumns = IntArray(all.size + 1)
        val remainingRows = IntArray(all.size + 1)
        for (i in all.indices.reversed()) {
            remainingColumns[i] = remainingColumns[i + 1] + all[i].cols
            remainingRows[i] = remainingRows[i + 1] + all[i].rows
        }
        // Every output row and column must be established by a real source tile;
        // the filler may repeat a tile only after that geometry is known.
        val maximumColumns = minOf(8, maximumWidth / ctb, all.sumOf { it.cols })
        val maximumRows = minOf(8, maximumHeight / ctb, all.sumOf { it.rows })
        val candidates = (minimumColumns..maximumColumns).flatMap { columns ->
            (minimumRows..maximumRows).mapNotNull { rows ->
                if (columns * rows !in minimumCells..24) null else columns to rows
            }
        }.sortedWith(compareBy<Pair<Int, Int>> { it.first * it.second }.thenBy { it.first })

        // Cell count alone is not a proxy for HEVC decoder workload. A tall,
        // narrow valid grid can contain far more coded pixels than a wider one.
        // Keep the existing bounded search per shape, but compare the *actual*
        // validated coded picture areas before choosing the output atlas.
        var best: Plan? = null
        var bestArea = Long.MAX_VALUE
        var evaluatedShapes = 0
        var rejectedGridValidations = 0
        var searchBudgetLimited = false
        val sourceAreaLowerBound = sources.sumOf {
            it.sps.width.toLong() * it.sps.height.toLong()
        }
        for ((columns, rows) in candidates) {
            evaluatedShapes++
            val placed = arrayOfNulls<Tile>(columns * rows)
            val rowCtbs = IntArray(rows)
            val colCtbs = IntArray(columns)
            val rowPixels = IntArray(rows)
            val colPixels = IntArray(columns)
            val origin = arrayOfNulls<Pair<Int, Int>>(sources.size)
            var attempts = 0
            fun valid(tile: Tile, x: Int, y: Int): Boolean =
                (x == columns - 1 || tile.pixelsWide == tile.ctbsWide * ctb) &&
                    (y == rows - 1 || tile.pixelsHigh == tile.ctbsHigh * ctb) &&
                    (colCtbs[x] == 0 || colCtbs[x] == tile.ctbsWide) &&
                    (rowCtbs[y] == 0 || rowCtbs[y] == tile.ctbsHigh) &&
                    (colPixels[x] == 0 || colPixels[x] == tile.pixelsWide) &&
                    (rowPixels[y] == 0 || rowPixels[y] == tile.pixelsHigh)

            fun finish(): Plan? {
                if (rowCtbs.any { it == 0 } || colCtbs.any { it == 0 }) return null
                val width = colPixels.sum()
                val height = rowPixels.sum()
                if (width > maximumWidth || height > maximumHeight) return null
                val complete = placed.mapIndexed { slot, tile ->
                    tile ?: availableTiles.firstOrNull { valid(it, slot % columns, slot / columns) } ?: return null
                }
                val grid = HevcOutputGridDimensions(columns, rows)
                val selected = complete.mapIndexed { slot, tile ->
                    HevcTizenGridComposer.CellSelection(tile.feed, tile.index, slot % columns, slot / columns)
                }
                val checked = HevcOutputGridValidator.validate(grid, complete.mapIndexed { slot, tile ->
                    HevcOutputCellPlacement(tile.sourceCell, slot % columns, slot / columns)
                })
                if (!checked.compatible || checked.geometry == null ||
                    checked.geometry.codedWidth != width || checked.geometry.codedHeight != height) {
                    rejectedGridValidations++
                    return null
                }
                val rects = sources.indices.map { feed ->
                    val (x, y) = origin[feed] ?: return null
                    SourceRect(colCtbs.take(x).sum() * ctb, rowCtbs.take(y).sum() * ctb,
                        sources[feed].sps.displayWidth, sources[feed].sps.displayHeight)
                }
                return Plan(grid, selected, rects, width, height)
            }

            fun search(index: Int) {
                if (++attempts > 50_000) return
                // Every occupied row/column has fixed coded dimensions; any
                // remaining source placements can only increase this area.
                val occupiedArea = colPixels.sum().toLong() * rowPixels.sum().toLong()
                if (occupiedArea >= bestArea) return
                if (colCtbs.count { it == 0 } > remainingColumns[index] ||
                    rowCtbs.count { it == 0 } > remainingRows[index]) return
                if (index == all.size) {
                    finish()?.let { plan ->
                        val area = plan.codedWidth.toLong() * plan.codedHeight.toLong()
                        if (area < bestArea) {
                            best = plan
                            bestArea = area
                        }
                    }
                    return
                }
                val feed = all[index]
                for (top in 0..rows - feed.rows) for (left in 0..columns - feed.cols) {
                    if (feed.tiles.any { tile ->
                        val x = left + tile.col; val y = top + tile.row
                        placed[y * columns + x] != null || !valid(tile, x, y)
                    }) continue
                    val changed = mutableListOf<Int>()
                    feed.tiles.forEach { tile ->
                        val x = left + tile.col; val y = top + tile.row
                        placed[y * columns + x] = tile
                        if (colCtbs[x] == 0) { colCtbs[x] = tile.ctbsWide; colPixels[x] = tile.pixelsWide; changed += x }
                        if (rowCtbs[y] == 0) { rowCtbs[y] = tile.ctbsHigh; rowPixels[y] = tile.pixelsHigh; changed += columns + y }
                    }
                    origin[index] = left to top
                    search(index + 1)
                    origin[index] = null
                    feed.tiles.forEach { tile -> placed[(top + tile.row) * columns + left + tile.col] = null }
                    changed.forEach { changedIndex ->
                        if (changedIndex < columns) { colCtbs[changedIndex] = 0; colPixels[changedIndex] = 0 }
                        else { rowCtbs[changedIndex - columns] = 0; rowPixels[changedIndex - columns] = 0 }
                    }
                }
            }
            search(0)
            if (attempts > 50_000) searchBudgetLimited = true
            // The input pictures cannot occupy less than their own coded area
            // without overlapping pixels, so this bound is globally optimal.
            if (bestArea <= sourceAreaLowerBound) break
        }
        return best?.copy(evaluatedShapes = evaluatedShapes,
            rejectedGridValidations = rejectedGridValidations,
            searchBudgetLimited = searchBudgetLimited)
    }
}
