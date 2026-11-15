package app.apexview

import androidx.media3.common.Format
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
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
    data class Snapshot(
        val label: String,
        val group: String,
        val summary: String,
        val mime: String?,
        val codecs: String?,
        val width: Int,
        val height: Int,
        val parameterSetFingerprint: String?,
        val parsedParameterCounts: String,
        val drmSecurityLevel: String?,
        val widevineKeysLoaded: Boolean
    )

    fun summarize(trackType: Int, format: Format): String = inspect(trackType, format).summary

    fun inspect(trackType: Int, format: Format, label: String = "feed", group: String = "session"): Snapshot {
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

        val parsedCounts = "${vps.size}/${sps.size}/${pps.size}"
        val parameterNals = nals.sortedWith(compareBy({ it.type }, { it.bytes.size }))
        val fingerprint = parameterNals.takeIf { it.isNotEmpty() }?.let { units ->
            val digest = MessageDigest.getInstance("SHA-256")
            units.forEach { nal ->
                digest.update(nal.type.toByte())
                digest.update((nal.bytes.size ushr 24).toByte())
                digest.update((nal.bytes.size ushr 16).toByte())
                digest.update((nal.bytes.size ushr 8).toByte())
                digest.update(nal.bytes.size.toByte())
                digest.update(nal.bytes)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
        val summary = "F1_HEVC_PROFILE label=$label trackType=$trackType mime=${format.sampleMimeType ?: "?"} " +
            "codecs=${format.codecs ?: "?"} size=${format.width}x${format.height} " +
            "frameRate=${format.frameRate} initializationDataCount=${initializationData.size} " +
            "parameterNalCounts=${vpsUnits.size}/${spsUnits.size}/${ppsUnits.size} " +
            "parsedParameterCounts=$parsedCounts parameterSetFingerprint=${fingerprint ?: "unavailable"} " +
            "vps={$vpsSummary} sps={$spsSummary} pps={$ppsSummary}"
        return Snapshot(
            label = label,
            group = group,
            summary = summary,
            mime = format.sampleMimeType,
            codecs = format.codecs,
            width = format.width,
            height = format.height,
            parameterSetFingerprint = fingerprint,
            parsedParameterCounts = parsedCounts,
            drmSecurityLevel = null,
            widevineKeysLoaded = false
        )
    }
}


/**
 * Volatile, in-memory comparison of the last two standard single-feed observations. It holds
 * sanitised codec metadata only. It is not a CMAF/encryption compatibility or DRM-feasibility result.
 */
internal object HevcFeedProfileCapture {
    private val profiles = linkedMapOf<String, HevcFeedProfileProbe.Snapshot>()
    private var activeGroup: String? = null
    var diagnostic by mutableStateOf("HEVC_PROFILE_WAITING_FOR_FEED_A_AND_FEED_B")
        private set

    @Synchronized
    fun record(snapshot: HevcFeedProfileProbe.Snapshot) {
        if (activeGroup != snapshot.group) {
            profiles.clear()
            activeGroup = snapshot.group
        }
        profiles.remove(snapshot.label)
        profiles[snapshot.label] = snapshot
        while (profiles.size > 2) profiles.remove(profiles.keys.first())
        diagnostic = buildDiagnostic()
    }

    @Synchronized
    fun updateDrm(group: String, label: String, securityLevel: String, keysLoaded: Boolean) {
        if (activeGroup != group) return
        val current = profiles[label] ?: return
        profiles[label] = current.copy(
            drmSecurityLevel = securityLevel.takeIf { it.isNotBlank() },
            widevineKeysLoaded = current.widevineKeysLoaded || keysLoaded
        )
        diagnostic = buildDiagnostic()
    }

    private fun buildDiagnostic(): String {
        if (profiles.isEmpty()) return "HEVC_PROFILE_WAITING_FOR_FEED_A"
        val captured = profiles.values.toList()
        if (captured.size == 1) {
            val first = captured.single()
            return "HEVC_PROFILE_CAPTURED ${first.label}: ${first.mime ?: "?"} ${first.codecs ?: "?"} " +
                "${first.width}x${first.height}; parsed VPS/SPS/PPS=${first.parsedParameterCounts}; " +
                "fingerprint=${first.parameterSetFingerprint ?: "unavailable"}"
        }
        val first = captured[0]
        val second = captured[1]
        val reasons = buildList {
            if (first.mime != second.mime) add("MIME differs")
            if (first.codecs != second.codecs) add("codec string differs")
            if (first.width != second.width || first.height != second.height) add("dimensions differ")
            if (first.parameterSetFingerprint == null || second.parameterSetFingerprint == null) {
                add("parameter-set fingerprint unavailable")
            } else if (first.parameterSetFingerprint != second.parameterSetFingerprint) {
                add("VPS/SPS/PPS fingerprint differs")
            }
            if (first.parsedParameterCounts != second.parsedParameterCounts) add("parsed parameter counts differ")
        }
        val result = if (reasons.isEmpty()) "CODEC_PROFILE_MATCH" else "CODEC_PROFILE_MISMATCH"
        fun drm(profile: HevcFeedProfileProbe.Snapshot) =
            "DRM=${profile.drmSecurityLevel ?: "not-observed"},keysLoaded=${profile.widevineKeysLoaded}"
        return "$result ${first.label} vs ${second.label}: " +
            (reasons.ifEmpty { listOf("MIME, codec, dimensions and VPS/SPS/PPS fingerprint match") }.joinToString("; ")) +
            ". CMAF timing, encryption layout and secure-surface feasibility are not established. " +
            "${first.label}[${first.width}x${first.height},${first.codecs ?: "?"},${drm(first)}]; " +
            "${second.label}[${second.width}x${second.height},${second.codecs ?: "?"},${drm(second)}]"
    }
}
