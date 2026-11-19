package app.apexview

/** Encrypted CMAF stitching has a stricter representation gate than clear VOD tile stitching. */
internal object PerFeedQualityPolicy {
    fun commonDecoderQuality(qualities: List<VideoQuality>): VideoQuality? {
        if (qualities.isEmpty()) return null
        return qualities.first().takeIf { wanted -> qualities.all { it == wanted } }
    }

    fun description(labels: List<String>, qualities: List<VideoQuality>): String =
        labels.zip(qualities).joinToString(", ") { (name, quality) -> "$name=${quality.label}" }

    const val MIXED_UNSUPPORTED = "Mixed-resolution encrypted HEVC composition has not passed the protected-subsample gate. " +
        "Clear HEVC archives can use the mixed tile atlas when their source tiles fit. " +
        "No additional video decoders will be started."
}
