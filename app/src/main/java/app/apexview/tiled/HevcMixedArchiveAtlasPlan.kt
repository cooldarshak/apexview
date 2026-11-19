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
        // Every tile must be independently decodable and share the coding tools of the
        // output PPS. Source dimensions and source tile counts may differ.
        val anchor = all.first().tiles.first().sourceCell
        if (all.flatMap { it.tiles }.any {
            !HevcSourceCellCompatibility.compareCodingParameters(anchor, it.sourceCell).compatible
        }) return null
        val minimumCells = all.sumOf { it.tiles.size }
        val minimumColumns = all.maxOf { it.cols }
        val minimumRows = all.maxOf { it.rows }
        val candidates = (minimumColumns..minOf(8, maximumWidth / ctb)).flatMap { columns ->
            (minimumRows..minOf(8, maximumHeight / ctb)).mapNotNull { rows ->
                if (columns * rows !in minimumCells..24) null else columns to rows
            }
        }.sortedWith(compareBy<Pair<Int, Int>> { it.first * it.second }.thenBy { it.first })

        for ((columns, rows) in candidates) {
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
                val available = all.flatMap { it.tiles }
                val complete = placed.mapIndexed { slot, tile ->
                    tile ?: available.firstOrNull { valid(it, slot % columns, slot / columns) } ?: return null
                }
                val grid = HevcOutputGridDimensions(columns, rows)
                val selected = complete.mapIndexed { slot, tile ->
                    HevcTizenGridComposer.CellSelection(tile.feed, tile.index, slot % columns, slot / columns)
                }
                val checked = HevcOutputGridValidator.validate(grid, complete.mapIndexed { slot, tile ->
                    HevcOutputCellPlacement(tile.sourceCell, slot % columns, slot / columns)
                })
                if (!checked.compatible || checked.geometry == null ||
                    checked.geometry.codedWidth != width || checked.geometry.codedHeight != height) return null
                val rects = sources.indices.map { feed ->
                    val (x, y) = origin[feed] ?: return null
                    SourceRect(colCtbs.take(x).sum() * ctb, rowCtbs.take(y).sum() * ctb,
                        sources[feed].sps.displayWidth, sources[feed].sps.displayHeight)
                }
                return Plan(grid, selected, rects, width, height)
            }

            fun search(index: Int): Plan? {
                if (++attempts > 50_000) return null
                if (index == all.size) return finish()
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
                    search(index + 1)?.let { return it }
                    origin[index] = null
                    feed.tiles.forEach { tile -> placed[(top + tile.row) * columns + left + tile.col] = null }
                    changed.forEach { changedIndex ->
                        if (changedIndex < columns) { colCtbs[changedIndex] = 0; colPixels[changedIndex] = 0 }
                        else { rowCtbs[changedIndex - columns] = 0; rowPixels[changedIndex - columns] = 0 }
                    }
                }
                return null
            }
            search(0)?.let { return it }
        }
        return null
    }
}
