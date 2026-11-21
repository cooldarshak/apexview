package app.apexview

/** Encrypted CMAF stitching has a stricter representation gate than clear VOD tile stitching. */
internal object PerFeedQualityPolicy {
    fun commonDecoderQuality(qualities: List<VideoQuality>): VideoQuality? {
        if (qualities.isEmpty()) return null
        return qualities.first().takeIf { wanted -> qualities.all { it == wanted } }
    }

    /**
     * Protected CBCS/DASH composition currently requires one uniform source rendition.
     * If per-feed choices differ, keep the user's saved choices unchanged and negotiate
     * the highest shared rendition not exceeding the lowest explicit preference.
     * AUTO imposes no per-feed ceiling. This does NOT enable mixed-resolution
     * encrypted slice rewriting or relax any DRM/subsample compatibility check.
     */
    fun protectedUniformQuality(qualities: List<VideoQuality>): VideoQuality? {
        val same = commonDecoderQuality(qualities)
        if (same != null || qualities.isEmpty()) return same
        return qualities.filter { it.targetHeight > 0 }
            .minByOrNull { it.targetHeight } ?: VideoQuality.AUTO
    }

    fun description(labels: List<String>, qualities: List<VideoQuality>): String =
        labels.zip(qualities).joinToString(", ") { (name, quality) -> "$name=${quality.label}" }

    const val MIXED_UNSUPPORTED = "Mixed-resolution encrypted HEVC composition has not passed the protected-subsample gate. " +
        "Clear HEVC archives can use the mixed tile atlas when their source tiles fit. " +
        "No additional video decoders will be started."
}
