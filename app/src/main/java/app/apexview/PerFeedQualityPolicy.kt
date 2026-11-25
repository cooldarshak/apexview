package app.apexview

/** Encrypted CMAF stitching has a stricter representation gate than clear VOD tile stitching. */
internal object PerFeedQualityPolicy {
    fun commonDecoderQuality(qualities: List<VideoQuality>): VideoQuality? {
        if (qualities.isEmpty()) return null
        return qualities.first().takeIf { wanted -> qualities.all { it == wanted } }
    }

    // No hidden source-quality downgrade: encrypted mixed-resolution pictures require
    // an independent HEVC/CBCS subsample validation before this policy can be extended.

    fun description(labels: List<String>, qualities: List<VideoQuality>): String =
        labels.zip(qualities).joinToString(", ") { (name, quality) -> "$name=${quality.label}" }

    const val MIXED_UNSUPPORTED = "This F1 source requires protected HEVC; mixed-resolution protected stitching " +
        "has not passed the CBCS subsample gate. Your requested qualities were preserved, not downgraded. " +
        "Select the same quality for these feeds to try protected single-decoder playback. " +
        "Clear archives can still play mixed resolutions with one decoder."
}

/** A session-bound common preference, not a replacement for authorized rendition selection.
 * The largest visible tile is used so equal-size compatible atlases do not underserve the main feed.
 * Geometry is in actual local pixels. No decoder restart is initiated by a resize or thermal sample.
 */
internal object ComposedAutoQualityPolicy {
    fun preferredHeight(qualities: List<VideoQuality>, visibleHeights: List<Float>, baseline: Int,
                        previous: Int?, constrained: Boolean): Int? {
        require(baseline > 0)
        if (qualities.isEmpty() || qualities.any { it != VideoQuality.AUTO }) return null
        if (visibleHeights.size != qualities.size ||
            visibleHeights.any { !it.isFinite() || it <= 0f }) return null
        val rungs = (VideoQuality.values().map { it.targetHeight }
            .filter { it in 1..baseline } + baseline).distinct().sorted()
        val height = visibleHeights.maxOrNull() ?: return null
        val wanted = rungs.firstOrNull { it >= height } ?: baseline
        val prior = previous?.takeIf { it in rungs }
        // Deadband is applied only between preparations of the same route/feed set.
        // A pressure signal suppresses an upgrade and permits one smaller real cap.
        val stable = when {
            prior == null -> wanted
            wanted > prior && height <= prior * 1.15f -> prior
            wanted < prior && height >= wanted * 0.85f -> prior
            else -> wanted
        }
        if (!constrained) return stable
        val pressureTarget = rungs.lastOrNull { it < wanted } ?: wanted
        return minOf(stable, prior ?: stable, pressureTarget)
    }
}
