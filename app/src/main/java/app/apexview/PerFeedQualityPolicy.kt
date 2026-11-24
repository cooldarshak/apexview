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
