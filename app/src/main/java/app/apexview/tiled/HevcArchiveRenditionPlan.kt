package app.apexview.tiled

/** The Cast floor is applied before selection; unavailable quality never becomes a lower rung. */
internal object HevcArchiveRenditionPlan {
    /** Match the highest offered bitrate at each selected geometry in each feed.
     * A missing/unknown declaration disables the bandwidth adjustment, not playback. */
    fun aggregateBitrates(perFeed: List<List<Triple<Int, Int, Int>>>): Map<Pair<Int, Int>, Long> {
        if (perFeed.isEmpty() || perFeed.any { it.isEmpty() }) return emptyMap()
        val costs = perFeed.map { offers ->
            offers.groupBy { it.first to it.second }.mapValues { (_, variants) ->
                if (variants.any { it.third <= 0 }) 0 else variants.maxOf { it.third }
            }
        }
        return costs.first().keys.mapNotNull { size ->
            val values = costs.map { it[size] ?: 0 }
            if (values.any { it <= 0 }) null else size to values.sumOf { it.toLong() }
        }.toMap()
    }

    /**
     * Manual selection is not Auto. If the requested common height is advertised,
     * an incompatible atlas must fail rather than disguise the failure as a lower
     * resolution. If that height is not advertised, try the highest legal lower
     * height. Never select a higher height or consult the Auto bandwidth budget.
     */
    fun chooseManual(common: Set<Pair<Int, Int>>, requestedHeight: Int, minimumHeight: Int,
                     feeds: Int, maximumWidth: Int): Pair<Int, Int>? {
        require(requestedHeight > 0 && minimumHeight >= 0)
        if (requestedHeight < minimumHeight) return null
        val exact = common.filter { it.second == requestedHeight }.toSet()
        val allowed = exact.ifEmpty { common.filter { it.second <= requestedHeight }.toSet() }
        return choose(allowed, null, minimumHeight, feeds, maximumWidth)
    }

    fun choose(common: Set<Pair<Int, Int>>, preferredHeight: Int?, minimumHeight: Int,
               feeds: Int, maximumWidth: Int,
               bandwidthCosts: Map<Pair<Int, Int>, Long> = emptyMap(),
               autoBandwidthBps: Long? = null): Pair<Int, Int>? {
        require(minimumHeight >= 0)
        val legal = common.filter { (width, height) ->
            width > 0 && height >= minimumHeight && height > 0 &&
                width <= Int.MAX_VALUE - 63 && height <= Int.MAX_VALUE - 63 &&
                HevcArchiveAtlasPlan.choose(feeds, ((width + 63) / 64) * 64,
                    ((height + 63) / 64) * 64, maximumWidth,
                    HevcArchiveAtlasPlan.MAX_CODED_HEIGHT) != null
        }
        val sizes = if (preferredHeight == null) legal else legal.filter { it.second <= preferredHeight }
            .ifEmpty { legal.minByOrNull { it.second }?.let(::listOf).orEmpty() }
        val candidates = if (autoBandwidthBps != null && autoBandwidthBps > 0 &&
            sizes.isNotEmpty() && sizes.all { (bandwidthCosts[it] ?: 0L) > 0L }) {
            sizes.filter { bandwidthCosts.getValue(it) <= autoBandwidthBps }
                .ifEmpty { listOf(sizes.minByOrNull { bandwidthCosts.getValue(it) }!!) }
        } else sizes
        return candidates.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
    }
}
