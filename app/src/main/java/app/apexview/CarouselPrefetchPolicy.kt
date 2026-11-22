package app.apexview

/**
 * Image prefetch should never scale with the total size of the F1 home
 * catalogue. Keep only the currently settled carousel slide and its nearest
 * neighbours warm at thumbnail resolution. Full-resolution loading belongs
 * to the actual visible AsyncImage.
 */
internal fun carouselPrefetchIndices(settledPage: Int, count: Int): List<Int> {
    if (count <= 0) return emptyList()
    return listOf(0L, 1L, -1L)
        .map { delta -> Math.floorMod(settledPage.toLong() + delta, count.toLong()).toInt() }
        .distinct()
}
