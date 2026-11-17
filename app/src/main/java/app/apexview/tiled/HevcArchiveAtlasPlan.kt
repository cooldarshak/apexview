package app.apexview.tiled

import kotlin.math.abs
import kotlin.math.ln

/** A rectangular encoded atlas. Extra cells duplicate the last source and are never displayed. */
internal data class HevcArchiveAtlasPlan(val columns: Int, val rows: Int) {
    companion object {
        fun choose(feeds: Int, width: Int, height: Int, maxWidth: Int, maxHeight: Int): HevcArchiveAtlasPlan? {
            require(feeds > 0 && width > 0 && height > 0 && maxWidth > 0 && maxHeight > 0)
            if (width.toLong() * feeds <= maxWidth && height <= maxHeight) return HevcArchiveAtlasPlan(feeds, 1)
            return (1..feeds).mapNotNull { columns ->
                val rows = (feeds + columns - 1) / columns
                if (width.toLong() * columns > maxWidth || height.toLong() * rows > maxHeight) null
                else HevcArchiveAtlasPlan(columns, rows)
            }.minWithOrNull(compareBy<HevcArchiveAtlasPlan> { it.columns * it.rows - feeds }
                .thenBy { abs(ln((width.toDouble() * it.columns / (height.toDouble() * it.rows)) / (16.0 / 9.0))) })
        }
    }
}
