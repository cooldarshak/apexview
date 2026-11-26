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

/** Metadata-only display name; persistent quality overrides still use profileLabel. */
internal fun qualityFeedName(channel: Channel, main: Boolean): String {
    val title = channel.title.trim().takeUnless {
        it.isEmpty() || it.matches(Regex("(?i)channel[- ]?\\d+"))
    }
    val driver = channel.driverName?.trim()?.takeIf { it.isNotEmpty() }
    val abbreviation = channel.tla?.trim()?.takeIf { it.isNotEmpty() }
    val onboard = driver != null || channel.racingNumber != null || abbreviation != null
    val name = if (onboard) {
        val identity = driver ?: title ?: abbreviation ?: channel.racingNumber?.let { "Driver $it" } ?: "Driver"
        "$identity · Onboard"
    } else title ?: if (channel.isDefault) "Broadcast" else "Video feed"
    return if (main) "Main · $name" else name
}

/** A user quality preference is a maximum, not a promise from the CDN. */
internal object QualityDisclosurePolicy {
    /** Real track-group availability only. Do not fabricate a lower CDN rung. */
    fun unavailableManualCap(requested: VideoQuality, offered: List<Pair<Int, Int>>): String? {
        if (requested.targetHeight <= 0 || offered.isEmpty()) return null
        val heights = offered.filter { (w, h) -> w > 0 && h > 0 }.map { it.second }.distinct().sorted()
        if (heights.isEmpty() || requested.targetHeight in heights) return null
        val list = heights.joinToString(", ") { "${it}p" }
        return if (heights.none { it <= requested.targetHeight })
            "F1 currently offers no video at or below ${requested.label}. " +
                "Available: $list. Choose a higher quality ceiling or Auto to restore the picture."
        else "F1 does not advertise ${requested.label} for this feed. Available: $list. " +
            "A lower compatible source may be used; your preference is unchanged."
    }

    fun notice(requested: VideoQuality, actualHeight: Int): String? {
        if (requested.targetHeight <= 0 || actualHeight <= 0 || actualHeight == requested.targetHeight) return null
        return if (actualHeight < requested.targetHeight)
            "Current source ${actualHeight}p is below the selected ${requested.label} ceiling."
        else "Observed source ${actualHeight}p exceeds the selected ${requested.label} ceiling. Check the offered rendition; the request was not changed."
    }
}


/** Evidence-only workload disclosure for a chosen, already authorized rendition.
 * No guessed CDN rung, thermal quality switch or extra video decoder. */
internal object VideoQualityAdvisory {
    data class Atlas(val codedWidth: Int, val codedHeight: Int,
                     val sourceFps: Double, val playbackSpeed: Float,
                     val activeCodec: String? = null) {
        val codedPixels: Long get() = codedWidth.toLong() * codedHeight.toLong()
        val effectiveFps: Double get() = sourceFps * playbackSpeed
    }

    fun atlasNotice(atlas: Atlas?): String? {
        if (atlas == null || atlas.codedWidth <= 0 || atlas.codedHeight <= 0 ||
            !atlas.effectiveFps.isFinite() || atlas.effectiveFps <= 0) return null
        val megapixels = "%.2f".format(java.util.Locale.ROOT, atlas.codedPixels / 1_000_000.0)
        val fps = "%.1f".format(java.util.Locale.ROOT, atlas.effectiveFps)
        return "Actual HEVC decoder input: ${atlas.codedWidth} × ${atlas.codedHeight} " +
            "($megapixels MP per picture) at $fps effective fps. " +
            "This is coded workload, not screen resolution or verified presentation." +
            " Active codec: ${atlas.activeCodec ?: "NOT_YET_OBSERVED"}."
    }

    fun tileNotice(actual: Pair<Int, Int>?, tileWidth: Float, tileHeight: Float): String? {
        if (actual == null || actual.first <= 0 || actual.second <= 0 ||
            !tileWidth.isFinite() || !tileHeight.isFinite() ||
            tileWidth <= 0f || tileHeight <= 0f) return null
        val width = tileWidth.toInt().coerceAtLeast(1)
        val height = tileHeight.toInt().coerceAtLeast(1)
        return "Visible tile: ${width} × ${height}px; actual F1 source: " +
            "${actual.first} × ${actual.second}px. Source pixels are not decoded atlas pixels."
    }

    fun thermalNotice(status: Int, decoderMode: DecoderMode, requested: VideoQuality): String? {
        if (status < android.os.PowerManager.THERMAL_STATUS_MODERATE) return null
        val action = when {
            status >= android.os.PowerManager.THERMAL_STATUS_CRITICAL ->
                "Device temperature is critical; multiple-decoder video prioritizes the main feed."
            status >= android.os.PowerManager.THERMAL_STATUS_SEVERE ->
                "Device temperature is severe; sustained throughput may be limited."
            else -> "Device is warming up; check sustained video cadence and temperature."
        }
        val option = if (decoderMode == DecoderMode.SINGLE)
            "The composed HEVC source is unchanged; you can manually choose fewer feeds or a lower available rendition."
        else if (requested == VideoQuality.AUTO)
            "Auto quality may adapt to the visible tile and temperature; choose a fixed cap if preferred."
        else
            "Your fixed quality ceiling is kept; a lower actual CDN rendition is disclosed, not silently claimed as the requested quality."
        return "$action $option"
    }
}
