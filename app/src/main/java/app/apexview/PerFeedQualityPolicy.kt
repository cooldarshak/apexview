package app.apexview

/** Compressed HEVC stitching currently requires equal-resolution sources and one SPS/PPS. */
internal object PerFeedQualityPolicy {
    fun commonDecoderQuality(qualities: List<VideoQuality>): VideoQuality? {
        if (qualities.isEmpty()) return null
        return qualities.first().takeIf { wanted -> qualities.all { it == wanted } }
    }

    fun description(labels: List<String>, qualities: List<VideoQuality>): String =
        labels.zip(qualities).joinToString(", ") { (name, quality) -> "$name=${quality.label}" }

    const val MIXED_UNSUPPORTED = "Mixed source resolutions need a compatible HEVC tile composer. " +
        "This build keeps one decoder and will not silently use multiple source decoders. " +
        "Choose matching quality for now, or use Multiple decoders."
}
