package app.apexview

/** Actual F1 rendition reporting without confusing atlas dimensions and feed pixels. */
internal object ComposedSourceRenditions {
    fun fromSourceRects(
        labels: List<String>, commonDisplay: Pair<Int, Int>,
        sourceRects: List<Pair<Int, Int>>,
    ): Map<String, Pair<Int, Int>> {
        require(commonDisplay.first > 0 && commonDisplay.second > 0)
        require(sourceRects.isEmpty() || sourceRects.size == labels.size)
        require(labels.size == labels.distinct().size)
        return labels.mapIndexed { i, label ->
            val dimensions = sourceRects.getOrNull(i) ?: commonDisplay
            require(dimensions.first > 0 && dimensions.second > 0)
            label to dimensions
        }.toMap()
    }
}

/** A user quality preference is a maximum, not a promise from the CDN. */
internal object QualityDisclosurePolicy {
    fun notice(requested: VideoQuality, actualHeight: Int): String? {
        if (requested.targetHeight <= 0 || actualHeight <= 0 ||
            actualHeight >= requested.targetHeight) return null
        return "Current source ${actualHeight}p is below the selected ${requested.label} ceiling."
    }
}
