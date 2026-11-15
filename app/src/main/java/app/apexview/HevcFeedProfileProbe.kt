package app.apexview

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import app.apexview.tiled.HevcBitstream
import app.apexview.tiled.HevcParameterSets

/**
 * Sanitised read-only summary of the codec configuration Media3 already parsed for a video track.
 *
 * Only VPS/SPS/PPS metadata is returned. Raw initialization data is not retained or logged. This
 * helper never sees media sample payloads, manifest URLs, DRM init data, licence responses, KIDs
 * or IV values.
 */
internal object HevcFeedProfileProbe {
    fun summarize(trackType: Int, format: Format): String {
        val initializationData = format.initializationData
        val nals = initializationData.flatMap { csd ->
            runCatching { HevcBitstream.splitAnnexB(csd) }.getOrElse {
                listOfNotNull(
                    runCatching { HevcBitstream.parseNalHeader(csd) }.getOrNull()
                        ?.takeIf { nal -> nal.type in 32..34 }
                )
            }
        }.filter { it.type in 32..34 }

        val vpsUnits = nals.filter { it.type == 32 }
        val spsUnits = nals.filter { it.type == 33 }
        val ppsUnits = nals.filter { it.type == 34 }

        val vps = vpsUnits.mapNotNull { nal ->
            runCatching { HevcParameterSets.parseVps(nal.bytes) }.getOrNull()
        }
        val sps = spsUnits.mapNotNull { nal ->
            runCatching { HevcParameterSets.parseSps(nal.bytes) }.getOrNull()
        }
        val spsById = sps.associateBy { it.spsId }
        val pps = ppsUnits.mapNotNull { nal ->
            val preliminary = runCatching { HevcParameterSets.parsePps(nal.bytes) }.getOrNull()
                ?: return@mapNotNull null
            val referenceSps = spsById[preliminary.spsId]
            if (referenceSps == null) preliminary
            else runCatching { HevcParameterSets.parsePps(nal.bytes, referenceSps) }.getOrDefault(preliminary)
        }

        val vpsSummary = vps.joinToString("|") {
            "id=${it.vpsId},layers=${it.maxLayers},subLayers=${it.maxSubLayers}," +
                "ptl=${it.profileTierLevel.profileIdc}/${it.profileTierLevel.levelIdc}," +
                "ordering=${it.subLayerOrderingInfoPresent},maxDPB=${it.maxDecPicBuffering}," +
                "timing=${it.timingInfoPresent},hrd=${it.hrdParameters.size},extension=${it.extensionPresent}"
        }.ifEmpty { "unavailable" }
        val spsSummary = sps.joinToString("|") {
            "id=${it.spsId},vps=${it.vpsId},profile=${it.profileTierLevel.profileIdc}/${it.profileTierLevel.levelIdc}," +
                "size=${it.width}x${it.height},display=${it.displayWidth}x${it.displayHeight}," +
                "chroma=${it.chromaFormatIdc},bitDepth=${it.bitDepthLuma}/${it.bitDepthChroma}," +
                "pocBits=${it.log2MaxPicOrderCntLsb},ctb=${it.ctbSize},ctbGrid=${it.picWidthInCtbs}x${it.picHeightInCtbs}," +
                "vui=${it.vuiParametersPresent},spsExtension=${it.spsExtensionPresent}"
        }.ifEmpty { "unavailable" }
        val ppsSummary = pps.joinToString("|") {
            "id=${it.ppsId},sps=${it.spsId},tiles=${it.tilesEnabled},tileGrid=${it.numTileColumns}x${it.numTileRows}," +
                "tileColumns=${it.tileColumnWidthsInCtbs},tileRows=${it.tileRowHeightsInCtbs}," +
                "entropySync=${it.entropyCodingSyncEnabled},dependentSlices=${it.dependentSliceSegmentsEnabled}," +
                "weighted=${it.weightedPredictionEnabled}/${it.weightedBipredictionEnabled}," +
                "deblockOverride=${it.deblockingFilterOverrideEnabled},listModification=${it.listsModificationPresent}," +
                "sliceHeaderExtension=${it.sliceSegmentHeaderExtensionPresent},extension=${it.extensionPresent}"
        }.ifEmpty { "unavailable" }

        return "F1_HEVC_PROFILE trackType=$trackType mime=${format.sampleMimeType ?: "?"} " +
            "codecs=${format.codecs ?: "?"} size=${format.width}x${format.height} " +
            "frameRate=${format.frameRate} initializationDataCount=${initializationData.size} " +
            "parameterNalCounts=${vpsUnits.size}/${spsUnits.size}/${ppsUnits.size} " +
            "parsedParameterCounts=${vps.size}/${sps.size}/${pps.size} " +
            "vps={$vpsSummary} sps={$spsSummary} pps={$ppsSummary}"
    }
}
