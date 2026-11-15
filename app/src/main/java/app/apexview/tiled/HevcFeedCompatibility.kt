package app.apexview.tiled

/** Metadata-only gate. Unknown values fail closed; this is not proof that slice headers can be merged. */
internal object HevcFeedCompatibility {
    data class Profile(
        val codec: String?,
        val width: Int?,
        val height: Int?,
        val timescale: Long?,
        val frameDurationTicks: Long?,
        val encryptionScheme: String?
    )

    data class Result(val compatible: Boolean, val reasons: List<String>)

    /**
     * Strict parsed-parameter-set gate. Metadata-only MP4 profile matching is not enough
     * to establish that two HEVC elementary streams share one decoder configuration.
     * VUI and parameter-set extensions remain unsupported until their semantics are parsed.
     */
    fun compareParameterSets(
        firstSps: HevcParameterSets.SpsMetadata,
        firstPps: HevcParameterSets.PpsMetadata,
        secondSps: HevcParameterSets.SpsMetadata,
        secondPps: HevcParameterSets.PpsMetadata
    ): Result {
        val reasons = mutableListOf<String>()
        if (firstSps.vuiParametersPresent || secondSps.vuiParametersPresent) {
            reasons += "SPS VUI is present but not yet parsed"
        }
        if (firstSps.spsExtensionPresent != false || secondSps.spsExtensionPresent != false) {
            reasons += "SPS extension is present or cannot be determined"
        }
        if (firstPps.extensionPresent || secondPps.extensionPresent) {
            reasons += "PPS extension is present but not yet parsed"
        }
        if (firstPps.extensionFlags != null && firstPps.extensionFlags != 0 ||
            secondPps.extensionFlags != null && secondPps.extensionFlags != 0) {
            reasons += "PPS extension flags are not supported"
        }
        if (firstSps != secondSps) reasons += "Parsed SPS decoder configuration differs"
        if (firstPps != secondPps) reasons += "Parsed PPS tile/slice configuration differs"
        return Result(reasons.isEmpty(), reasons)
    }

    fun compare(first: Profile, second: Profile): Result {
        val reasons = mutableListOf<String>()
        if (first.codec != "hvc1" && first.codec != "hev1") reasons += "First feed codec is not a supported HEVC sample entry"
        if (second.codec != "hvc1" && second.codec != "hev1") reasons += "Second feed codec is not a supported HEVC sample entry"
        if (first.codec != second.codec) reasons += "Codec sample entries differ"
        if (first.width == null || first.height == null || second.width == null || second.height == null) {
            reasons += "Video dimensions are missing"
        } else if (first.width != second.width || first.height != second.height) {
            reasons += "Video dimensions differ"
        }
        if (first.timescale == null || second.timescale == null || first.timescale <= 0 || second.timescale <= 0) {
            reasons += "Track timescale is missing or invalid"
        } else if (first.timescale != second.timescale) {
            reasons += "Track timescales differ"
        }
        if (first.frameDurationTicks == null || second.frameDurationTicks == null ||
            first.frameDurationTicks <= 0 || second.frameDurationTicks <= 0) {
            reasons += "Frame duration is missing or invalid"
        } else if (first.frameDurationTicks != second.frameDurationTicks) {
            reasons += "Frame durations differ"
        }
        if (first.encryptionScheme != second.encryptionScheme) reasons += "Encryption schemes differ"
        return Result(reasons.isEmpty(), reasons)
    }
}
