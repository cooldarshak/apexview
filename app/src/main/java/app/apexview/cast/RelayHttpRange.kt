package app.apexview.cast

/** Overflow or malformed ranges never silently become full GETs. */
internal data class RelayHttpRange(val start: Int, val end: Int) {
    val length: Int get() = end - start + 1
    companion object {
        private val pattern = Regex("bytes=([0-9]+)-([0-9]*)")
        fun parse(header: String?, size: Int): RelayHttpRange? {
            if (size <= 0) return null
            if (header == null) return RelayHttpRange(0, size - 1)
            val match = pattern.matchEntire(header) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val rawEnd = match.groupValues[2]
            val end = if (rawEnd.isEmpty()) size.toLong() - 1 else rawEnd.toLongOrNull() ?: return null
            if (start !in 0L until size.toLong() || end !in start until size.toLong()) return null
            return RelayHttpRange(start.toInt(), end.toInt())
        }
    }
}
