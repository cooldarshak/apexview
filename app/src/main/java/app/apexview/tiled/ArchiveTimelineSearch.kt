package app.apexview.tiled

/** Binary search over the finite, strictly ordered archive media timeline. */
internal object ArchiveTimelineSearch {
    fun msToUs(ms: Long): Long = ms.coerceIn(0L, Long.MAX_VALUE / 1000L) * 1000L

    inline fun floorIndex(count: Int, positionUs: Long, startAt: (Int) -> Long): Int {
        require(count > 0)
        var low = 0
        var high = count - 1
        while (low <= high) {
            val mid = low + (high - low) / 2
            if (startAt(mid) <= positionUs) low = mid + 1 else high = mid - 1
        }
        return high.coerceAtLeast(0)
    }
}
