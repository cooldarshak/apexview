package app.apexview.tiled

/**
 * Strict compatibility gate for two candidate HEVC tile tracks.
 *
 * This is intentionally narrower than general HEVC compatibility. It accepts only parameter
 * sets that this POC has fully represented and whose slice-header features are currently
 * understood. It is not a claim that arbitrary slice payloads can already be stitched.
 */
internal object HevcParameterSetCompatibility {
    data class Result(val compatible: Boolean, val reasons: List<String>)

    fun compare(
        firstVps: HevcParameterSets.VpsMetadata?,
        firstSps: HevcParameterSets.SpsMetadata?,
        firstPps: HevcParameterSets.PpsMetadata?,
        secondVps: HevcParameterSets.VpsMetadata?,
        secondSps: HevcParameterSets.SpsMetadata?,
        secondPps: HevcParameterSets.PpsMetadata?
    ): Result {
        val reasons = mutableListOf<String>()
        if (firstVps == null || secondVps == null) reasons += "VPS is missing"
        else {
            if (firstVps != secondVps) reasons += "VPS metadata differs"
            if (firstVps.extensionPresent || secondVps.extensionPresent) {
                reasons += "VPS multilayer extension payload is present but not parsed"
            }
            if (firstVps.maxLayers != 1 || secondVps.maxLayers != 1) {
                reasons += "Multilayer VPS configurations are outside the supported profile"
            }
        }

        if (firstSps == null || secondSps == null) {
            reasons += "SPS is missing"
        } else {
            if (firstSps != secondSps) reasons += "SPS metadata differs"
            if (firstSps.spsExtensionPresent || secondSps.spsExtensionPresent) {
                reasons += "SPS extension is missing or unsupported"
            }
        }

        if (firstPps == null || secondPps == null) {
            reasons += "PPS is missing"
        } else {
            if (firstPps != secondPps) reasons += "PPS metadata differs"
            if (!firstPps.tilesEnabled || !secondPps.tilesEnabled) {
                reasons += "Both tracks must declare HEVC tiles"
            }
            if (firstPps.numTileColumns != 2 || secondPps.numTileColumns != 2 ||
                firstPps.numTileRows != 1 || secondPps.numTileRows != 1) {
                reasons += "Only a 2-column by 1-row HEVC tile grid is supported"
            }
            if (firstSps == null || secondSps == null) {
                reasons += "Cannot verify tile geometry because one or both SPS records are missing"
            } else {
                if (firstSps.width <= 0 || firstSps.height <= 0 ||
                    secondSps.width <= 0 || secondSps.height <= 0) {
                    reasons += "SPS picture dimensions are invalid"
                }
                val columnWidths = firstPps.tileColumnWidthsInCtbs
                val rowHeights = firstPps.tileRowHeightsInCtbs
                if (columnWidths == null || rowHeights == null) {
                    reasons += "PPS tile geometry is missing"
                } else if (columnWidths.size != 2 || rowHeights.size != 1 ||
                    columnWidths[0] != columnWidths[1] ||
                    columnWidths.sum() != firstSps.picWidthInCtbs ||
                    rowHeights.single() != firstSps.picHeightInCtbs) {
                    reasons += "Only a uniform, full-picture 2x1 CTB layout is supported"
                }
            }
            if (firstPps.entropyCodingSyncEnabled || secondPps.entropyCodingSyncEnabled) {
                reasons += "Wavefront entropy coding is not supported"
            }
            if (firstPps.weightedPredictionEnabled || firstPps.weightedBipredictionEnabled ||
                secondPps.weightedPredictionEnabled || secondPps.weightedBipredictionEnabled) {
                reasons += "Weighted prediction is not supported by the slice-header parser"
            }
            if (firstPps.deblockingFilterOverrideEnabled == true ||
                secondPps.deblockingFilterOverrideEnabled == true) {
                reasons += "Per-slice deblocking overrides are not supported"
            }
            if (firstPps.scalingListDataPresent || secondPps.scalingListDataPresent) {
                reasons += "PPS scaling-list data is not compared"
            }
            if (firstPps.listsModificationPresent || secondPps.listsModificationPresent) {
                reasons += "Reference-list modification syntax is not supported"
            }
            if (firstPps.sliceSegmentHeaderExtensionPresent || secondPps.sliceSegmentHeaderExtensionPresent) {
                reasons += "Slice-segment header extensions are not supported"
            }
            if (firstPps.extensionPresent || secondPps.extensionPresent) {
                reasons += "PPS extensions are not supported"
            }
        }

        return Result(reasons.isEmpty(), reasons.distinct())
    }
}
