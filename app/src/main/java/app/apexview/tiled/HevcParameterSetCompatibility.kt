package app.apexview.tiled

/**
 * Source-cell compatibility for independent HEVC pictures contributing regions to a separately
 * generated output picture. Source PPS tile declarations describe only their own input pictures.
 */
internal object HevcParameterSetCompatibility {
    data class Result(val compatible: Boolean, val reasons: List<String>)

    fun compare(
        firstVps: HevcParameterSets.VpsMetadata?, firstSps: HevcParameterSets.SpsMetadata?,
        firstPps: HevcParameterSets.PpsMetadata?, secondVps: HevcParameterSets.VpsMetadata?,
        secondSps: HevcParameterSets.SpsMetadata?, secondPps: HevcParameterSets.PpsMetadata?
    ): Result {
        val reasons = mutableListOf<String>()
        if (firstVps == null || secondVps == null) reasons += "VPS is missing"
        else {
            if (firstVps.maxLayers != 1 || secondVps.maxLayers != 1) reasons += "Multilayer VPS configurations are outside the supported source profile"
            if (firstVps.extensionPresent || secondVps.extensionPresent) reasons += "VPS extension payload is present but not parsed"
            if (firstVps.maxSubLayers != secondVps.maxSubLayers ||
                firstVps.temporalIdNesting != secondVps.temporalIdNesting) reasons += "VPS temporal-layer structures differ"
            if (!sameProfile(firstVps.profileTierLevel, secondVps.profileTierLevel)) reasons += "VPS profile or tier constraints differ"
            if (firstVps.baseLayerInternal != secondVps.baseLayerInternal ||
                firstVps.baseLayerAvailable != secondVps.baseLayerAvailable ||
                firstVps.subLayerOrderingInfoPresent != secondVps.subLayerOrderingInfoPresent ||
                firstVps.maxDecPicBuffering != secondVps.maxDecPicBuffering ||
                firstVps.maxNumReorderPics != secondVps.maxNumReorderPics ||
                firstVps.maxLatencyIncrease != secondVps.maxLatencyIncrease ||
                firstVps.maxLayerId != secondVps.maxLayerId ||
                firstVps.numLayerSets != secondVps.numLayerSets) reasons += "VPS decoded-picture-buffer or layer-set constraints differ"
            if (firstVps.timingInfoPresent != secondVps.timingInfoPresent ||
                firstVps.numUnitsInTick != secondVps.numUnitsInTick ||
                firstVps.timeScale != secondVps.timeScale ||
                firstVps.pocProportionalToTiming != secondVps.pocProportionalToTiming ||
                firstVps.numTicksPocDiffOneMinus1 != secondVps.numTicksPocDiffOneMinus1 ||
                firstVps.hrdParameters != secondVps.hrdParameters) reasons += "VPS picture-timing or HRD constraints differ"
        }
        if (firstSps == null || secondSps == null) reasons += "SPS is missing"
        else {
            if (firstSps.spsExtensionPresent || secondSps.spsExtensionPresent) reasons += "SPS extension is present but not parsed"
            if (firstSps.vpsId != firstVps?.vpsId || secondSps.vpsId != secondVps?.vpsId) reasons += "SPS-to-VPS parameter-set reference is inconsistent"
            if ((firstVps != null && firstSps.maxSubLayers > firstVps.maxSubLayers) ||
                (secondVps != null && secondSps.maxSubLayers > secondVps.maxSubLayers)) reasons += "SPS temporal-layer count exceeds its referenced VPS"
            if (firstSps.maxSubLayers != secondSps.maxSubLayers ||
                firstSps.temporalIdNesting != secondSps.temporalIdNesting) reasons += "SPS temporal-layer structures differ"
            if (!sameProfile(firstSps.profileTierLevel, secondSps.profileTierLevel)) reasons += "SPS profile or tier constraints differ"
            if (firstSps.chromaFormatIdc != secondSps.chromaFormatIdc ||
                firstSps.separateColourPlane != secondSps.separateColourPlane) reasons += "Source chroma-format constraints differ"
            if (firstSps.bitDepthLuma != secondSps.bitDepthLuma ||
                firstSps.bitDepthChroma != secondSps.bitDepthChroma) reasons += "Source bit-depth constraints differ"
            if (firstSps.ctbSize != secondSps.ctbSize || firstSps.log2CtbSize != secondSps.log2CtbSize) reasons += "Source CTB sizes differ"
            if (firstSps.log2MaxPicOrderCntLsb != secondSps.log2MaxPicOrderCntLsb) reasons += "Source POC widths differ"
            if (firstSps.shortTermRefPicSets != secondSps.shortTermRefPicSets ||
                firstSps.longTermRefPicsPresent != secondSps.longTermRefPicsPresent ||
                firstSps.longTermRefPics != secondSps.longTermRefPics) reasons += "Source SPS reference-picture structures differ"
            if (firstSps.maxNumReorderPics != secondSps.maxNumReorderPics ||
                firstSps.maxDecPicBuffering != secondSps.maxDecPicBuffering ||
                firstSps.maxLatencyIncrease != secondSps.maxLatencyIncrease) reasons += "Source SPS DPB/reorder constraints differ"
            if (firstSps.sampleAdaptiveOffsetEnabled != secondSps.sampleAdaptiveOffsetEnabled ||
                firstSps.temporalMvpEnabled != secondSps.temporalMvpEnabled ||
                firstSps.scalingListEnabled != secondSps.scalingListEnabled ||
                firstSps.scalingListDataPresent != secondSps.scalingListDataPresent ||
                firstSps.ampEnabled != secondSps.ampEnabled || firstSps.pcmEnabled != secondSps.pcmEnabled ||
                firstSps.strongIntraSmoothingEnabled != secondSps.strongIntraSmoothingEnabled) reasons += "Source SPS coding-tool constraints differ"
            // VUI is source-track metadata, not coded-cell identity. Independent feeds can
            // legitimately signal different SAR/colour/timing. Output uses the first selected
            // cell's SPS template; exact sample timing is checked separately by WP4.
            if ((firstSps.vuiParametersPresent && firstSps.vuiMetadata == null) ||
                (secondSps.vuiParametersPresent && secondSps.vuiMetadata == null)) {
                reasons += "Source VUI metadata is present but could not be parsed completely"
            }
            listOf("First" to firstSps, "Second" to secondSps).forEach { (label, sps) ->
                if (sps.width <= 0 || sps.height <= 0 || sps.ctbSize <= 0 ||
                    sps.picWidthInCtbs <= 0 || sps.picHeightInCtbs <= 0 ||
                    sps.log2CtbSize !in 3..6 || sps.ctbSize != (1 shl sps.log2CtbSize)) reasons += "$label SPS source picture geometry is invalid"
                else if ((sps.width.toLong() + sps.ctbSize - 1L) / sps.ctbSize != sps.picWidthInCtbs.toLong() ||
                    (sps.height.toLong() + sps.ctbSize - 1L) / sps.ctbSize != sps.picHeightInCtbs.toLong()) reasons += "$label SPS CTB dimensions do not cover its coded picture"
            }
        }
        if (firstPps == null || secondPps == null) reasons += "PPS is missing"
        else {
            if ((firstSps != null && firstPps.spsId != firstSps.spsId) ||
                (secondSps != null && secondPps.spsId != secondSps.spsId)) reasons += "PPS-to-SPS parameter-set reference is inconsistent"
            if (firstPps.dependentSliceSegmentsEnabled || secondPps.dependentSliceSegmentsEnabled) reasons += "Dependent slice segments are not supported by the source-cell rewrite profile"
            if (firstPps.entropyCodingSyncEnabled || secondPps.entropyCodingSyncEnabled) reasons += "Wavefront entropy coding is not supported"
            if (firstPps.weightedPredictionEnabled || firstPps.weightedBipredictionEnabled ||
                secondPps.weightedPredictionEnabled || secondPps.weightedBipredictionEnabled) reasons += "Weighted prediction is not supported by the slice-header rewrite profile"
            if (firstPps.deblockingFilterOverrideEnabled == true || secondPps.deblockingFilterOverrideEnabled == true) reasons += "Per-slice deblocking overrides are not supported by the slice-header rewrite profile"
            if (firstPps.scalingListDataPresent || secondPps.scalingListDataPresent) reasons += "PPS scaling-list data is not supported"
            if (firstPps.listsModificationPresent || secondPps.listsModificationPresent) reasons += "Reference-list modification syntax is not supported"
            if (firstPps.sliceSegmentHeaderExtensionPresent || secondPps.sliceSegmentHeaderExtensionPresent) reasons += "Slice-segment header extensions are not supported"
            if (firstPps.extensionPresent || secondPps.extensionPresent ||
                firstPps.extensionFlags?.let { it != 0 } == true || secondPps.extensionFlags?.let { it != 0 } == true) reasons += "PPS extensions are not supported"
            if (firstSps != null) reasons += validateSourceTiles(firstSps, firstPps, "First")
            if (secondSps != null) reasons += validateSourceTiles(secondSps, secondPps, "Second")
            val normalize: (HevcParameterSets.PpsMetadata) -> HevcParameterSets.PpsMetadata = { p ->
                p.copy(ppsId = 0, spsId = 0, tilesEnabled = false, entropyCodingSyncEnabled = false,
                    uniformTileSpacing = null, numTileColumns = 1, numTileRows = 1,
                    explicitColumnWidthsMinus1 = emptyList(), explicitRowHeightsMinus1 = emptyList(),
                    tileColumnWidthsInCtbs = null, tileRowHeightsInCtbs = null, loopFilterAcrossTiles = null)
            }
            if (normalize(firstPps) != normalize(secondPps)) reasons += "Source PPS slice/coding syntax differs outside the source tile layout"
        }
        return Result(reasons.isEmpty(), reasons.distinct())
    }

    private fun validateSourceTiles(
        sps: HevcParameterSets.SpsMetadata, pps: HevcParameterSets.PpsMetadata, label: String
    ): List<String> {
        val reasons = mutableListOf<String>()
        val cols = pps.tileColumnWidthsInCtbs
        val rows = pps.tileRowHeightsInCtbs
        if (cols == null || rows == null) return listOf("$label PPS source tile geometry is unavailable")
        if (!pps.tilesEnabled) {
            if (pps.numTileColumns != 1 || pps.numTileRows != 1 ||
                cols != listOf(sps.picWidthInCtbs) || rows != listOf(sps.picHeightInCtbs)) reasons += "$label non-tiled PPS does not describe one complete source picture"
        } else {
            if (pps.numTileColumns <= 0 || pps.numTileRows <= 0 ||
                cols.size != pps.numTileColumns || rows.size != pps.numTileRows ||
                cols.any { it <= 0 } || rows.any { it <= 0 }) reasons += "$label PPS tile counts or dimensions are invalid"
            if (cols.sumOf { it.toLong() } != sps.picWidthInCtbs.toLong() ||
                rows.sumOf { it.toLong() } != sps.picHeightInCtbs.toLong()) reasons += "$label PPS tile geometry does not cover its complete source picture"
            if (pps.uniformTileSpacing == null) reasons += "$label tiled PPS spacing mode is unavailable"
            if (pps.loopFilterAcrossTiles == null) reasons += "$label tiled PPS loop-filter flag is unavailable"
        }
        return reasons
    }

    private fun sameProfile(a: HevcParameterSets.ProfileTierLevel, b: HevcParameterSets.ProfileTierLevel): Boolean =
        a.profileSpace == b.profileSpace && a.tierFlag == b.tierFlag &&
            a.profileIdc == b.profileIdc && a.compatibilityFlags == b.compatibilityFlags &&
            a.constraintIndicatorFlags == b.constraintIndicatorFlags &&
            a.subLayerProfilePresent == b.subLayerProfilePresent &&
            a.subLayerLevelPresent == b.subLayerLevelPresent &&
            a.subLayerProfiles == b.subLayerProfiles
}
