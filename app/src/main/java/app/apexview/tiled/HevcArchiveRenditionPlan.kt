package app.apexview.tiled

/** The Cast floor is applied before selection; unavailable quality never becomes a lower rung. */
internal object HevcArchiveRenditionPlan {
    fun choose(common: Set<Pair<Int, Int>>, preferredHeight: Int?, minimumHeight: Int,
               feeds: Int, maximumWidth: Int): Pair<Int, Int>? {
        require(minimumHeight >= 0)
        val legal = common.filter { (width, height) ->
            height >= minimumHeight && (preferredHeight == null || (width > 0 && height > 0 &&
                HevcArchiveAtlasPlan.choose(feeds, ((width + 63) / 64) * 64, ((height + 63) / 64) * 64, maximumWidth, HevcArchiveAtlasPlan.MAX_CODED_HEIGHT) != null))
        }
        val sizes = if (preferredHeight == null) legal else legal.filter { it.second <= preferredHeight }
            .ifEmpty { legal.minByOrNull { it.second }?.let(::listOf).orEmpty() }
        return sizes.maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
    }
}
